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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.request.uri
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.apache.commons.codec.digest.Crypt
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
import tech.libeufin.schema.ebics_s001.UserSignatureData
import java.awt.Container
import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.time.Instant.now
import java.util.*
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream
import javax.crypto.EncryptedPrivateKeyInfo
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
 * Inserts spaces every 2 characters, and a newline after 8 pairs.
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
suspend inline fun HttpClient.postToBank(url: String, body: String): String {

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

    return response
}

/**
 * DO verify the bank's signature
 */
suspend inline fun <reified T, reified S>HttpClient.postToBankSignedAndVerify(
    url: String,
    body: T,
    pub: PublicKey,
    priv: PrivateKey): JAXBElement<S> {

    val doc = XMLUtil.convertJaxbToDocument(body)
    XMLUtil.signEbicsDocument(doc, priv)

    val response: String = this.postToBank(url, XMLUtil.convertDomToString(doc))
    logger.debug("About to verify: ${response}")

    val responseString = try {

        XMLUtil.parseStringIntoDom(response)
    } catch (e: Exception) {

        throw UnparsableResponse(HttpStatusCode.BadRequest, response)
    }

    if (!XMLUtil.verifyEbicsDocument(responseString, pub)) {

        throw BadSignature(HttpStatusCode.NotAcceptable)
    }

    try {

        return XMLUtil.convertStringToJaxb(response)
    } catch (e: Exception) {

        throw UnparsableResponse(HttpStatusCode.BadRequest, response)
    }
}

suspend inline fun <reified T, reified S>HttpClient.postToBankSigned(
    url: String,
    body: T,
    priv: PrivateKey): JAXBElement<S> {

    val doc = XMLUtil.convertJaxbToDocument(body)
    XMLUtil.signEbicsDocument(doc, priv)

    val response: String = this.postToBank(url, XMLUtil.convertDomToString(doc))

    try {
        return XMLUtil.convertStringToJaxb(response)
    } catch (e: Exception) {
        throw UnparsableResponse(HttpStatusCode.BadRequest, response)
    }
}



/**
 * do NOT verify the bank's signature
 */
suspend inline fun <reified T, reified S>HttpClient.postToBankUnsigned(
    url: String,
    body: T
): JAXBElement<S> {

    val response: String = this.postToBank(url, XMLUtil.convertJaxbToString(body))

    try {
        return XMLUtil.convertStringToJaxb(response)
    } catch (e: Exception) {
        throw UnparsableResponse(HttpStatusCode.BadRequest, response)
    }
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
data class BadSignature(val statusCode: HttpStatusCode) : Exception("Signature verification unsuccessful")
data class BadBackup(val statusCode: HttpStatusCode) : Exception("Could not restore backed up keys")



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

            exception<BadBackup> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Bad backup, or passphrase incorrect\n", ContentType.Text.Plain, HttpStatusCode.BadRequest)
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

            exception<BadSignature> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Signature verification unsuccessful\n", ContentType.Text.Plain, HttpStatusCode.NotAcceptable)
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
                val bundle = transaction {
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

                    EbicsContainer(
                        ebicsUrl = subscriber.ebicsURL,
                        customerEncPrivBlob = subscriber.encryptionPrivateKey.toByteArray(),
                        customerAuthPrivBlob = subscriber.authenticationPrivateKey.toByteArray(),
                        jaxb = request,
                        hostId = subscriber.hostID

                    )
                }

                val response = client.postToBankUnsigned<EbicsRequest, EbicsResponse>(bundle.ebicsUrl!!, bundle.jaxb!!)
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

                val dataCompr = CryptoUtil.decryptEbicsE002(er, CryptoUtil.loadRsaPrivateKey(bundle.customerEncPrivBlob!!))
                val data = EbicsOrderUtil.decodeOrderDataXml<HTDResponseOrderData>(dataCompr)


                logger.debug("HTD payload is: ${XMLUtil.convertJaxbToString(data)}")


                val ackRequest = EbicsRequest().apply {
                    header = EbicsRequest.Header().apply {
                        version = "H004"
                        revision = 1
                        authenticate = true
                        static = EbicsRequest.StaticHeaderType().apply {
                            hostID = bundle.hostId!!
                            transactionID = response.value.header._static.transactionID
                        }
                        mutable = EbicsRequest.MutableHeader().apply {
                            transactionPhase = EbicsTypes.TransactionPhaseType.RECEIPT
                        }
                    }
                    authSignature = SignatureType()

                    body = EbicsRequest.Body().apply {
                        transferReceipt = EbicsRequest.TransferReceipt().apply {
                            authenticate = true
                            receiptCode = 0 // always true at this point.
                        }
                    }
                }

                val ackResponse = client.postToBankSignedAndVerify<EbicsRequest, EbicsResponse>(
                    bundle.ebicsUrl,
                    ackRequest,
                    CryptoUtil.loadRsaPublicKey(bundle.bankAuthPubBlob!!),
                    CryptoUtil.loadRsaPrivateKey(bundle.customerAuthPrivBlob!!)
                )

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

                var usernameLine = "TODO"
                var recipientLine = "TODO"
                val customerIdLine = "TODO"

                var dateLine = ""
                var timeLine = ""
                var userIdLine = ""
                var esExponentLine = ""
                var esModulusLine = ""
                var authExponentLine = ""
                var authModulusLine = ""
                var encExponentLine = ""
                var encModulusLine = ""
                var esKeyHashLine = ""
                var encKeyHashLine = ""
                var authKeyHashLine = ""

                val esVersionLine = "A006"
                val authVersionLine = "X002"
                val encVersionLine = "E002"

                val now = Date()
                val dateFormat = SimpleDateFormat("DD.MM.YYYY")
                val timeFormat = SimpleDateFormat("HH.mm.ss")

                dateLine = dateFormat.format(now)
                timeLine = timeFormat.format(now)


                transaction {
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

                    userIdLine = subscriber.userID

                    esExponentLine = signPubTmp.publicExponent.toByteArray().toHexString()
                    esModulusLine = signPubTmp.modulus.toByteArray().toHexString()

                    encExponentLine = encPubTmp.publicExponent.toByteArray().toHexString()
                    encModulusLine = encPubTmp.modulus.toByteArray().toHexString()

                    authExponentLine = authPubTmp.publicExponent.toByteArray().toHexString()
                    authModulusLine = authPubTmp.modulus.toByteArray().toHexString()

                    esKeyHashLine = CryptoUtil.getEbicsPublicKeyHash(signPubTmp).toHexString()
                    encKeyHashLine = CryptoUtil.getEbicsPublicKeyHash(encPubTmp).toHexString()
                    authKeyHashLine = CryptoUtil.getEbicsPublicKeyHash(authPubTmp).toHexString()
                }

                val iniLetter = """
                    |Name: ${usernameLine}
                    |Date: ${dateLine}
                    |Time: ${timeLine}
                    |Recipient: ${recipientLine}
                    |User ID: ${userIdLine}
                    |Customer ID: ${customerIdLine}
                    |ES version: ${esVersionLine}
                    
                    |Public key for the electronic signature:
                    
                    |Exponent:
                    |${chunkString(esExponentLine)}
                    
                    |Modulus:
                    |${chunkString(esModulusLine)}
                    
                    |SHA-256 hash:
                    |${chunkString(esKeyHashLine)}
                    
                    |I hereby confirm the above public keys for my electronic signature.
                    
                    |__________
                    |Place/date
                    
                    |__________
                    |Signature
                """.trimMargin()

                val hiaLetter = """
                    |Name: ${usernameLine}
                    |Date: ${dateLine}
                    |Time: ${timeLine}
                    |Recipient: ${recipientLine}
                    |User ID: ${userIdLine}
                    |Customer ID: ${customerIdLine}
                    |Identification and authentication signature version: ${authVersionLine}
                    |Encryption version: ${encVersionLine}
                    
                    |Public key for the identification and authentication signature:
                    
                    |Exponent:
                    |${chunkString(authExponentLine)}
                    
                    |Modulus:
                    |${chunkString(authModulusLine)}
                    
                    |SHA-256 hash:
                    |${chunkString(authKeyHashLine)}
                    
                    |Public encryption key:
                    
                    |Exponent:
                    |${chunkString(encExponentLine)}
                    
                    |Modulus:
                    |${chunkString(encModulusLine)}
                    
                    |SHA-256 hash:
                    |${chunkString(encKeyHashLine)}              


                    |I hereby confirm the above public keys for my electronic signature.
                    
                    |__________
                    |Place/date
                    
                    |__________
                    |Signature
                """.trimMargin()

                call.respondText(
                    "####INI####:\n${iniLetter}\n\n\n####HIA####:\n${hiaLetter}",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
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

                val responseJaxb = client.postToBankUnsigned<EbicsUnsecuredRequest, EbicsKeyManagementResponse>(
                    url,
                    iniRequest
                )

                if (responseJaxb.value.body.returnCode.value != "000000") {
                    throw EbicsError(responseJaxb.value.body.returnCode.value)
                }

                call.respondText("Bank accepted signature key\n", ContentType.Text.Plain, HttpStatusCode.OK)
                return@post
            }

            post("/ebics/subscribers/{id}/restoreBackup") {

                val body = call.receive<EbicsKeysBackup>()
                val id = expectId(call.parameters["id"])

                val (authKey, encKey, sigKey) = try {

                    Triple(

                        CryptoUtil.decryptKey(
                            EncryptedPrivateKeyInfo(body.authBlob), body.passphrase!!
                        ),

                        CryptoUtil.decryptKey(
                            EncryptedPrivateKeyInfo(body.encBlob), body.passphrase
                        ),

                        CryptoUtil.decryptKey(
                            EncryptedPrivateKeyInfo(body.sigBlob), body.passphrase
                        )
                    )

                } catch (e: Exception) {
                    e.printStackTrace()
                    throw BadBackup(HttpStatusCode.BadRequest)
                }

                transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)

                    subscriber.encryptionPrivateKey = SerialBlob(encKey.encoded)
                    subscriber.authenticationPrivateKey = SerialBlob(authKey.encoded)
                    subscriber.signaturePrivateKey = SerialBlob(sigKey.encoded)
                }

                call.respondText(
                    "Keys successfully restored",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )

            }

            put("/ebics/subscribers/{id}/backup") {

                val id = expectId(call.parameters["id"])
                val body = call.receive<EbicsBackupRequest>()

                val content = transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)


                    EbicsKeysBackup(

                        authBlob = CryptoUtil.encryptKey(
                            subscriber.authenticationPrivateKey.toByteArray(),
                            body.passphrase
                        ),

                        encBlob = CryptoUtil.encryptKey(
                            subscriber.encryptionPrivateKey.toByteArray(),
                            body.passphrase),

                        sigBlob = CryptoUtil.encryptKey(
                            subscriber.signaturePrivateKey.toByteArray(),
                            body.passphrase
                        )
                    )
                }

                call.response.headers.append("Content-Disposition", "attachment")
                call.respond(
                    HttpStatusCode.OK,
                    content
                )

            }
            post("/ebics/subscribers/{id}/sendTst") {

                val id = expectId(call.parameters["id"])

                val innerPayload = "ES-PAYLOAD"


                val container = transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)

                    // first prepare ES content
                    val ES_signature = CryptoUtil.signEbicsA006(
                        CryptoUtil.digestEbicsOrderA006(innerPayload.toByteArray()),
                        CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.toByteArray())
                    )

                    val userSignatureData = UserSignatureData().apply {
                        orderSignatureList = listOf(
                            UserSignatureData.OrderSignatureData().apply {
                                signatureVersion = "A006"
                                signatureValue = ES_signature
                                partnerID = subscriber.partnerID
                                userID = subscriber.userID
                            }
                        )
                    }

                    val usd_compressed = EbicsOrderUtil.encodeOrderDataXml(userSignatureData)
                    val usd_encrypted = CryptoUtil.encryptEbicsE002(
                        usd_compressed,
                        CryptoUtil.loadRsaPublicKey(subscriber.bankEncryptionPublicKey!!.toByteArray()
                        )
                    )

                    val tmp = EbicsRequest().apply {
                        header = EbicsRequest.Header().apply {
                            version = "H004"
                            revision = 1
                            authenticate = true
                            static = EbicsRequest.StaticHeaderType().apply {
                                hostID = subscriber.hostID
                                nonce = getNonce(128)
                                timestamp = getGregorianDate()
                                partnerID = subscriber.partnerID
                                userID = subscriber.userID
                                orderDetails = EbicsRequest.OrderDetails().apply {
                                    orderType = "TST"
                                    orderAttribute = "OZHNN"
                                    orderParams = EbicsRequest.StandardOrderParams()
                                }
                                bankPubKeyDigests = EbicsRequest.BankPubKeyDigests().apply {
                                    authentication = EbicsTypes.PubKeyDigest().apply {
                                        algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                                        version = "X002"
                                        value = CryptoUtil.getEbicsPublicKeyHash(
                                            CryptoUtil.loadRsaPublicKey(subscriber.bankAuthenticationPublicKey!!.toByteArray())
                                        )
                                    }
                                    encryption = EbicsTypes.PubKeyDigest().apply {
                                        algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                                        version = "E002"
                                        value = CryptoUtil.getEbicsPublicKeyHash(
                                            CryptoUtil.loadRsaPublicKey(subscriber.bankEncryptionPublicKey!!.toByteArray())
                                        )
                                    }
                                }
                                securityMedium = "0000"
                                numSegments = BigInteger.ONE
                            }
                            mutable = EbicsRequest.MutableHeader().apply {
                                transactionPhase = EbicsTypes.TransactionPhaseType.INITIALISATION
                            }
                        }
                        authSignature = SignatureType()
                        body = EbicsRequest.Body().apply {
                            dataTransfer = EbicsRequest.DataTransfer().apply {
                                signatureData = EbicsRequest.SignatureData().apply {
                                    authenticate = true
                                    value = usd_encrypted.encryptedData
                                }
                                dataEncryptionInfo = EbicsTypes.DataEncryptionInfo().apply {
                                    transactionKey = usd_encrypted.encryptedTransactionKey
                                    authenticate = true
                                    encryptionPubKeyDigest = EbicsTypes.PubKeyDigest().apply {
                                        algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                                        version = "E002"
                                        value = CryptoUtil.getEbicsPublicKeyHash(
                                            CryptoUtil.loadRsaPublicKey(
                                                subscriber.bankEncryptionPublicKey!!.toByteArray()
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    EbicsContainer(
                        jaxb = tmp,
                        ebicsUrl = subscriber.ebicsURL,
                        bankAuthPubBlob = subscriber.bankAuthenticationPublicKey?.toByteArray() ?: throw BankKeyMissing(
                            HttpStatusCode.NotAcceptable),
                        plainTransactionKey = usd_encrypted.plainTransactionKey,
                        customerAuthPrivBlob = subscriber.authenticationPrivateKey.toByteArray(),
                        bankEncPubBlob = subscriber.bankEncryptionPublicKey?.toByteArray() ?: throw BankKeyMissing(
                            HttpStatusCode.NotAcceptable
                        ),
                        hostId = subscriber.hostID
                    )
                }
                val response = client.postToBankSignedAndVerify<EbicsRequest, EbicsResponse>(
                    container.ebicsUrl!!,
                    container.jaxb!!,
                    CryptoUtil.loadRsaPublicKey(container.bankAuthPubBlob!!),
                    CryptoUtil.loadRsaPrivateKey(container.customerAuthPrivBlob!!)
                )

                if (response.value.body.returnCode.value != "000000") {
                    throw EbicsError(response.value.body.returnCode.value)
                }

                /* now send actual payload */
                val compressedInnerPayload = DeflaterInputStream(
                    innerPayload.toByteArray().inputStream()

                ).use { it.readAllBytes() }

                val encryptedPayload = CryptoUtil.encryptEbicsE002withTransactionKey(
                    compressedInnerPayload,
                    CryptoUtil.loadRsaPublicKey(container.bankEncPubBlob!!),
                    container.plainTransactionKey!!
                )

                val tmp = EbicsRequest().apply {
                    header = EbicsRequest.Header().apply {
                        version = "H004"
                        revision = 1
                        authenticate = true
                        static = EbicsRequest.StaticHeaderType().apply {
                            hostID = container.hostId!!
                            transactionID = response.value.header._static.transactionID
                        }
                        mutable = EbicsRequest.MutableHeader().apply {
                            transactionPhase = EbicsTypes.TransactionPhaseType.TRANSFER
                            segmentNumber = EbicsTypes.SegmentNumber().apply {
                                lastSegment = true
                                value = BigInteger.ONE
                            }
                        }
                    }

                    authSignature = SignatureType()
                    body = EbicsRequest.Body().apply {
                        dataTransfer = EbicsRequest.DataTransfer().apply {
                            orderData = encryptedPayload.encryptedData
                        }
                    }
                }

                val responseTransaction = client.postToBankSignedAndVerify<EbicsRequest, EbicsResponse>(
                    container.ebicsUrl,
                    tmp,
                    CryptoUtil.loadRsaPublicKey(container.bankAuthPubBlob!!),
                    CryptoUtil.loadRsaPrivateKey(container.customerAuthPrivBlob!!)
                )

                if (responseTransaction.value.body.returnCode.value != "000000") {
                    throw EbicsError(response.value.body.returnCode.value)
                }

                call.respondText(
                    "TST INITIALIZATION & TRANSACTION phases succeeded\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
            }


            post("/ebics/subscribers/{id}/sync") {
                val id = expectId(call.parameters["id"])
                val bundle = transaction {
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

                    EbicsContainer<EbicsNpkdRequest>(
                        ebicsUrl = subscriber.ebicsURL,
                        customerEncPrivBlob = subscriber.encryptionPrivateKey.toByteArray(),
                        customerAuthPrivBlob = subscriber.authenticationPrivateKey.toByteArray(),
                        jaxb = hpbRequest

                    )
                }

                val response = client.postToBankSigned<EbicsNpkdRequest, EbicsKeyManagementResponse>(
                    bundle.ebicsUrl!!,
                    bundle.jaxb!!,
                    CryptoUtil.loadRsaPrivateKey(bundle.customerAuthPrivBlob!!)
                )

                if (response.value.body.returnCode.value != "000000") {
                    throw EbicsError(response.value.body.returnCode.value)
                }

                val er = CryptoUtil.EncryptionResult(
                    response.value.body.dataTransfer!!.dataEncryptionInfo!!.transactionKey,
                    (response.value.body.dataTransfer!!.dataEncryptionInfo as EbicsTypes.DataEncryptionInfo)
                        .encryptionPubKeyDigest.value,
                    response.value.body.dataTransfer!!.orderData.value
                )

                val dataCompr = CryptoUtil.decryptEbicsE002(
                    er,
                    CryptoUtil.loadRsaPrivateKey(bundle.customerEncPrivBlob!!))
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

                val bundle = transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)
                    val tmpAiKey = CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray())
                    val tmpEncKey = CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.toByteArray())

                    val hiaRequest = EbicsUnsecuredRequest().apply {
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
                    EbicsContainer<EbicsUnsecuredRequest>(
                        ebicsUrl = subscriber.ebicsURL,
                        jaxb = hiaRequest
                    )
                }

                val responseJaxb = client.postToBankUnsigned<EbicsUnsecuredRequest, EbicsKeyManagementResponse>(
                    bundle.ebicsUrl!!,
                    bundle.jaxb!!
                )

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