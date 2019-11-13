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

import com.ryanharter.ktor.moshi.moshi
import com.squareup.moshi.JsonDataException
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.*
import io.ktor.client.request.post
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
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
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import tech.libeufin.sandbox.*
import tech.libeufin.schema.ebics_h004.*
import java.text.DateFormat
import javax.sql.rowset.serial.SerialBlob
import javax.xml.bind.JAXBElement
import org.w3c.dom.Document
import tech.libeufin.schema.ebics_s001.SignatureTypes
import java.security.SecureRandom
import java.util.*
import java.util.zip.InflaterInputStream
import javax.xml.datatype.DatatypeFactory
import javax.xml.datatype.XMLGregorianCalendar


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

/**
 * Inserts spaces every 2 characters.
 */
fun chunkString(input: String): String {

    val ret = StringBuilder()
    var columns = 0

    for (i in input.indices) {

        if ((i + 1).rem(2) == 0) {

            if (columns == 7) {
                ret.append(input[i] + "\n")
                columns = 0
                continue
            }

            ret.append(input[i] + " ")
            columns++
            continue
        }
        ret.append(input[i])
    }

    return ret.toString()

}

fun expectId(param: String?): Int {

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
suspend inline fun <reified S>HttpClient.postToBank(url: String, body: String): JAXBElement<S> {

    val response = try {
        this.post<String>(
            urlString = url,
            block = {
                this.body = body
            }
        )
    } catch (e: Exception) {
        throw UnreachableBankError(HttpStatusCode.InternalServerError)
    }

    try {
        return XMLUtil.convertStringToJaxb(response)
    } catch (e: Exception) {
        logger.warn("bank responded: ${response}")
        throw UnparsableResponse(HttpStatusCode.BadRequest, response)
    }
}

suspend inline fun <reified T, reified S>HttpClient.postToBank(url: String, body: T): JAXBElement<S> {
    return this.postToBank<S>(url, XMLUtil.convertJaxbToString(body))
}

suspend inline fun <reified S>HttpClient.postToBank(url: String, body: Document): JAXBElement<S> {
    return this.postToBank<S>(url, XMLUtil.convertDomToString(body))
}

/**
 * @param size in bits
 */
fun getNonce(size: Int): ByteArray {
    val sr = SecureRandom()
    val ret = ByteArray(size / 8)
    sr.nextBytes(ret)
    return ret
}

fun getGregorianDate(): XMLGregorianCalendar {
    val gregorianCalendar = GregorianCalendar()
    val datatypeFactory = DatatypeFactory.newInstance()
    return datatypeFactory.newXMLGregorianCalendar(gregorianCalendar)
}

data class NotAnIdError(val statusCode: HttpStatusCode) : Exception("String ID not convertible in number")
data class BankKeyMissing(val statusCode: HttpStatusCode) : Exception("Impossible operation: bank keys are missing")
data class SubscriberNotFoundError(val statusCode: HttpStatusCode) : Exception("Subscriber not found in database")
data class UnreachableBankError(val statusCode: HttpStatusCode) : Exception("Could not reach the bank")
data class UnparsableResponse(val statusCode: HttpStatusCode, val rawResponse: String) : Exception("bank responded: ${rawResponse}")
data class EbicsError(val codeError: String) : Exception("Bank did not accepted EBICS request, error is: ${codeError}")


fun main() {
    dbCreateTables()
    testData() // gets always id == 1
    val client = HttpClient(){
        expectSuccess = false // this way, it does not throw exceptions on != 200 responses.
    }

    val logger = LoggerFactory.getLogger("tech.libeufin.nexus")

    val server = embeddedServer(Netty, port = 5001) {

        install(ContentNegotiation) {

            moshi {
            }

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

            exception<JsonDataException> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Bad request\n", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            }

            exception<NotAnIdError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Bad request\n", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            }

            exception<UnparsableResponse> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Could not parse bank response (${cause.message})\n", ContentType.Text.Plain, HttpStatusCode
                    .InternalServerError)
            }

            exception<UnreachableBankError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Could not reach the bank\n", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
            }

            exception<SubscriberNotFoundError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Subscriber not found\n", ContentType.Text.Plain, HttpStatusCode.NotFound)
            }

            exception<EbicsError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Bank gave EBICS-error response\n", ContentType.Text.Plain, HttpStatusCode.NotAcceptable)
            }

            exception<BankKeyMissing> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Impossible operation: get bank keys first\n", ContentType.Text.Plain, HttpStatusCode.NotAcceptable)
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

            get("/ebics/subscribers/{id}/sendHtd") {
                val id = expectId(call.parameters["id"])
                val (url, requestDoc, encPrivBlob) = transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)
                    val request = EbicsRequest().apply {
                        version = "H004"
                        revision = 1
                        header = EbicsRequest.Header().apply {
                            authenticate = true
                            static = EbicsRequest.StaticHeaderType().apply {
                                userID = subscriber.userID
                                partnerID = subscriber.partnerID
                                hostID = subscriber.hostID
                                nonce = getNonce(128)
                                timestamp = getGregorianDate()
                                partnerID = subscriber.partnerID
                                orderDetails = EbicsRequest.OrderDetails().apply {
                                    orderType = "HTD"
                                    orderAttribute = "DZHNN"
                                    orderParams = EbicsRequest.StandardOrderParams()
                                }
                                bankPubKeyDigests = EbicsRequest.BankPubKeyDigests().apply {
                                    authentication = EbicsTypes.PubKeyDigest().apply {
                                        algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                                        version = "X002"
                                        value = CryptoUtil.getEbicsPublicKeyHash(
                                            CryptoUtil.loadRsaPublicKey(
                                                (subscriber.bankAuthenticationPublicKey ?: throw BankKeyMissing(HttpStatusCode.NotAcceptable)).toByteArray()
                                            )
                                        )
                                    }
                                    encryption = EbicsTypes.PubKeyDigest().apply {
                                        algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                                        version = "E002"
                                        value = CryptoUtil.getEbicsPublicKeyHash(
                                            CryptoUtil.loadRsaPublicKey(
                                                (subscriber.bankEncryptionPublicKey ?: throw BankKeyMissing(HttpStatusCode.NotAcceptable)).toByteArray()
                                            )
                                        )
                                    }
                                    securityMedium = "0000"
                                }
                                mutable = EbicsRequest.MutableHeader().apply {
                                    transactionPhase = EbicsTypes.TransactionPhaseType.INITIALISATION
                                }
                                authSignature = SignatureType()
                            }
                        }
                        body = EbicsRequest.Body()
                    }

                    val hpbText = XMLUtil.convertJaxbToString(request)
                    val hpbDoc = XMLUtil.parseStringIntoDom(hpbText)

                    XMLUtil.signEbicsDocument(
                        hpbDoc,
                        CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray())
                        )

                    Triple(subscriber.ebicsURL, hpbDoc, subscriber.encryptionPrivateKey.toByteArray())
                }

                val response = client.postToBank<EbicsResponse>(url, requestDoc)
                logger.debug("HTD response: " + XMLUtil.convertJaxbToString<EbicsResponse>(response.value))

                if (response.value.body.returnCode.value != "000000") {
                    throw EbicsError(response.value.body.returnCode.value)
                }

                // extract payload

                val er = CryptoUtil.EncryptionResult(
                    response.value.body.dataTransfer!!.dataEncryptionInfo!!.transactionKey,
                    (response.value.body.dataTransfer!!.dataEncryptionInfo as EbicsTypes.DataEncryptionInfo)
                        .encryptionPubKeyDigest.value,
                    Base64.getDecoder().decode(response.value.body.dataTransfer!!.orderData.value)
                )

                val dataCompr = CryptoUtil.decryptEbicsE002(er, CryptoUtil.loadRsaPrivateKey(encPrivBlob))
                val data = EbicsOrderUtil.decodeOrderDataXml<HTDResponseOrderData>(dataCompr)
                logger.debug("HTD payload is: ${XMLUtil.convertJaxbToString(data)}")

                val ackRequestDoc = transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)

                    val ackRequest = EbicsRequest().apply {
                        header = EbicsRequest.Header().apply {
                            version = "H004"
                            revision = 1
                            authenticate = true
                            static = EbicsRequest.StaticHeaderType().apply {
                                hostID = subscriber.hostID
                                transactionID = response.value.header._static.transactionID
                            }
                            mutable = EbicsRequest.MutableHeader().apply {
                                transactionPhase = EbicsTypes.TransactionPhaseType.RECEIPT
                            }
                            authSignature = SignatureType()
                        }
                        body = EbicsRequest.Body().apply {
                            transferReceipt = EbicsRequest.TransferReceipt().apply {
                                authenticate = true
                                receiptCode = 0 // always true at this point.
                            }
                        }
                    }

                    val ackRequestDoc = XMLUtil.convertJaxbToDocument(ackRequest)
                    XMLUtil.signEbicsDocument(
                        ackRequestDoc,
                        CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray())
                    )

                    ackRequestDoc
                }

                val ackResponse = client.postToBank<EbicsResponse>(url, ackRequestDoc)
                logger.debug("HTD final response: " + XMLUtil.convertJaxbToString<EbicsResponse>(response.value))

                if (ackResponse.value.body.returnCode.value != "000000") {
                    throw EbicsError(response.value.body.returnCode.value)
                }

                call.respondText(
                    "Success! Details (temporarily) reported on the Nexus console.",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
            }

            get("/ebics/subscribers/{id}/keyletter") {

                val id = expectId(call.parameters["id"])
                val (signPub, authPub, encPub) = transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)

                    val signPubTmp = CryptoUtil.getRsaPublicFromPrivate(
                        CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.toByteArray())
                    )
                    val authPubTmp = CryptoUtil.getRsaPublicFromPrivate(
                        CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray())
                    )
                    val encPubTmp = CryptoUtil.getRsaPublicFromPrivate(
                        CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.toByteArray())
                    )

                    Triple(signPubTmp, authPubTmp, encPubTmp)
                }

                call.respondText(
                    "Not implemented",
                    ContentType.Text.Plain,
                    HttpStatusCode.NotImplemented
                )
            }

            get("/ebics/subscribers") {

                val ebicsSubscribers = transaction {
                    EbicsSubscriberEntity.all().map {
                        EbicsSubscriberInfoResponse(
                            accountID = it.id.value,
                            hostID = it.hostID,
                            partnerID = it.partnerID,
                            systemID = it.systemID,
                            ebicsURL = it.ebicsURL,
                            userID = it.userID
                        )
                    }
                }
                call.respond(EbicsSubscribersResponse(ebicsSubscribers))
            }

            get("/ebics/subscribers/{id}") {
                val id = expectId(call.parameters["id"])
                val response = transaction {
                    val tmp = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)
                    EbicsSubscriberInfoResponse(
                        accountID = tmp.id.value,
                        hostID = tmp.hostID,
                        partnerID = tmp.partnerID,
                        systemID = tmp.systemID,
                        ebicsURL = tmp.ebicsURL,
                        userID = tmp.userID
                    )
                }
                call.respond(HttpStatusCode.OK, response)
                return@get
            }

            post("/ebics/subscribers") {

                val body = call.receive<EbicsSubscriberInfoRequest>()

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

                call.respondText(
                    "Subscriber registered, ID: ${id}",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
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
                                        SignatureTypes.SignaturePubKeyOrderData().apply {
                                            signaturePubKeyInfo = SignatureTypes.SignaturePubKeyInfoType().apply {
                                                signatureVersion = "A006"
                                                pubKeyValue = SignatureTypes.PubKeyValueType().apply {
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

                val responseJaxb = client.postToBank<EbicsUnsecuredRequest, EbicsKeyManagementResponse>(
                    url,
                    iniRequest
                ) ?: throw UnreachableBankError(HttpStatusCode.InternalServerError)

                if (responseJaxb.value.body.returnCode.value != "000000") {
                    throw EbicsError(responseJaxb.value.body.returnCode.value)
                }

                call.respondText("Bank accepted signature key\n", ContentType.Text.Plain, HttpStatusCode.OK)
                return@post
            }

            post("/ebics/subscribers/{id}/sync") {
                val id = expectId(call.parameters["id"])
                val (url, body, encPrivBlob) = transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)
                    val hpbRequest = EbicsNpkdRequest().apply {
                        version = "H004"
                        revision = 1
                        header = EbicsNpkdRequest.Header().apply {
                            authenticate = true
                            mutable = EbicsNpkdRequest.EmptyMutableHeader()
                            static = EbicsNpkdRequest.StaticHeaderType().apply {
                                hostID = subscriber.hostID
                                partnerID = subscriber.partnerID
                                userID = subscriber.userID
                                securityMedium = "0000"
                                orderDetails = EbicsNpkdRequest.OrderDetails()
                                orderDetails.orderType = "HPB"
                                orderDetails.orderAttribute = "DZHNN"
                                nonce = getNonce(128)
                                timestamp = getGregorianDate()
                            }
                        }
                        body = EbicsNpkdRequest.EmptyBody()
                        authSignature = SignatureType()
                    }
                    val hpbText = XMLUtil.convertJaxbToString(hpbRequest)
                    val hpbDoc = XMLUtil.parseStringIntoDom(hpbText)
                    XMLUtil.signEbicsDocument(
                        hpbDoc,
                        CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray())
                    )
                    Triple(subscriber.ebicsURL, hpbDoc, subscriber.encryptionPrivateKey.toByteArray())
                }

                val response = client.postToBank<EbicsKeyManagementResponse>(url, body)

                if (response.value.body.returnCode.value != "000000") {
                    throw EbicsError(response.value.body.returnCode.value)
                }

                val er = CryptoUtil.EncryptionResult(
                    response.value.body.dataTransfer!!.dataEncryptionInfo!!.transactionKey,
                    (response.value.body.dataTransfer!!.dataEncryptionInfo as EbicsTypes.DataEncryptionInfo)
                        .encryptionPubKeyDigest.value,
                    response.value.body.dataTransfer!!.orderData.value
                )

                val dataCompr = CryptoUtil.decryptEbicsE002(er, CryptoUtil.loadRsaPrivateKey(encPrivBlob))
                val data = EbicsOrderUtil.decodeOrderDataXml<HPBResponseOrderData>(dataCompr)

                val bankAuthPubBlob = CryptoUtil.loadRsaPublicKeyFromComponents(
                    data.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.modulus,
                    data.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.exponent
                )

                val bankEncPubBlob = CryptoUtil.loadRsaPublicKeyFromComponents(
                    data.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue.modulus,
                    data.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue.exponent
                )

                // put bank's keys into database.
                transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id)
                    subscriber!!.bankAuthenticationPublicKey = SerialBlob(bankAuthPubBlob.encoded)
                    subscriber!!.bankEncryptionPublicKey = SerialBlob(bankEncPubBlob.encoded)
                }

                call.respondText("Bank keys stored in database\n", ContentType.Text.Plain, HttpStatusCode.OK)
                return@post
            }

            post("/ebics/subscribers/{id}/sendHia") {

                val id = expectId(call.parameters["id"]) // caught above
                val hiaRequest = EbicsUnsecuredRequest()

                val url = transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)
                    val tmpAiKey = CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray())
                    val tmpEncKey = CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.toByteArray())

                    hiaRequest.apply {
                        version = "H004"
                        revision = 1
                        header = EbicsUnsecuredRequest.Header().apply {
                            authenticate = true
                            static = EbicsUnsecuredRequest.StaticHeaderType().apply {
                                orderDetails = EbicsUnsecuredRequest.OrderDetails().apply {
                                    orderAttribute = "DZNNN"
                                    orderType = "HIA"
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
                                        HIARequestOrderData().apply {
                                            authenticationPubKeyInfo = EbicsTypes.AuthenticationPubKeyInfoType().apply {
                                                pubKeyValue = EbicsTypes.PubKeyValueType().apply {
                                                    rsaKeyValue = RSAKeyValueType().apply {
                                                        exponent = tmpAiKey.publicExponent.toByteArray()
                                                        modulus = tmpAiKey.modulus.toByteArray()
                                                    }
                                                }
                                                authenticationVersion = "X002"
                                            }
                                            encryptionPubKeyInfo = EbicsTypes.EncryptionPubKeyInfoType().apply {
                                                pubKeyValue = EbicsTypes.PubKeyValueType().apply {
                                                    rsaKeyValue = RSAKeyValueType().apply {
                                                        exponent = tmpEncKey.publicExponent.toByteArray()
                                                        modulus = tmpEncKey.modulus.toByteArray()
                                                    }
                                                }
                                                encryptionVersion = "E002"

                                            }
                                            partnerID = subscriber.partnerID
                                            userID = subscriber.userID
                                        }
                                    )
                                }
                            }
                        }
                    }
                    subscriber.ebicsURL
                }

                val responseJaxb = client.postToBank<EbicsUnsecuredRequest, EbicsKeyManagementResponse>(
                    url,
                    hiaRequest
                ) ?: throw UnreachableBankError(HttpStatusCode.InternalServerError)

                if (responseJaxb.value.body.returnCode.value != "000000") {
                    throw EbicsError(responseJaxb.value.body.returnCode.value)
                }

                call.respondText(
                    "Bank accepted authentication and encryption keys\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK)

                return@post
            }
        }
    }

    logger.info("Up and running")
    server.start(wait = true)
}
