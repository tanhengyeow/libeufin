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

package tech.libeufin.nexus

import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.*
import io.ktor.client.features.ServerResponseException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.request.receive
import io.ktor.request.uri
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import tech.libeufin.sandbox.*
import tech.libeufin.schema.ebics_h004.EbicsKeyManagementResponse
import tech.libeufin.schema.ebics_h004.EbicsUnsecuredRequest
import tech.libeufin.schema.ebics_s001.PubKeyValueType
import tech.libeufin.schema.ebics_s001.SignaturePubKeyInfoType
import tech.libeufin.schema.ebics_s001.SignaturePubKeyOrderData
import java.text.DateFormat
import javax.sql.rowset.serial.SerialBlob
import javax.xml.bind.JAXBElement

fun testData() {

    val pairA = CryptoUtil.generateRsaKeyPair(2048)
    val pairB = CryptoUtil.generateRsaKeyPair(2048)
    val pairC = CryptoUtil.generateRsaKeyPair(2048)

    transaction {
        EbicsSubscriberEntity.new {
            ebicsURL = "http://localhost:5000/ebicsweb"
            userID = "USER1"
            partnerID = "PARTNER1"
            hostID = "host01"

            signaturePrivateKey = SerialBlob(pairA.private.encoded)
            encryptionPrivateKey = SerialBlob(pairB.private.encoded)
            authenticationPrivateKey = SerialBlob(pairC.private.encoded)
        }
    }
}

fun expectId(param: String?) : Int {

    try {
        return param!!.toInt()
    } catch (e: Exception) {
        throw NotAnIdError(HttpStatusCode.BadRequest)
    }
}

/**
 * @return null when the bank could not be reached, otherwise returns the
 * response already converted in JAXB.
 */
suspend inline fun <reified S, reified T>HttpClient.postToBank(url: String, body: T) : JAXBElement<S>? {

    val response = try {
        this.post<String>(
            urlString = url,
            block = {
                this.body = XMLUtil.convertJaxbToString(body)
            }
        )
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }

    // note: not checking status code, as EBICS mandates to return "200 OK" for ANY outcome.
    return XMLUtil.convertStringToJaxb(response)
}

data class NotAnIdError(val statusCode: HttpStatusCode) : Exception("String ID not convertible in number")
data class SubscriberNotFoundError(val statusCode: HttpStatusCode) : Exception("Subscriber not found in database")
data class UnreachableBankError(val statusCode: HttpStatusCode) : Exception("Could not reach the bank")


fun main() {
    dbCreateTables()
    testData() // gets always id == 1
    val client = HttpClient()

    val logger = LoggerFactory.getLogger("tech.libeufin.nexus")

    val server = embeddedServer(Netty, port = 5001) {

        install(ContentNegotiation) {
            gson {
                setDateFormat(DateFormat.LONG)
                setPrettyPrinting()
            }
        }

        install(StatusPages) {
            exception<Throwable> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Internal server error.\n", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
            }

            exception<NotAnIdError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Bad request\n", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            }

            exception<UnreachableBankError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Could not reach the bank\n", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
            }

            exception<SubscriberNotFoundError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Subscriber not found\n", ContentType.Text.Plain, HttpStatusCode.NotFound)
            }

            exception<javax.xml.bind.UnmarshalException> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText(
                    "Could not convert string into JAXB (either from client or from bank)\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.NotFound
                )
            }
        }

        intercept(ApplicationCallPipeline.Fallback) {
            if (this.call.response.status() == null) {
                call.respondText("Not found (no route matched).\n", ContentType.Text.Plain, HttpStatusCode.NotFound)
                return@intercept finish()
            }
        }

        routing {
            get("/") {
                call.respondText("Hello by Nexus!\n")
                return@get
            }

            post("/ebics/subscribers") {
                val body = try {
                    call.receive<EbicsSubscriberInfoRequest>()
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NexusError(e.message.toString())
                    )
                    return@post
                }

                val pairA = CryptoUtil.generateRsaKeyPair(2048)
                val pairB = CryptoUtil.generateRsaKeyPair(2048)
                val pairC = CryptoUtil.generateRsaKeyPair(2048)

                val id = transaction {

                    EbicsSubscriberEntity.new {
                        ebicsURL = body.ebicsURL
                        hostID = body.hostID
                        partnerID = body.partnerID
                        userID = body.userID
                        systemID = body.systemID
                        signaturePrivateKey = SerialBlob(pairA.private.encoded)
                        encryptionPrivateKey = SerialBlob(pairB.private.encoded)
                        authenticationPrivateKey = SerialBlob(pairC.private.encoded)

                    }.id.value
                }

                call.respond(
                    HttpStatusCode.OK,
                    EbicsSubscriberInfoResponse(id)
                )
                return@post
            }

            post("/ebics/subscribers/{id}/sendIni") {

                val id = expectId(call.parameters["id"]) // caught above
                val iniRequest = EbicsUnsecuredRequest()

                val url = transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)
                    val tmpKey = CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.toByteArray())

                    iniRequest.apply {
                        version = "H004"
                        revision = 1
                        header = EbicsUnsecuredRequest.Header().apply {
                            authenticate = true
                            static = EbicsUnsecuredRequest.StaticHeaderType().apply {
                                orderDetails = EbicsUnsecuredRequest.OrderDetails().apply {
                                    orderAttribute = "DZNNN"
                                    orderType = "INI"
                                    securityMedium = "0000"
                                    hostID = subscriber.hostID
                                    userID = subscriber.userID
                                    partnerID = subscriber.partnerID
                                    systemID = subscriber.systemID
                                }

                            }
                            mutable = EbicsUnsecuredRequest.Header.EmptyMutableHeader()
                        }
                        body = EbicsUnsecuredRequest.Body().apply {
                            dataTransfer = EbicsUnsecuredRequest.UnsecuredDataTransfer().apply {
                                orderData = EbicsUnsecuredRequest.OrderData().apply {
                                    value = EbicsOrderUtil.encodeOrderDataXml(
                                        SignaturePubKeyOrderData().apply {
                                            signaturePubKeyInfo = SignaturePubKeyInfoType().apply {
                                                signatureVersion = "A006"
                                                pubKeyValue = PubKeyValueType().apply {
                                                    rsaKeyValue = org.apache.xml.security.binding.xmldsig.RSAKeyValueType().apply {
                                                        exponent = tmpKey.publicExponent.toByteArray()
                                                        modulus = tmpKey.modulus.toByteArray()
                                                    }
                                                }
                                            }
                                            userID = subscriber.userID
                                            partnerID = subscriber.partnerID

                                        }
                                    )
                                }
                            }
                        }
                    }
                    subscriber.ebicsURL
                }

                val responseJaxb = client.postToBank<EbicsKeyManagementResponse, EbicsUnsecuredRequest>(
                    url,
                    iniRequest
                ) ?: throw UnreachableBankError(HttpStatusCode.InternalServerError)

                val returnCode = responseJaxb.value.body.returnCode.value
                if (returnCode == "000000") {
                    call.respond(
                        HttpStatusCode.OK,
                        NexusError("Sandbox accepted the key.")
                    )
                    return@post
                } else {

                    call.respond(
                        HttpStatusCode.OK,
                        NexusError("Sandbox did not accept the key.  Error code: ${returnCode}")
                    )
                    return@post
                }
            }
        }
    }

    logger.info("Up and running")
    server.start(wait = true)
}
