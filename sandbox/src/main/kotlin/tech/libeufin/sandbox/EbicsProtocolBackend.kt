/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */


package tech.libeufin.sandbox

import io.ktor.application.ApplicationCall
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receiveText
import io.ktor.response.respond
import io.ktor.response.respondText
import org.apache.xml.security.binding.xmldsig.RSAKeyValueType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.stringParam
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upperCase
import org.w3c.dom.Document
import tech.libeufin.util.ebics_h004.*
import tech.libeufin.util.ebics_hev.HEVResponse
import tech.libeufin.util.ebics_hev.SystemReturnCodeType
import tech.libeufin.util.ebics_s001.SignatureTypes
import tech.libeufin.util.ebics_s001.UserSignatureData
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.EbicsOrderUtil
import tech.libeufin.util.XMLUtil
import tech.libeufin.util.*
import java.security.interfaces.RSAPrivateCrtKey
import java.util.*
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream
import javax.sql.rowset.serial.SerialBlob


open class EbicsRequestError(val errorText: String, val errorCode: String) :
    Exception("EBICS request management error: $errorText ($errorCode)")

class EbicsInvalidRequestError : EbicsRequestError("[EBICS_INVALID_REQUEST] Invalid request", "060102")

open class EbicsKeyManagementError(val errorText: String, val errorCode: String) :
    Exception("EBICS key management error: $errorText ($errorCode)")

private class EbicsInvalidXmlError : EbicsKeyManagementError("[EBICS_INVALID_XML]", "091010")

private class EbicsInvalidOrderType : EbicsRequestError(
    "[EBICS_UNSUPPORTED_ORDER_TYPE] Order type not supported",
    "091005"
)

private suspend fun ApplicationCall.respondEbicsKeyManagement(
    errorText: String,
    errorCode: String,
    bankReturnCode: String,
    dataTransfer: CryptoUtil.EncryptionResult? = null,
    orderId: String? = null
) {
    val responseXml = EbicsKeyManagementResponse().apply {
        version = "H004"
        header = EbicsKeyManagementResponse.Header().apply {
            authenticate = true
            mutable = EbicsKeyManagementResponse.MutableHeaderType().apply {
                reportText = errorText
                returnCode = errorCode
                if (orderId != null) {
                    this.orderID = orderId
                }
            }
            _static = EbicsKeyManagementResponse.EmptyStaticHeader()
        }
        body = EbicsKeyManagementResponse.Body().apply {
            this.returnCode = EbicsKeyManagementResponse.ReturnCode().apply {
                this.authenticate = true
                this.value = bankReturnCode
            }
            if (dataTransfer != null) {
                this.dataTransfer = EbicsKeyManagementResponse.DataTransfer().apply {
                    this.dataEncryptionInfo = EbicsTypes.DataEncryptionInfo().apply {
                        this.authenticate = true
                        this.transactionKey = dataTransfer.encryptedTransactionKey
                        this.encryptionPubKeyDigest = EbicsTypes.PubKeyDigest().apply {
                            this.algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                            this.version = "E002"
                            this.value = dataTransfer.pubKeyDigest
                        }
                    }
                    this.orderData = EbicsKeyManagementResponse.OrderData().apply {
                        this.value = dataTransfer.encryptedData
                    }
                }
            }
        }
    }
    val text = XMLUtil.convertJaxbToString(responseXml)
    LOGGER.info("responding with:\n${text}")
    respondText(text, ContentType.Application.Xml, HttpStatusCode.OK)
}

/* intra-day account traffic */
private fun ApplicationCall.handleEbicsC52(header: EbicsRequest.Header): ByteArray {

    val userId = header.static.userID!!
    val od = header.static.orderDetails ?: throw Exception("Need 'OrderDetails'")
    val op = od.orderParams ?: throw Exception("Need 'StandardOrderParams'")

    val subscriber = transaction {
        EbicsSubscriberEntity.find {
            stringParam(userId) eq EbicsSubscribersTable.userId // will have to match partner and system IDs
        }
    }.firstOrNull() ?: throw Exception("Unknown subscriber")

    val history = extractHistoryForEach(
        subscriber.bankCustomer.id.value,
        getGregorianDate().toString(),
        getGregorianDate().toString()
        /* Previous style where dates were fetched from the request:
        op as EbicsRequest.StandardOrderParams).dateRange?.start.toString(),
-       op.dateRange?.end.toString()
         */
    ) { println(it) }

    val ret = constructXml(indent = true) {
        namespace("foo", "bar")
        root("foo:BkToCstmrAcctRpt") {
            element("GrpHdr") {

                element("MsgId") {
                    text("id under group header")
                }
                element("CreDtTm") {
                    text("now")
                }
            }
            element("Rpt") {
                element("Id") {
                    text("id under report")
                }
                element("Acct") {
                    text("account identifier")
                }
            }
        }
    }
    return ret.toByteArray()
}

private suspend fun ApplicationCall.handleEbicsHia(header: EbicsUnsecuredRequest.Header, orderData: ByteArray) {
    val plainOrderData = InflaterInputStream(orderData.inputStream()).use {
        it.readAllBytes()
    }
    println("hia order data: ${plainOrderData.toString(Charsets.UTF_8)}")

    val keyObject = EbicsOrderUtil.decodeOrderDataXml<HIARequestOrderData>(orderData)
    val encPubXml = keyObject.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue
    val authPubXml = keyObject.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue
    val encPub = CryptoUtil.loadRsaPublicKeyFromComponents(encPubXml.modulus, encPubXml.exponent)
    val authPub = CryptoUtil.loadRsaPublicKeyFromComponents(authPubXml.modulus, authPubXml.exponent)

    transaction {
        val ebicsSubscriber = findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            LOGGER.warn("ebics subscriber not found")
            throw EbicsInvalidRequestError()
        }
        ebicsSubscriber.authenticationKey = EbicsSubscriberPublicKeyEntity.new {
            this.rsaPublicKey = SerialBlob(authPub.encoded)
            state = KeyState.NEW
        }
        ebicsSubscriber.encryptionKey = EbicsSubscriberPublicKeyEntity.new {
            this.rsaPublicKey = SerialBlob(encPub.encoded)
            state = KeyState.NEW
        }
        ebicsSubscriber.state = when (ebicsSubscriber.state) {
            SubscriberState.NEW -> SubscriberState.PARTIALLY_INITIALIZED_HIA
            SubscriberState.PARTIALLY_INITIALIZED_INI -> SubscriberState.INITIALIZED
            else -> ebicsSubscriber.state
        }
    }
    respondEbicsKeyManagement("[EBICS_OK]", "000000", "000000")
}


private suspend fun ApplicationCall.handleEbicsIni(header: EbicsUnsecuredRequest.Header, orderData: ByteArray) {
    val plainOrderData = InflaterInputStream(orderData.inputStream()).use {
        it.readAllBytes()
    }
    println("ini order data: ${plainOrderData.toString(Charsets.UTF_8)}")

    val keyObject = EbicsOrderUtil.decodeOrderDataXml<SignatureTypes.SignaturePubKeyOrderData>(orderData)
    val sigPubXml = keyObject.signaturePubKeyInfo.pubKeyValue.rsaKeyValue
    val sigPub = CryptoUtil.loadRsaPublicKeyFromComponents(sigPubXml.modulus, sigPubXml.exponent)

    transaction {
        val ebicsSubscriber =
            findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            LOGGER.warn("ebics subscriber ('${header.static.partnerID}' / '${header.static.userID}' / '${header.static.systemID}') not found")
            throw EbicsInvalidRequestError()
        }
        ebicsSubscriber.signatureKey = EbicsSubscriberPublicKeyEntity.new {
            this.rsaPublicKey = SerialBlob(sigPub.encoded)
            state = KeyState.NEW
        }
        ebicsSubscriber.state = when (ebicsSubscriber.state) {
            SubscriberState.NEW -> SubscriberState.PARTIALLY_INITIALIZED_INI
            SubscriberState.PARTIALLY_INITIALIZED_HIA -> SubscriberState.INITIALIZED
            else -> ebicsSubscriber.state
        }
    }
    LOGGER.info("Signature key inserted in database _and_ subscriber state changed accordingly")
    respondEbicsKeyManagement("[EBICS_OK]", "000000", bankReturnCode = "000000", orderId = "OR01")
}

private suspend fun ApplicationCall.handleEbicsHpb(
    ebicsHostInfo: EbicsHostPublicInfo,
    requestDocument: Document,
    header: EbicsNpkdRequest.Header
) {
    val subscriberKeys = transaction {
        val ebicsSubscriber =
            findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            throw EbicsInvalidRequestError()
        }
        if (ebicsSubscriber.state != SubscriberState.INITIALIZED) {
            throw EbicsInvalidRequestError()
        }
        val authPubBlob = ebicsSubscriber.authenticationKey!!.rsaPublicKey
        val encPubBlob = ebicsSubscriber.encryptionKey!!.rsaPublicKey
        val sigPubBlob = ebicsSubscriber.signatureKey!!.rsaPublicKey
        SubscriberKeys(
            CryptoUtil.loadRsaPublicKey(authPubBlob.toByteArray()),
            CryptoUtil.loadRsaPublicKey(encPubBlob.toByteArray()),
            CryptoUtil.loadRsaPublicKey(sigPubBlob.toByteArray())
        )
    }
    val validationResult =
        XMLUtil.verifyEbicsDocument(requestDocument, subscriberKeys.authenticationPublicKey)
    LOGGER.info("validationResult: $validationResult")
    if (!validationResult) {
        throw EbicsKeyManagementError("invalid signature", "90000");
    }
    val hpbRespondeData = HPBResponseOrderData().apply {
        this.authenticationPubKeyInfo = EbicsTypes.AuthenticationPubKeyInfoType().apply {
            this.authenticationVersion = "X002"
            this.pubKeyValue = EbicsTypes.PubKeyValueType().apply {
                this.rsaKeyValue = RSAKeyValueType().apply {
                    this.exponent = ebicsHostInfo.authenticationPublicKey.publicExponent.toByteArray()
                    this.modulus = ebicsHostInfo.authenticationPublicKey.modulus.toByteArray()
                }
            }
        }
        this.encryptionPubKeyInfo = EbicsTypes.EncryptionPubKeyInfoType().apply {
            this.encryptionVersion = "E002"
            this.pubKeyValue = EbicsTypes.PubKeyValueType().apply {
                this.rsaKeyValue = RSAKeyValueType().apply {
                    this.exponent = ebicsHostInfo.encryptionPublicKey.publicExponent.toByteArray()
                    this.modulus = ebicsHostInfo.encryptionPublicKey.modulus.toByteArray()
                }
            }
        }
        this.hostID = ebicsHostInfo.hostID
    }

    val compressedOrderData = EbicsOrderUtil.encodeOrderDataXml(hpbRespondeData)

    val encryptionResult = CryptoUtil.encryptEbicsE002(compressedOrderData, subscriberKeys.encryptionPublicKey)

    respondEbicsKeyManagement("[EBICS_OK]", "000000", "000000", encryptionResult, "OR01")
}

/**
 * Find the ebics host corresponding to the one specified in the header.
 */
private fun ApplicationCall.ensureEbicsHost(requestHostID: String): EbicsHostPublicInfo {
    return transaction {
        val ebicsHost =
            EbicsHostEntity.find { EbicsHostsTable.hostID.upperCase() eq requestHostID.toUpperCase() }.firstOrNull()
        if (ebicsHost == null) {
            LOGGER.warn("client requested unknown HostID")
            throw EbicsKeyManagementError("[EBICS_INVALID_HOST_ID]", "091011")
        }
        val encryptionPrivateKey = CryptoUtil.loadRsaPrivateKey(ebicsHost.encryptionPrivateKey.toByteArray())
        val authenticationPrivateKey = CryptoUtil.loadRsaPrivateKey(ebicsHost.authenticationPrivateKey.toByteArray())
        EbicsHostPublicInfo(
            requestHostID,
            CryptoUtil.getRsaPublicFromPrivate(encryptionPrivateKey),
            CryptoUtil.getRsaPublicFromPrivate(authenticationPrivateKey)
        )
    }
}


private suspend fun ApplicationCall.receiveEbicsXml(): Document {
    val body: String = receiveText()
    LOGGER.debug("Data received: $body")
    val requestDocument: Document? = XMLUtil.parseStringIntoDom(body)
    if (requestDocument == null || (!XMLUtil.validateFromDom(requestDocument))) {
        println("Problematic document was: $requestDocument")
        throw EbicsInvalidXmlError()
    }
    return requestDocument
}


fun handleEbicsHtd(): ByteArray {
    val htd = HTDResponseOrderData().apply {
        this.partnerInfo = EbicsTypes.PartnerInfo().apply {
            this.accountInfoList = listOf(
                EbicsTypes.AccountInfo().apply {
                    this.id = "acctid1"
                    this.accountHolder = "Mina Musterfrau"
                    this.accountNumberList = listOf(
                        EbicsTypes.GeneralAccountNumber().apply {
                            this.international = true
                            this.value = "DE21500105174751659277"
                        }
                    )
                    this.currency = "EUR"
                    this.description = "ACCT"
                    this.bankCodeList = listOf(
                        EbicsTypes.GeneralBankCode().apply {
                            this.international = true
                            this.value = "INGDDEFFXXX"
                        }
                    )
                },
                EbicsTypes.AccountInfo().apply {
                    this.id = "glsdemo"
                    this.accountHolder = "Mina Musterfrau"
                    this.accountNumberList = listOf(
                        EbicsTypes.GeneralAccountNumber().apply {
                            this.international = true
                            this.value = "DE91430609670123123123"
                        }
                    )
                    this.currency = "EUR"
                    this.description = "glsdemoacct"
                    this.bankCodeList = listOf(
                        EbicsTypes.GeneralBankCode().apply {
                            this.international = true
                            this.value = "GENODEM1GLS"
                        }
                    )
                }
            )
            this.addressInfo = EbicsTypes.AddressInfo().apply {
                this.name = "Foo"
            }
            this.bankInfo = EbicsTypes.BankInfo().apply {
                this.hostID = "host01"
            }
            this.orderInfoList = listOf(
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo1"
                    this.orderType = "C53"
                    this.transferType = "Download"
                },
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo2"
                    this.orderType = "C52"
                    this.transferType = "Download"
                },
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo3"
                    this.orderType = "CCC"
                    this.transferType = "Upload"
                },
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo4"
                    this.orderType = "VMK"
                    this.transferType = "Download"
                },
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo5"
                    this.orderType = "STA"
                    this.transferType = "Download"
                }
            )
        }
        this.userInfo = EbicsTypes.UserInfo().apply {
            this.name = "Some User"
            this.userID = EbicsTypes.UserIDType().apply {
                this.status = 5
                this.value = "USER1"
            }
            this.permissionList = listOf(
                EbicsTypes.UserPermission().apply {
                    this.orderTypes = "C53 C52 CCC VMK STA"
                }
            )
        }
    }

    val str = XMLUtil.convertJaxbToString(htd)
    return str.toByteArray()
}


fun handleEbicsHkd(): ByteArray {
    val hkd = HKDResponseOrderData().apply {
        this.partnerInfo = EbicsTypes.PartnerInfo().apply {
            this.accountInfoList = listOf(
                EbicsTypes.AccountInfo().apply {
                    this.id = "acctid1"
                    this.accountHolder = "Mina Musterfrau"
                    this.accountNumberList = listOf(
                        EbicsTypes.GeneralAccountNumber().apply {
                            this.international = true
                            this.value = "DE21500105174751659277"
                        }
                    )
                    this.currency = "EUR"
                    this.description = "ACCT"
                    this.bankCodeList = listOf(
                        EbicsTypes.GeneralBankCode().apply {
                            this.international = true
                            this.value = "INGDDEFFXXX"
                        }
                    )
                },
                EbicsTypes.AccountInfo().apply {
                    this.id = "glsdemo"
                    this.accountHolder = "Mina Musterfrau"
                    this.accountNumberList = listOf(
                        EbicsTypes.GeneralAccountNumber().apply {
                            this.international = true
                            this.value = "DE91430609670123123123"
                        }
                    )
                    this.currency = "EUR"
                    this.description = "glsdemoacct"
                    this.bankCodeList = listOf(
                        EbicsTypes.GeneralBankCode().apply {
                            this.international = true
                            this.value = "GENODEM1GLS"
                        }
                    )
                }
            )
            this.addressInfo = EbicsTypes.AddressInfo().apply {
                this.name = "Foo"
            }
            this.bankInfo = EbicsTypes.BankInfo().apply {
                this.hostID = "host01"
            }
            this.orderInfoList = listOf(
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo"
                    this.orderType = "C53"
                    this.transferType = "Download"
                },
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo"
                    this.orderType = "C52"
                    this.transferType = "Download"
                },
                EbicsTypes.AuthOrderInfoType().apply {
                    this.description = "foo"
                    this.orderType = "CCC"
                    this.transferType = "Upload"
                }
            )
        }
        this.userInfoList = listOf(
            EbicsTypes.UserInfo().apply {
                this.name = "Some User"
                this.userID = EbicsTypes.UserIDType().apply {
                    this.status = 1
                    this.value = "USER1"
                }
                this.permissionList = listOf(
                    EbicsTypes.UserPermission().apply {
                        this.orderTypes = "C54 C53 C52 CCC"
                    }
                )
            })
    }

    val str = XMLUtil.convertJaxbToString(hkd)
    return str.toByteArray()
}


fun signEbicsResponseX002(ebicsResponse: EbicsResponse, privateKey: RSAPrivateCrtKey): String {
    val doc = XMLUtil.convertJaxbToDocument(ebicsResponse)
    XMLUtil.signEbicsDocument(doc, privateKey)
    val signedDoc = XMLUtil.convertDomToString(doc)
    println("response: $signedDoc")
    return signedDoc
}

suspend fun ApplicationCall.ebicsweb() {
    val requestDocument = receiveEbicsXml()

    LOGGER.info("Processing ${requestDocument.documentElement.localName}")

    when (requestDocument.documentElement.localName) {
        "ebicsUnsecuredRequest" -> {
            val requestObject = requestDocument.toObject<EbicsUnsecuredRequest>()
            LOGGER.info("Serving a ${requestObject.header.static.orderDetails.orderType} request")

            val orderData = requestObject.body.dataTransfer.orderData.value
            val header = requestObject.header

            when (header.static.orderDetails.orderType) {
                "INI" -> handleEbicsIni(header, orderData)
                "HIA" -> handleEbicsHia(header, orderData)
                else -> throw EbicsInvalidXmlError()
            }
        }
        "ebicsHEVRequest" -> {
            val hevResponse = HEVResponse().apply {
                this.systemReturnCode = SystemReturnCodeType().apply {
                    this.reportText = "[EBICS_OK]"
                    this.returnCode = "000000"
                }
                this.versionNumber = listOf(HEVResponse.VersionNumber.create("H004", "02.50"))
            }

            val strResp = XMLUtil.convertJaxbToString(hevResponse)
            respondText(strResp, ContentType.Application.Xml, HttpStatusCode.OK)
        }
        "ebicsNoPubKeyDigestsRequest" -> {
            val requestObject = requestDocument.toObject<EbicsNpkdRequest>()
            val hostInfo = ensureEbicsHost(requestObject.header.static.hostID)
            when (requestObject.header.static.orderDetails.orderType) {
                "HPB" -> handleEbicsHpb(hostInfo, requestDocument, requestObject.header)
                else -> throw EbicsInvalidXmlError()
            }
        }
        "ebicsRequest" -> {
            println("ebicsRequest ${XMLUtil.convertDomToString(requestDocument)}")
            val requestObject = requestDocument.toObject<EbicsRequest>()
            val staticHeader = requestObject.header.static
            val requestedHostId = staticHeader.hostID

            val responseXmlStr = transaction {
                // Step 1 of 3:  Get information about the host and subscriber

                val ebicsHost =
                    EbicsHostEntity.find { EbicsHostsTable.hostID.upperCase() eq requestedHostId.toUpperCase() }
                        .firstOrNull()
                val requestTransactionID = requestObject.header.static.transactionID
                var downloadTransaction: EbicsDownloadTransactionEntity? = null
                var uploadTransaction: EbicsUploadTransactionEntity? =
                    null
                val subscriber = if (requestTransactionID != null) {
                    println("finding subscriber by transactionID $requestTransactionID")
                    downloadTransaction = EbicsDownloadTransactionEntity.findById(requestTransactionID.toUpperCase())
                    if (downloadTransaction != null) {
                        downloadTransaction.subscriber
                    } else {
                        uploadTransaction = EbicsUploadTransactionEntity.findById(requestTransactionID)
                        uploadTransaction?.subscriber
                    }
                } else {
                    val partnerID = staticHeader.partnerID ?: throw EbicsInvalidRequestError()
                    val userID = staticHeader.userID ?: throw EbicsInvalidRequestError()
                    findEbicsSubscriber(partnerID, userID, staticHeader.systemID)
                }

                if (ebicsHost == null) throw EbicsInvalidRequestError()
                if (subscriber == null) throw EbicsInvalidRequestError()

                val hostAuthPriv = CryptoUtil.loadRsaPrivateKey(
                    ebicsHost.authenticationPrivateKey
                        .toByteArray()
                )
                val hostEncPriv = CryptoUtil.loadRsaPrivateKey(
                    ebicsHost.encryptionPrivateKey
                        .toByteArray()
                )
                val clientAuthPub =
                    CryptoUtil.loadRsaPublicKey(subscriber.authenticationKey!!.rsaPublicKey.toByteArray())
                val clientEncPub =
                    CryptoUtil.loadRsaPublicKey(subscriber.encryptionKey!!.rsaPublicKey.toByteArray())
                val clientSigPub =
                    CryptoUtil.loadRsaPublicKey(subscriber.signatureKey!!.rsaPublicKey.toByteArray())

                // Step 2 of 3:  Validate the signature
                val verifyResult = XMLUtil.verifyEbicsDocument(requestDocument, clientAuthPub)
                if (!verifyResult) {
                    throw EbicsInvalidRequestError()
                }

                val ebicsResponse: EbicsResponse = when (requestObject.header.mutable.transactionPhase) {
                    EbicsTypes.TransactionPhaseType.INITIALISATION -> {
                        val transactionID = EbicsOrderUtil.generateTransactionId()
                        val orderType =
                            requestObject.header.static.orderDetails?.orderType ?: throw EbicsInvalidRequestError()
                        val partnerID = staticHeader.partnerID ?: throw EbicsInvalidRequestError()
                        val userID = staticHeader.userID ?: throw EbicsInvalidRequestError()
                        if (staticHeader.numSegments == null) {
                            println("handling initialization for order type $orderType")
                            val response = when (orderType) {
                                "HTD" -> handleEbicsHtd()
                                "HKD" -> handleEbicsHkd()

                                /* Temporarily handling C52/C53 with same logic */
                                "C52" -> handleEbicsC52(requestObject.header)
                                "C53" -> handleEbicsC52(requestObject.header)
                                else -> throw EbicsInvalidXmlError()
                            }

                            val compressedResponse = DeflaterInputStream(response.inputStream()).use {
                                it.readAllBytes()
                            }

                            val enc = CryptoUtil.encryptEbicsE002(compressedResponse, clientEncPub)
                            val encodedResponse = Base64.getEncoder().encodeToString(enc.encryptedData)

                            val segmentSize = 4096
                            val totalSize = encodedResponse.length
                            val numSegments = ((totalSize + segmentSize - 1) / segmentSize)

                            EbicsDownloadTransactionEntity.new(transactionID) {
                                this.subscriber = subscriber
                                this.host = ebicsHost
                                this.orderType = orderType
                                this.segmentSize = segmentSize
                                this.transactionKeyEnc = SerialBlob(enc.encryptedTransactionKey)
                                this.encodedResponse = encodedResponse
                                this.numSegments = numSegments
                                this.receiptReceived = false
                            }
                            EbicsResponse.createForDownloadInitializationPhase(
                                transactionID,
                                numSegments,
                                segmentSize,
                                enc,
                                encodedResponse
                            )
                        } else {
                            val oidn = subscriber.nextOrderID++
                            if (EbicsOrderUtil.checkOrderIDOverflow(oidn)) throw NotImplementedError()
                            val orderID = EbicsOrderUtil.computeOrderIDFromNumber(oidn)
                            val numSegments =
                                requestObject.header.static.numSegments ?: throw EbicsInvalidRequestError()
                            val transactionKeyEnc =
                                requestObject.body.dataTransfer?.dataEncryptionInfo?.transactionKey
                                    ?: throw EbicsInvalidRequestError()
                            val encPubKeyDigest =
                                requestObject.body.dataTransfer?.dataEncryptionInfo?.encryptionPubKeyDigest?.value
                            if (encPubKeyDigest == null)
                                throw EbicsInvalidRequestError()
                            val encSigData = requestObject.body.dataTransfer?.signatureData?.value
                            if (encSigData == null)
                                throw EbicsInvalidRequestError()
                            val decryptedSignatureData = CryptoUtil.decryptEbicsE002(
                                CryptoUtil.EncryptionResult(
                                    transactionKeyEnc,
                                    encPubKeyDigest,
                                    encSigData
                                ), hostEncPriv
                            )
                            val plainSigData = InflaterInputStream(decryptedSignatureData.inputStream()).use {
                                it.readAllBytes()
                            }

                            println("creating upload transaction for transactionID $transactionID")
                            EbicsUploadTransactionEntity.new(transactionID) {
                                this.host = ebicsHost
                                this.subscriber = subscriber
                                this.lastSeenSegment = 0
                                this.orderType = orderType
                                this.orderID = orderID
                                this.numSegments = numSegments.toInt()
                                this.transactionKeyEnc = SerialBlob(transactionKeyEnc)
                            }
                            val sigObj = XMLUtil.convertStringToJaxb<UserSignatureData>(plainSigData.toString(Charsets.UTF_8))
                            println("got UserSignatureData: ${plainSigData.toString(Charsets.UTF_8)}")
                            for (sig in sigObj.value.orderSignatureList ?: listOf()) {
                                println("inserting order signature for orderID $orderID and orderType $orderType")
                                EbicsOrderSignatureEntity.new {
                                    this.orderID = orderID
                                    this.orderType = orderType
                                    this.partnerID = sig.partnerID
                                    this.userID = sig.userID
                                    this.signatureAlgorithm = sig.signatureVersion
                                    this.signatureValue = SerialBlob(sig.signatureValue)
                                }
                            }

                            EbicsResponse.createForUploadInitializationPhase(transactionID, orderID)
                        }
                    }
                    EbicsTypes.TransactionPhaseType.TRANSFER -> {
                        requestTransactionID ?: throw EbicsInvalidRequestError()
                        val requestSegmentNumber =
                            requestObject.header.mutable.segmentNumber?.value?.toInt() ?: throw EbicsInvalidRequestError()
                        if (uploadTransaction != null) {
                            if (requestSegmentNumber == 1 && uploadTransaction.numSegments == 1) {
                                val encOrderData =
                                    requestObject.body.dataTransfer?.orderData ?: throw EbicsInvalidRequestError()
                                val zippedData = CryptoUtil.decryptEbicsE002(
                                    uploadTransaction.transactionKeyEnc.toByteArray(),
                                    encOrderData,
                                    hostEncPriv
                                )
                                val unzippedData =
                                    InflaterInputStream(zippedData.inputStream()).use { it.readAllBytes() }
                                println("got upload data: ${unzippedData.toString(Charsets.UTF_8)}")

                                val sigs  = EbicsOrderSignatureEntity.find {
                                    (EbicsOrderSignaturesTable.orderID eq uploadTransaction.orderID) and
                                            (EbicsOrderSignaturesTable.orderType eq uploadTransaction.orderType)
                                }

                                if (sigs.count() == 0) {
                                    throw EbicsInvalidRequestError()
                                }

                                val customCanon = unzippedData.filter { it != '\r'.toByte() && it != '\n'.toByte() && it != (26).toByte()}.toByteArray()

                                for (sig in sigs) {
                                    if (sig.signatureAlgorithm == "A006") {

                                        val signedData = CryptoUtil.digestEbicsOrderA006(unzippedData)
                                        val res1 = CryptoUtil.verifyEbicsA006(sig.signatureValue.toByteArray(), signedData, clientSigPub)

                                        if (!res1) {
                                            throw EbicsInvalidRequestError()
                                        }

                                    } else {
                                        throw NotImplementedError()
                                    }
                                }

                                EbicsResponse.createForUploadTransferPhase(
                                    requestTransactionID,
                                    requestSegmentNumber,
                                    true,
                                    uploadTransaction.orderID
                                )
                            } else {
                                throw NotImplementedError()
                            }
                        } else if (downloadTransaction != null) {
                            throw NotImplementedError()
                        } else {
                            throw AssertionError()
                        }
                    }
                    EbicsTypes.TransactionPhaseType.RECEIPT -> {
                        requestTransactionID ?: throw EbicsInvalidRequestError()
                        if (downloadTransaction == null)
                            throw EbicsInvalidRequestError()
                        val receiptCode =
                            requestObject.body.transferReceipt?.receiptCode ?: throw EbicsInvalidRequestError()
                        EbicsResponse.createForDownloadReceiptPhase(requestTransactionID, receiptCode == 0)
                    }
                }
                signEbicsResponseX002(ebicsResponse, hostAuthPriv)
            }
            respondText(responseXmlStr, ContentType.Application.Xml, HttpStatusCode.OK)
        }
        else -> {
            /* Log to console and return "unknown type" */
            LOGGER.info("Unknown message, just logging it!")
            respond(
                HttpStatusCode.NotImplemented,
                SandboxError("Not Implemented")
            )
        }
    }
}
