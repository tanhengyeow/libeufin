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
import org.apache.xml.security.binding.xmldsig.SignatureType
import org.jetbrains.exposed.sql.transactions.transaction
import org.w3c.dom.Document
import tech.libeufin.schema.ebics_h004.*
import tech.libeufin.schema.ebics_hev.HEVResponse
import tech.libeufin.schema.ebics_hev.SystemReturnCodeType
import tech.libeufin.schema.ebics_s001.SignaturePubKeyOrderData
import java.math.BigInteger
import java.util.*
import java.util.zip.DeflaterInputStream
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
    logger.info("responding with:\n${text}")
    respondText(text, ContentType.Application.Xml, HttpStatusCode.OK)
}


private suspend fun ApplicationCall.handleEbicsHia(header: EbicsUnsecuredRequest.Header, orderData: ByteArray) {
    val keyObject = EbicsOrderUtil.decodeOrderDataXml<HIARequestOrderData>(orderData)
    val encPubXml = keyObject.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue
    val authPubXml = keyObject.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue
    val encPub = CryptoUtil.loadRsaPublicKeyFromComponents(encPubXml.modulus, encPubXml.exponent)
    val authPub = CryptoUtil.loadRsaPublicKeyFromComponents(authPubXml.modulus, authPubXml.exponent)

    transaction {
        val ebicsSubscriber = findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            logger.warn("ebics subscriber not found")
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
    val keyObject = EbicsOrderUtil.decodeOrderDataXml<SignaturePubKeyOrderData>(orderData)
    val sigPubXml = keyObject.signaturePubKeyInfo.pubKeyValue.rsaKeyValue
    val sigPub = CryptoUtil.loadRsaPublicKeyFromComponents(sigPubXml.modulus, sigPubXml.exponent)

    transaction {
        val ebicsSubscriber =
            findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            logger.warn("ebics subscriber ('${header.static.partnerID}' / '${header.static.userID}' / '${header.static.systemID}') not found")
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
    logger.info("Signature key inserted in database _and_ subscriber state changed accordingly")
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
    logger.info("validationResult: $validationResult")
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
        val ebicsHost = EbicsHostEntity.find { EbicsHostsTable.hostID eq requestHostID }.firstOrNull()
        if (ebicsHost == null) {
            logger.warn("client requested unknown HostID")
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
    logger.debug("Data received: $body")
    val requestDocument: Document? = XMLUtil.parseStringIntoDom(body)
    if (requestDocument == null || (!XMLUtil.validateFromDom(requestDocument))) {
        throw EbicsInvalidXmlError()
    }
    return requestDocument
}


fun handleEbicsHtd(): ByteArray {
    val htd = HTDResponseOrderData().apply {
        this.partnerInfo = HTDResponseOrderData.PartnerInfo().apply {
            this.accountInfoList = listOf(
                HTDResponseOrderData.AccountInfo().apply {
                    this.id = "acctid1"
                    this.accountHolder = "Mina Musterfrau"
                    this.accountNumberList = listOf(
                        HTDResponseOrderData.GeneralAccountNumber().apply {
                            this.international = true
                            this.value = "DE21500105174751659277"
                        }
                    )
                    this.currency = "EUR"
                    this.description = "ACCT"
                    this.bankCodeList = listOf(
                        HTDResponseOrderData.GeneralBankCode().apply {
                            this.international = true
                            this.value = "INGDDEFFXXX"
                        }
                    )
                },
                HTDResponseOrderData.AccountInfo().apply {
                    this.id = "glsdemo"
                    this.accountHolder = "Mina Musterfrau"
                    this.accountNumberList = listOf(
                        HTDResponseOrderData.GeneralAccountNumber().apply {
                            this.international = true
                            this.value = "DE91430609670123123123"
                        }
                    )
                    this.currency = "EUR"
                    this.description = "glsdemoacct"
                    this.bankCodeList = listOf(
                        HTDResponseOrderData.GeneralBankCode().apply {
                            this.international = true
                            this.value = "GENODEM1GLS"
                        }
                    )
                }
            )
            this.addressInfo = HTDResponseOrderData.AddressInfo().apply {
                this.name = "Foo"
            }
            this.bankInfo = HTDResponseOrderData.BankInfo().apply {
                this.hostID = "host01"
            }
            this.orderInfoList = listOf(
                HTDResponseOrderData.AuthOrderInfoType().apply {
                    this.description = "foo"
                    this.orderType = "C53"
                    this.transferType = "Download"
                },
                HTDResponseOrderData.AuthOrderInfoType().apply {
                    this.description = "foo"
                    this.orderType = "C52"
                    this.transferType = "Download"
                },
                HTDResponseOrderData.AuthOrderInfoType().apply {
                    this.description = "foo"
                    this.orderType = "CCC"
                    this.transferType = "Upload"
                }
            )
        }
        this.userInfo = HTDResponseOrderData.UserInfo().apply {
            this.name = "Some User"
            this.userID = HTDResponseOrderData.UserIDType().apply {
                this.status = 5
                this.value = "USER1"
            }
            this.permissionList = listOf(
                HTDResponseOrderData.UserPermission().apply {
                    this.orderTypes = "C54 C53 C52 CCC"
                }
            )
        }
    }

    val str = XMLUtil.convertJaxbToString(htd)
    return str.toByteArray()
}


fun createEbicsResponseForDownloadInitializationPhase(
    transactionID: String,
    numSegments: Int,
    segmentSize: Int,
    enc: CryptoUtil.EncryptionResult,
    encodedData: String
): EbicsResponse {
    return EbicsResponse().apply {
        this.version = "H004"
        this.revision = 1
        this.header = EbicsResponse.Header().apply {
            this.authenticate = true
            this._static = EbicsResponse.StaticHeaderType().apply {
                this.transactionID = transactionID
                this.numSegments = BigInteger.valueOf(numSegments.toLong())
            }
            this.mutable = EbicsResponse.MutableHeaderType().apply {
                this.transactionPhase = EbicsTypes.TransactionPhaseType.INITIALISATION
                this.segmentNumber = EbicsResponse.SegmentNumber().apply {
                    this.lastSegment = (numSegments == 1)
                    this.value = BigInteger.valueOf(1)
                }
                this.reportText = "[EBICS_OK] OK"
                this.returnCode = "000000"
            }
        }
        this.authSignature = SignatureType()
        this.body = EbicsResponse.Body().apply {
            this.returnCode = EbicsResponse.ReturnCode().apply {
                this.authenticate = true
                this.value = "000000"
            }
            this.dataTransfer = EbicsResponse.DataTransferResponseType().apply {
                this.dataEncryptionInfo = EbicsTypes.DataEncryptionInfo().apply {
                    this.authenticate = true
                    this.encryptionPubKeyDigest = EbicsTypes.PubKeyDigest().apply {
                        this.algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                        this.version = "E002"
                        this.value = enc.pubKeyDigest
                    }
                    this.transactionKey = enc.encryptedTransactionKey
                }
                this.orderData = EbicsResponse.OrderData().apply {
                    this.value = encodedData.substring(0, Math.min(segmentSize, encodedData.length))
                }
            }
        }
    }
}


fun createEbicsResponseForDownloadReceiptPhase(transactionID: String, positiveAck: Boolean): EbicsResponse {
    return EbicsResponse().apply {
        this.version = "H004"
        this.revision = 1
        this.header = EbicsResponse.Header().apply {
            this.authenticate = true
            this._static = EbicsResponse.StaticHeaderType().apply {
                this.transactionID = transactionID
            }
            this.mutable = EbicsResponse.MutableHeaderType().apply {
                this.transactionPhase = EbicsTypes.TransactionPhaseType.RECEIPT
                if (positiveAck) {
                    this.reportText = "[EBICS_DOWNLOAD_POSTPROCESS_DONE] Received positive receipt"
                    this.returnCode = "011000"
                } else {
                    this.reportText = "[EBICS_DOWNLOAD_POSTPROCESS_SKIPPED] Received negative receipt"
                    this.returnCode = "011001"
                }
            }
        }
        this.authSignature = SignatureType()
        this.body = EbicsResponse.Body().apply {
            this.returnCode = EbicsResponse.ReturnCode().apply {
                this.authenticate = true
                this.value = "000000"
            }
        }
    }
}

fun createEbicsResponseForUploadInitializationPhase(transactionID: String, orderID: String): EbicsResponse {
    return EbicsResponse().apply {
        this.version = "H004"
        this.revision = 1
        this.header = EbicsResponse.Header().apply {
            this.authenticate = true
            this._static = EbicsResponse.StaticHeaderType().apply {
                this.transactionID = transactionID
            }
            this.mutable = EbicsResponse.MutableHeaderType().apply {
                this.transactionPhase = EbicsTypes.TransactionPhaseType.INITIALISATION
                this.orderID = orderID
                this.reportText = "[EBICS_OK] OK"
                this.returnCode = "000000"
            }
        }
        this.authSignature = SignatureType()
        this.body = EbicsResponse.Body().apply {
            this.returnCode = EbicsResponse.ReturnCode().apply {
                this.authenticate = true
                this.value = "000000"
            }
        }
    }
}


suspend fun ApplicationCall.ebicsweb() {
    val requestDocument = receiveEbicsXml()

    logger.info("Processing ${requestDocument.documentElement.localName}")

    when (requestDocument.documentElement.localName) {
        "ebicsUnsecuredRequest" -> {
            val requestObject = requestDocument.toObject<EbicsUnsecuredRequest>()
            logger.info("Serving a ${requestObject.header.static.orderDetails.orderType} request")

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

                val ebicsHost = EbicsHostEntity.find { EbicsHostsTable.hostID eq requestedHostId }.firstOrNull()
                val requestTransactionID = requestObject.header.static.transactionID
                var downloadTransaction: EbicsDownloadTransactionEntity? = null
                var uploadTransaction: EbicsUploadTransactionEntity? = null
                val subscriber = if (requestTransactionID != null) {
                    downloadTransaction = EbicsDownloadTransactionEntity.findById(requestTransactionID)
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
                val clientAuthPub =
                    CryptoUtil.loadRsaPublicKey(subscriber.authenticationKey!!.rsaPublicKey.toByteArray())
                val clientEncPub =
                    CryptoUtil.loadRsaPublicKey(subscriber.encryptionKey!!.rsaPublicKey.toByteArray())

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
                        if (staticHeader.numSegments == null) {
                            val response = when (orderType) {
                                "HTD" -> handleEbicsHtd()
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
                            createEbicsResponseForDownloadInitializationPhase(
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
                            val signatureData = requestObject.body.dataTransfer?.signatureData
                            if (signatureData != null) {
                                println("signature data: ${signatureData.toString(Charsets.UTF_8)}")
                            }
                            val numSegments =
                                requestObject.header.static.numSegments ?: throw EbicsInvalidRequestError()
                            val transactionKeyEnc =
                                requestObject.body.dataTransfer?.dataEncryptionInfo?.transactionKey
                                    ?: throw EbicsInvalidRequestError()
                            EbicsUploadTransactionEntity.new(transactionID) {
                                this.host = ebicsHost
                                this.subscriber = subscriber
                                this.lastSeenSegment = 0
                                this.orderType = orderType
                                this.orderID = orderID
                                this.numSegments = numSegments.toInt()
                                this.transactionKeyEnc = SerialBlob(transactionKeyEnc)
                            }
                            createEbicsResponseForUploadInitializationPhase(transactionID, orderID)
                        }
                    }
                    EbicsTypes.TransactionPhaseType.TRANSFER -> {
                        throw NotImplementedError()
                    }
                    EbicsTypes.TransactionPhaseType.RECEIPT -> {
                        requestTransactionID ?: throw EbicsInvalidRequestError()
                        if (downloadTransaction == null)
                            throw EbicsInvalidRequestError()
                        val receiptCode =
                            requestObject.body.transferReceipt?.receiptCode ?: throw EbicsInvalidRequestError()
                        createEbicsResponseForDownloadReceiptPhase(requestTransactionID, receiptCode == 0)
                    }
                }
                val docText = XMLUtil.convertJaxbToString(ebicsResponse)
                val doc = XMLUtil.parseStringIntoDom(docText)
                XMLUtil.signEbicsDocument(doc, hostAuthPriv)
                val signedDoc = XMLUtil.convertDomToString(doc)
                println("response: $signedDoc")
                docText
            }
            respondText(responseXmlStr, ContentType.Application.Xml, HttpStatusCode.OK)
        }
        else -> {
            /* Log to console and return "unknown type" */
            logger.info("Unknown message, just logging it!")
            respond(
                HttpStatusCode.NotImplemented,
                SandboxError("Not Implemented")
            )
        }
    }
}
