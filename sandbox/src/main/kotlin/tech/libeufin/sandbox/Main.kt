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
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.request.receiveText
import io.ktor.request.uri
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.apache.xml.security.binding.xmldsig.RSAKeyValueType
import org.apache.xml.security.binding.xmldsig.SignatureType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import tech.libeufin.schema.ebics_h004.*
import tech.libeufin.schema.ebics_hev.HEVResponse
import tech.libeufin.schema.ebics_hev.SystemReturnCodeType
import tech.libeufin.schema.ebics_s001.SignaturePubKeyOrderData
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import java.text.DateFormat
import java.util.*
import java.util.zip.DeflaterInputStream
import javax.sql.rowset.serial.SerialBlob
import javax.xml.bind.JAXBContext

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")

open class EbicsRequestError(val errorText: String, val errorCode: String) :
    Exception("EBICS request management error: $errorText ($errorCode)")

class EbicsInvalidRequestError : EbicsRequestError("[EBICS_INVALID_REQUEST] Invalid request", "060102")

open class EbicsKeyManagementError(val errorText: String, val errorCode: String) :
    Exception("EBICS key management error: $errorText ($errorCode)")

class EbicsInvalidXmlError : EbicsKeyManagementError("[EBICS_INVALID_XML]", "091010")

class EbicsInvalidOrderType : EbicsRequestError("[EBICS_UNSUPPORTED_ORDER_TYPE] Order type not supported", "091005")

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


fun findEbicsSubscriber(partnerID: String, userID: String, systemID: String?): EbicsSubscriberEntity? {
    return if (systemID == null) {
        EbicsSubscriberEntity.find {
            (EbicsSubscribersTable.partnerId eq partnerID) and (EbicsSubscribersTable.userId eq userID)
        }
    } else {
        EbicsSubscriberEntity.find {
            (EbicsSubscribersTable.partnerId eq partnerID) and
                    (EbicsSubscribersTable.userId eq userID) and
                    (EbicsSubscribersTable.systemId eq systemID)
        }
    }.firstOrNull()
}


data class Subscriber(
    val partnerID: String,
    val userID: String,
    val systemID: String?
)

data class SubscriberKeys(
    val authenticationPublicKey: RSAPublicKey,
    val encryptionPublicKey: RSAPublicKey,
    val signaturePublicKey: RSAPublicKey
)


data class EbicsHostInfo(
    val hostID: String,
    val encryptionPublicKey: RSAPublicKey,
    val authenticationPublicKey: RSAPublicKey
)


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
    ebicsHostInfo: EbicsHostInfo,
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
private fun ApplicationCall.ensureEbicsHost(requestHostID: String): EbicsHostInfo {
    return transaction {
        val ebicsHost = EbicsHostEntity.find { EbicsHostsTable.hostID eq requestHostID }.firstOrNull()
        if (ebicsHost == null) {
            logger.warn("client requested unknown HostID")
            throw EbicsKeyManagementError("[EBICS_INVALID_HOST_ID]", "091011")
        }
        val encryptionPrivateKey = CryptoUtil.loadRsaPrivateKey(ebicsHost.encryptionPrivateKey.toByteArray())
        val authenticationPrivateKey = CryptoUtil.loadRsaPrivateKey(ebicsHost.authenticationPrivateKey.toByteArray())
        EbicsHostInfo(
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


inline fun <reified T> Document.toObject(): T {
    val jc = JAXBContext.newInstance(T::class.java)
    val m = jc.createUnmarshaller()
    return m.unmarshal(this, T::class.java).value
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


fun createEbicsResponseForDownloadTransferPhase() {

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
                    this.reportText = "[EBICS_DOWNLOAD_POSTPROCESS_DONE] Received negative receipt"
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


private suspend fun ApplicationCall.handleEbicsDownloadInitialization() {

}

private suspend fun ApplicationCall.handleEbicsDownloadTransfer() {

}

private suspend fun ApplicationCall.handleEbicsDownloadReceipt() {

}

private suspend fun ApplicationCall.handleEbicsUploadInitialization() {

}


private suspend fun ApplicationCall.ebicsweb() {
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

            when (requestObject.header.mutable.transactionPhase) {
                EbicsTypes.TransactionPhaseType.INITIALISATION -> {
                    val partnerID = staticHeader.partnerID ?: throw EbicsInvalidXmlError()
                    val userID = staticHeader.userID ?: throw EbicsInvalidXmlError()
                    val respText = transaction {
                        val subscriber =
                            findEbicsSubscriber(partnerID, userID, staticHeader.systemID)
                                ?: throw EbicsInvalidXmlError()
                        val requestedHostId = requestObject.header.static.hostID
                        val ebicsHost = EbicsHostEntity.find { EbicsHostsTable.hostID eq requestedHostId }.firstOrNull()
                        if (ebicsHost == null)
                            throw EbicsInvalidRequestError()
                        val hostAuthPriv = CryptoUtil.loadRsaPrivateKey(
                            ebicsHost.authenticationPrivateKey
                                .toByteArray()
                        )
                        val clientAuthPub =
                            CryptoUtil.loadRsaPublicKey(subscriber.authenticationKey!!.rsaPublicKey.toByteArray())
                        val clientEncPub =
                            CryptoUtil.loadRsaPublicKey(subscriber.encryptionKey!!.rsaPublicKey.toByteArray())
                        val verifyResult = XMLUtil.verifyEbicsDocument(requestDocument, clientAuthPub)
                        println("ebicsRequest verification result: $verifyResult")
                        val transactionID = EbicsOrderUtil.generateTransactionId()
                        val orderType = requestObject.header.static.orderDetails?.orderType

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

                        println("inner response: " + response.toString(Charsets.UTF_8))

                        println("total size: $totalSize")
                        println("num segments: $numSegments")

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

                        val ebicsResponse = createEbicsResponseForDownloadInitializationPhase(
                            transactionID,
                            numSegments, segmentSize, enc, encodedResponse
                        )
                        val docText = XMLUtil.convertJaxbToString(ebicsResponse)
                        val doc = XMLUtil.parseStringIntoDom(docText)
                        XMLUtil.signEbicsDocument(doc, hostAuthPriv)
                        val signedDoc = XMLUtil.convertDomToString(doc)
                        println("response: $signedDoc")
                        docText
                    }
                    respondText(respText, ContentType.Application.Xml, HttpStatusCode.OK)
                    return
                }
                EbicsTypes.TransactionPhaseType.TRANSFER -> {

                }
                EbicsTypes.TransactionPhaseType.RECEIPT -> {
                    val respText = transaction {
                        val requestedHostId = requestObject.header.static.hostID
                        val ebicsHost = EbicsHostEntity.find { EbicsHostsTable.hostID eq requestedHostId }.firstOrNull()
                        if (ebicsHost == null)
                            throw EbicsInvalidRequestError()
                        val hostAuthPriv = CryptoUtil.loadRsaPrivateKey(
                            ebicsHost.authenticationPrivateKey
                                .toByteArray()
                        )
                        val transactionID = requestObject.header.static.transactionID
                        if (transactionID == null)
                            throw EbicsInvalidRequestError()
                        val downloadTransaction = EbicsDownloadTransactionEntity.findById(transactionID)
                        if (downloadTransaction == null)
                            throw EbicsInvalidRequestError()
                        println("sending receipt for transaction ID $transactionID")
                        val receiptCode = requestObject.body.transferReceipt?.receiptCode
                        if (receiptCode == null)
                            throw EbicsInvalidRequestError()
                        val ebicsResponse = createEbicsResponseForDownloadReceiptPhase(transactionID, receiptCode == 0)
                        val docText = XMLUtil.convertJaxbToString(ebicsResponse)
                        val doc = XMLUtil.parseStringIntoDom(docText)
                        XMLUtil.signEbicsDocument(doc, hostAuthPriv)
                        val signedDoc = XMLUtil.convertDomToString(doc)
                        println("response: $signedDoc")
                        docText
                    }
                    respondText(respText, ContentType.Application.Xml, HttpStatusCode.OK)
                    return

                }
            }
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

fun main() {
    dbCreateTables()

    transaction {
        val pairA = CryptoUtil.generateRsaKeyPair(2048)
        val pairB = CryptoUtil.generateRsaKeyPair(2048)
        val pairC = CryptoUtil.generateRsaKeyPair(2048)
        EbicsHostEntity.new {
            hostId = "host01"
            ebicsVersion = "H004"
            authenticationPrivateKey = SerialBlob(pairA.private.encoded)
            encryptionPrivateKey = SerialBlob(pairB.private.encoded)
            signaturePrivateKey = SerialBlob(pairC.private.encoded)
        }

        EbicsSubscriberEntity.new {
            partnerId = "PARTNER1"
            userId = "USER1"
            systemId = null
            state = SubscriberState.NEW
            nextOrderID = 1
        }
    }

    val server = embeddedServer(Netty, port = 5000) {
        install(CallLogging)
        install(ContentNegotiation) {
            gson {
                setDateFormat(DateFormat.LONG)
                setPrettyPrinting()
            }
        }
        install(StatusPages) {
            exception<Throwable> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Internal server error.", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
            }
        }
        // TODO: add another intercept call that adds schema validation before the response is sent
        intercept(ApplicationCallPipeline.Fallback) {
            if (this.call.response.status() == null) {
                call.respondText("Not found (no route matched).\n", ContentType.Text.Plain, HttpStatusCode.NotFound)
                return@intercept finish()
            }
        }
        routing {
            //trace { logger.info(it.buildText()) }
            get("/") {
                call.respondText("Hello LibEuFin!\n", ContentType.Text.Plain)
            }
            get("/ebics/hosts") {
                val ebicsHosts = transaction {
                    EbicsHostEntity.all().map { it.hostId }
                }
                call.respond(EbicsHostsResponse(ebicsHosts))
            }
            post("/ebics/hosts") {
                val req = call.receive<EbicsHostCreateRequest>()
                transaction {
                    EbicsHostEntity.new {
                        this.ebicsVersion = req.ebicsVersion
                        this.hostId = hostId
                    }
                }
            }
            get("/ebics/hosts/{id}") {
                val resp = transaction {
                    val host = EbicsHostEntity.find { EbicsHostsTable.hostID eq call.parameters["id"]!! }.firstOrNull()
                    if (host == null) null
                    else EbicsHostResponse(host.hostId, host.ebicsVersion)
                }
                if (resp == null) call.respond(
                    HttpStatusCode.NotFound,
                    SandboxError("host not found")
                )
                else call.respond(resp)
            }
            get("/ebics/subscribers") {
                val subscribers = transaction {
                    EbicsSubscriberEntity.all().map { it.id.value.toString() }
                }
                call.respond(EbicsSubscribersResponse(subscribers))
            }
            get("/ebics/subscribers/{id}") {
                val resp = transaction {
                    val id = call.parameters["id"]!!
                    val subscriber = EbicsSubscriberEntity.findById(id.toInt())!!
                    EbicsSubscriberResponse(
                        id,
                        subscriber.partnerId,
                        subscriber.userId,
                        subscriber.systemId,
                        subscriber.state.name
                    )
                }
                call.respond(resp)
            }
            post("/ebicsweb") {
                call.ebicsweb()
            }
        }
    }
    logger.info("Up and running")
    server.start(wait = true)
}
