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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import tech.libeufin.sandbox.db.*
import tech.libeufin.schema.ebics_h004.EbicsKeyManagementResponse
import tech.libeufin.schema.ebics_h004.EbicsUnsecuredRequest
import tech.libeufin.schema.ebics_h004.HIARequestOrderDataType
import tech.libeufin.schema.ebics_hev.HEVResponse
import tech.libeufin.schema.ebics_hev.SystemReturnCodeType
import tech.libeufin.schema.ebics_s001.SignaturePubKeyOrderData
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.charset.StandardCharsets.UTF_8
import java.security.interfaces.RSAPublicKey
import java.text.DateFormat
import java.util.zip.InflaterInputStream
import javax.sql.rowset.serial.SerialBlob

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")

val xmlProcess = XMLUtil()

data class EbicsRequestError(val statusCode: HttpStatusCode) : Exception("Ebics request error")

private suspend fun ApplicationCall.respondEbicsKeyManagement(
    errorText: String,
    errorCode: String,
    statusCode: HttpStatusCode,
    orderId: String? = null,
    bankReturnCode: String? = null
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
            if (bankReturnCode != null) {
                this.returnCode = EbicsKeyManagementResponse.Body.ReturnCode().apply {
                    this.authenticate = true
                    this.value = bankReturnCode
                }
            }
        }
    }
    val text = XMLUtil.convertJaxbToString(responseXml)
    logger.info("responding with:\n${text}")
    respondText(text, ContentType.Application.Xml, statusCode)
}


private suspend fun ApplicationCall.respondEbicsInvalidXml() {
    respondEbicsKeyManagement("[EBICS_INVALID_XML]", "091010", HttpStatusCode.BadRequest)
}


fun findEbicsSubscriber(partnerID: String, userID: String, systemID: String?): EbicsSubscriber? {
    return if (systemID == null) {
        EbicsSubscriber.find {
            (EbicsSubscribers.partnerId eq partnerID) and (EbicsSubscribers.userId eq userID)
        }
    } else {
        EbicsSubscriber.find {
            (EbicsSubscribers.partnerId eq partnerID) and
                    (EbicsSubscribers.userId eq userID) and
                    (EbicsSubscribers.systemId eq systemID)
        }
    }.firstOrNull()
}

private suspend fun ApplicationCall.ebicsweb() {
    val body: String = receiveText()
    logger.debug("Data received: $body")

    val bodyDocument: Document? = XMLUtil.parseStringIntoDom(body)

    if (bodyDocument == null || (!xmlProcess.validateFromDom(bodyDocument))) {
        respondEbicsInvalidXml()
        return
    }

    logger.info("Processing ${bodyDocument.documentElement.localName}")

    when (bodyDocument.documentElement.localName) {
        "ebicsUnsecuredRequest" -> {

            val bodyJaxb = XMLUtil.convertDomToJaxb(
                EbicsUnsecuredRequest::class.java,
                bodyDocument
            )

            val staticHeader = bodyJaxb.value.header.static
            val requestHostID = bodyJaxb.value.header.static.hostID

            val ebicsHost = transaction {
                EbicsHost.find { EbicsHosts.hostID eq requestHostID }.firstOrNull()
            }

            if (ebicsHost == null) {
                logger.warn("client requested unknown HostID")
                respondEbicsKeyManagement("[EBICS_INVALID_HOST_ID]", "091011", HttpStatusCode.NotFound)
                return
            }

            logger.info("Serving a ${bodyJaxb.value.header.static.orderDetails.orderType} request")

            /**
             * NOTE: the JAXB interface has some automagic mechanism that decodes
             * the Base64 string into its byte[] form _at the same time_ it instantiates
             * the object; in other words, there is no need to perform here the decoding.
             */
            val zkey = bodyJaxb.value.body.dataTransfer.orderData.value

            /**
             * The validation enforces zkey to be a base64 value, but does not check
             * whether it is given _empty_ or not; will check explicitly here.  FIXME:
             * shall the schema be patched to avoid having this if-block here?
             */
            if (zkey.isEmpty()) {
                logger.info("0-length key element given, invalid request")
                respondEbicsInvalidXml()
                return
            }

            /**
             * This value holds the bytes[] of a XML "SignaturePubKeyOrderData" document
             * and at this point is valid and _never_ empty.
             */
            val inflater = InflaterInputStream(zkey.inputStream())

            var payload = try {
                ByteArray(1) { inflater.read().toByte() }
            } catch (e: Exception) {
                e.printStackTrace()
                respondEbicsInvalidXml()
                return
            }

            while (inflater.available() == 1) {
                payload += inflater.read().toByte()
            }

            inflater.close()

            logger.debug("Found payload: ${payload.toString(US_ASCII)}")

            when (bodyJaxb.value.header.static.orderDetails.orderType) {
                "INI" -> {
                    val keyObject = XMLUtil.convertStringToJaxb<SignaturePubKeyOrderData>(payload.toString(UTF_8))

                    val rsaPublicKey: RSAPublicKey = try {
                        CryptoUtil.loadRsaPublicKeyFromComponents(
                            keyObject.value.signaturePubKeyInfo.pubKeyValue.rsaKeyValue.modulus,
                            keyObject.value.signaturePubKeyInfo.pubKeyValue.rsaKeyValue.exponent
                        )
                    } catch (e: Exception) {
                        logger.info("User gave bad key, not storing it")
                        e.printStackTrace()
                        respondEbicsInvalidXml()
                        return
                    }

                    // put try-catch block here? (FIXME)
                    transaction {
                        val ebicsSubscriber =
                            findEbicsSubscriber(staticHeader.partnerID, staticHeader.userID, staticHeader.systemID)
                        if (ebicsSubscriber == null) {
                            logger.warn("ebics subscriber ('${staticHeader.partnerID}' / '${staticHeader.userID}' / '${staticHeader.systemID}') not found")
                            throw EbicsRequestError(HttpStatusCode.NotFound)
                        }
                        ebicsSubscriber.signatureKey = EbicsPublicKey.new {
                            this.rsaPublicKey = SerialBlob(rsaPublicKey.encoded)
                            state = KeyState.NEW
                        }

                        if (ebicsSubscriber.state == SubscriberState.NEW) {
                            ebicsSubscriber.state = SubscriberState.PARTIALLY_INITIALIZED_INI
                        }

                        if (ebicsSubscriber.state == SubscriberState.PARTIALLY_INITIALIZED_HIA) {
                            ebicsSubscriber.state = SubscriberState.INITIALIZED
                        }
                    }

                    logger.info("Signature key inserted in database _and_ subscriber state changed accordingly")
                    respondEbicsKeyManagement(
                        "[EBICS_OK]",
                        "000000",
                        HttpStatusCode.OK,
                        bankReturnCode = "000000",
                        orderId = "OR01"
                    )
                    return
                }

                "HIA" -> {
                    val keyObject = XMLUtil.convertStringToJaxb<HIARequestOrderDataType>(payload.toString(US_ASCII))

                    val authenticationPublicKey = try {
                        CryptoUtil.loadRsaPublicKeyFromComponents(
                            keyObject.value.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.modulus,
                            keyObject.value.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.exponent
                        )
                    } catch (e: Exception) {
                        logger.info("auth public key invalid")
                        e.printStackTrace()
                        respondEbicsInvalidXml()
                        return
                    }

                    val encryptionPublicKey = try {
                        CryptoUtil.loadRsaPublicKeyFromComponents(
                            keyObject.value.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue.modulus,
                            keyObject.value.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue.exponent
                        )
                    } catch (e: Exception) {
                        logger.info("auth public key invalid")
                        e.printStackTrace()
                        respondEbicsInvalidXml()
                        return
                    }

                    transaction {
                        val ebicsSubscriber =
                            findEbicsSubscriber(staticHeader.partnerID, staticHeader.userID, staticHeader.systemID)
                        if (ebicsSubscriber == null) {
                            logger.warn("ebics subscriber not found")
                            throw EbicsRequestError(HttpStatusCode.NotFound)
                        }
                        ebicsSubscriber.authenticationKey = EbicsPublicKey.new {
                            this.rsaPublicKey = SerialBlob(authenticationPublicKey.encoded)
                            state = KeyState.NEW
                        }
                        ebicsSubscriber.encryptionKey = EbicsPublicKey.new {
                            this.rsaPublicKey = SerialBlob(encryptionPublicKey.encoded)
                            state = KeyState.NEW
                        }

                        if (ebicsSubscriber.state == SubscriberState.NEW) {
                            ebicsSubscriber.state = SubscriberState.PARTIALLY_INITIALIZED_HIA
                        }

                        if (ebicsSubscriber.state == SubscriberState.PARTIALLY_INITIALIZED_INI) {
                            ebicsSubscriber.state = SubscriberState.INITIALIZED
                        }
                    }
                    respondEbicsKeyManagement("[EBICS_OK]", "000000", HttpStatusCode.OK)
                }
            }

            throw AssertionError("not reached")
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
            return
        }
        else -> {
            /* Log to console and return "unknown type" */
            logger.info("Unknown message, just logging it!")
            respond(
                HttpStatusCode.NotImplemented,
                SandboxError("Not Implemented")
            )
            return
        }
    }
}


fun main() {
    dbCreateTables()

    transaction {
        val pairA = CryptoUtil.generateRsaKeyPair(2048)
        val pairB = CryptoUtil.generateRsaKeyPair(2048)
        val pairC = CryptoUtil.generateRsaKeyPair(2048)
        EbicsHost.new {
            hostId = "host01"
            ebicsVersion = "H004"
            authenticationPrivateKey = SerialBlob(pairA.private.encoded)
            encryptionPrivateKey = SerialBlob(pairB.private.encoded)
            signaturePrivateKey = SerialBlob(pairC.private.encoded)
        }

        EbicsSubscriber.new {
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
                logger.error("Exception while handling '${call.request.uri.toString()}'", cause)
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
                    EbicsHost.all().map { it.hostId }
                }
                call.respond(EbicsHostsResponse(ebicsHosts))
            }
            post("/ebics/hosts") {
                val req = call.receive<EbicsHostCreateRequest>()
                transaction {
                    EbicsHost.new {
                        this.ebicsVersion = req.ebicsVersion
                        this.hostId = hostId
                    }
                }
            }
            get("/ebics/hosts/{id}") {
                val resp = transaction {
                    val host = EbicsHost.find { EbicsHosts.hostID eq call.parameters["id"]!! }.firstOrNull()
                    if (host == null) null
                    else EbicsHostResponse(host.hostId, host.ebicsVersion)
                }
                if (resp == null) call.respond(HttpStatusCode.NotFound, SandboxError("host not found"))
                else call.respond(resp)
            }
            get("/ebics/subscribers") {
                val subscribers = transaction {
                    EbicsSubscriber.all().map { it.id.value.toString() }
                }
                call.respond(EbicsSubscribersResponse(subscribers))
            }
            get("/ebics/subscribers/{id}") {
                val resp = transaction {
                    val id = call.parameters["id"]!!
                    val subscriber = EbicsSubscriber.findById(id.toInt())!!
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
