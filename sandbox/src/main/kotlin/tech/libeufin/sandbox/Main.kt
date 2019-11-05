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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import tech.libeufin.schema.ebics_h004.*
import tech.libeufin.schema.ebics_hev.HEVResponse
import tech.libeufin.schema.ebics_hev.SystemReturnCodeType
import tech.libeufin.schema.ebics_s001.SignaturePubKeyOrderData
import java.security.interfaces.RSAPublicKey
import java.text.DateFormat
import javax.sql.rowset.serial.SerialBlob
import javax.xml.bind.JAXBContext
import javax.xml.datatype.XMLGregorianCalendar

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")

data class EbicsRequestError(val statusCode: HttpStatusCode) : Exception("Ebics request error")

open class EbicsKeyManagementError(val errorText: String, val errorCode: String) :
    Exception("EBICS key management error: $errorText ($errorCode)")

class EbicsInvalidXmlError : EbicsKeyManagementError("[EBICS_INVALID_XML]", "091010")

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
            mutable = EbicsKeyManagementResponse.Header.KeyManagementResponseMutableHeaderType().apply {
                reportText = errorText
                returnCode = errorCode
                if (orderId != null) {
                    this.orderID = orderId
                }
            }
            _static = EbicsKeyManagementResponse.Header.EmptyStaticHeader()
        }
        body = EbicsKeyManagementResponse.Body().apply {
            this.returnCode = EbicsKeyManagementResponse.Body.ReturnCode().apply {
                this.authenticate = true
                this.value = bankReturnCode
            }
            if (dataTransfer != null) {
                this.dataTransfer = EbicsKeyManagementResponse.Body.DataTransfer().apply {
                    this.dataEncryptionInfo = DataEncryptionInfo().apply {
                        this.authenticate = true
                        this.transactionKey = dataTransfer.encryptedTransactionKey
                        this.encryptionPubKeyDigest = DataEncryptionInfo.EncryptionPubKeyDigest().apply {
                            this.algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                            this.version = "E002"
                            this.value = dataTransfer.pubKeyDigest
                        }
                    }
                    this.orderData = EbicsResponse.Body.DataTransferResponseType.OrderData().apply {
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
    val keyObject = EbicsOrderUtil.decodeOrderDataXml<HIARequestOrderDataType>(orderData)
    val encPubXml = keyObject.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue
    val authPubXml = keyObject.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue
    val encPub = CryptoUtil.loadRsaPublicKeyFromComponents(encPubXml.modulus, encPubXml.exponent)
    val authPub = CryptoUtil.loadRsaPublicKeyFromComponents(authPubXml.modulus, authPubXml.exponent)

    transaction {
        val ebicsSubscriber = findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            logger.warn("ebics subscriber not found")
            throw EbicsRequestError(HttpStatusCode.NotFound)
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
            throw EbicsRequestError(HttpStatusCode.NotFound)
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
    header: EbicsNoPubKeyDigestsRequest.Header
) {
    val subscriberKeys = transaction {
        val ebicsSubscriber =
            findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            throw EbicsRequestError(HttpStatusCode.Unauthorized)
        }
        if (ebicsSubscriber.state != SubscriberState.INITIALIZED) {
            throw EbicsRequestError(HttpStatusCode.Forbidden)
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
        this.authenticationPubKeyInfo = AuthenticationPubKeyInfoType().apply {
            this.authenticationVersion = "X002"
            this.pubKeyValue = PubKeyValueType().apply {
                this.rsaKeyValue = RSAKeyValueType().apply {
                    this.exponent = ebicsHostInfo.authenticationPublicKey.publicExponent.toByteArray()
                    this.modulus = ebicsHostInfo.authenticationPublicKey.modulus.toByteArray()
                }
            }
        }
        this.encryptionPubKeyInfo = EncryptionPubKeyInfoType().apply {
            this.encryptionVersion = "E002"
            this.pubKeyValue = PubKeyValueType().apply {
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
            val requestObject = requestDocument.toObject<EbicsNoPubKeyDigestsRequest>()
            val hostInfo = ensureEbicsHost(requestObject.header.static.hostID)
            when (requestObject.header.static.orderDetails.orderType) {
                "HPB" -> handleEbicsHpb(hostInfo, requestDocument, requestObject.header)
                else -> throw EbicsInvalidXmlError()
            }
        }
        "ebicsRequest" -> {
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
