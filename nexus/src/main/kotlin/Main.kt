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
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.request.uri
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.util.ebics_h004.*
import tech.libeufin.util.*
import java.text.DateFormat
import javax.sql.rowset.serial.SerialBlob
import tech.libeufin.util.toHexString
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.EbicsOrderUtil
import tech.libeufin.util.XMLUtil
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.DeflaterInputStream
import javax.crypto.EncryptedPrivateKeyInfo


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

data class NotAnIdError(val statusCode: HttpStatusCode) : Exception("String ID not convertible in number")
data class BankKeyMissing(val statusCode: HttpStatusCode) : Exception("Impossible operation: bank keys are missing")
data class SubscriberNotFoundError(val statusCode: HttpStatusCode) : Exception("Subscriber not found in database")
data class UnreachableBankError(val statusCode: HttpStatusCode) : Exception("Could not reach the bank")
data class UnparsableResponse(val statusCode: HttpStatusCode, val rawResponse: String) : Exception("bank responded: ${rawResponse}")
data class EbicsError(val codeError: String) : Exception("Bank did not accepted EBICS request, error is: ${codeError}")
data class BadSignature(val statusCode: HttpStatusCode) : Exception("Signature verification unsuccessful")
data class BadBackup(val statusCode: HttpStatusCode) : Exception("Could not restore backed up keys")
data class BankInvalidResponse(val statusCode: HttpStatusCode) : Exception("Missing data from bank response")

val LOGGER: Logger = LoggerFactory.getLogger("tech.libeufin.nexus")

fun main() {
    dbCreateTables()
    testData() // gets always id == 1
    val client = HttpClient(){
        expectSuccess = false // this way, it does not throw exceptions on != 200 responses.
    }

    val logger = LoggerFactory.getLogger("tech.libeufin.nexus")

    val server = embeddedServer(Netty, port = 5001) {

        install(CallLogging) {
            this.level = Level.DEBUG
            this.logger = LOGGER

        }

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

            post("/ebics/subscribers/{id}/sendC52") {
                val id = expectId(call.parameters["id"])
                val body = call.receive<EbicsDateRange>()

                val startDate = DateTime.parse(body.start)
                val endDate = DateTime.parse(body.end)
                // will throw DateTimeParseException if strings are malformed.

                val subscriberData = transaction {
                    containerInit(EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound))
                }

                val response = client.postToBankSigned<EbicsRequest, EbicsResponse>(
                    subscriberData.ebicsUrl,
                    createDownloadInitializationPhase(
                        subscriberData,
                        "C52",
                        getNonce(128),
                        getGregorianDate(),
                        getGregorianDate(startDate.year, startDate.monthOfYear, startDate.dayOfMonth),
                        getGregorianDate(endDate.year, endDate.monthOfYear, endDate.dayOfMonth)
                    ),
                    subscriberData.customerAuthPriv
                )

                val payload: ByteArray = decryptAndDecompressResponse(response.value, subscriberData.customerEncPriv)

                call.respondText(
                    payload.toString(Charsets.UTF_8),
                    ContentType.Text.Plain,
                    HttpStatusCode.OK)

                return@post
            }

            get("/ebics/subscribers/{id}/sendHtd") {
                val id = expectId(call.parameters["id"])
                val subscriberData = transaction {
                    containerInit(EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound))
                }

                val response = client.postToBankSigned<EbicsRequest, EbicsResponse>(
                    subscriberData.ebicsUrl,
                    createDownloadInitializationPhase(
                        subscriberData,
                        "HTD",
                        getNonce(128),
                        getGregorianDate()
                    ),
                    subscriberData.customerAuthPriv
                )

                logger.debug("HTD response: " + XMLUtil.convertJaxbToString<EbicsResponse>(response.value))

                if (response.value.body.returnCode.value != "000000") {
                    throw EbicsError(response.value.body.returnCode.value)
                }

                val er = CryptoUtil.EncryptionResult(
                    response.value.body.dataTransfer!!.dataEncryptionInfo!!.transactionKey,
                    (response.value.body.dataTransfer!!.dataEncryptionInfo as EbicsTypes.DataEncryptionInfo)
                        .encryptionPubKeyDigest.value,
                    Base64.getDecoder().decode(response.value.body.dataTransfer!!.orderData.value)
                )

                val dataCompr = CryptoUtil.decryptEbicsE002(
                    er,
                    subscriberData.customerEncPriv
                )
                val data = EbicsOrderUtil.decodeOrderDataXml<HTDResponseOrderData>(dataCompr)


                logger.debug("HTD payload is: ${XMLUtil.convertJaxbToString(data)}")

                val ackRequest = EbicsRequest.createForDownloadReceiptPhase(
                    response.value.header._static.transactionID ?: throw BankInvalidResponse(HttpStatusCode.ExpectationFailed),
                    subscriberData.hostId
                )

                val ackResponse = client.postToBankSignedAndVerify<EbicsRequest, EbicsResponse>(
                    subscriberData.ebicsUrl,
                    ackRequest,
                    subscriberData.bankAuthPub ?: throw BankKeyMissing(HttpStatusCode.PreconditionFailed),
                    subscriberData.customerAuthPriv
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
                var dateLine = dateFormat.format(now)
                var timeLine = timeFormat.format(now)





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
                val subscriberData = transaction {
                    containerInit(
                        EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)
                    )
                }

                val iniRequest = EbicsUnsecuredRequest.createIni(
                    subscriberData.hostId,
                    subscriberData.userId,
                    subscriberData.partnerId,
                    subscriberData.customerSignPriv
                )

                val responseJaxb = client.postToBankUnsigned<EbicsUnsecuredRequest, EbicsKeyManagementResponse>(
                    subscriberData.ebicsUrl,
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

            post("/ebics/subscribers/{id}/backup") {

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

                val subscriberData = transaction {
                    containerInit(
                        EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)
                    )
                }
                val payload = "PAYLOAD"

                if (subscriberData.bankEncPub == null) {
                    call.respondText(
                        "Bank encryption key not found, request HPB first!\n",
                        ContentType.Text.Plain,
                        HttpStatusCode.NotFound
                    )
                    return@post
                }


                val usd_encrypted = CryptoUtil.encryptEbicsE002(
                    EbicsOrderUtil.encodeOrderDataXml(

                        signOrder(
                            payload.toByteArray(),
                            subscriberData.customerSignPriv,
                            subscriberData.partnerId,
                            subscriberData.userId
                        )
                    ),
                    subscriberData.bankEncPub!!
                )

                val response = client.postToBankSignedAndVerify<EbicsRequest, EbicsResponse>(
                    subscriberData.ebicsUrl,
                    createUploadInitializationPhase(
                        subscriberData,
                        "TST",
                        usd_encrypted
                    ),
                    subscriberData.bankAuthPub!!,
                    subscriberData.customerEncPriv
                )

                if (response.value.body.returnCode.value != "000000") {
                    throw EbicsError(response.value.body.returnCode.value)
                }

                logger.debug("INIT phase passed!")

                /* now send actual payload */
                val compressedInnerPayload = DeflaterInputStream(
                    payload.toByteArray().inputStream()

                ).use { it.readAllBytes() }

                val encryptedPayload = CryptoUtil.encryptEbicsE002withTransactionKey(
                    compressedInnerPayload,
                    subscriberData.bankEncPub!!,
                    usd_encrypted.plainTransactionKey!!
                )

                val tmp = EbicsRequest.createForUploadTransferPhase(
                    subscriberData.hostId,
                    response.value.header._static.transactionID!!,
                    BigInteger.ONE,
                    encryptedPayload.encryptedData
                )

                val responseTransaction = client.postToBankSignedAndVerify<EbicsRequest, EbicsResponse>(
                    subscriberData.ebicsUrl,
                    tmp,
                    subscriberData.bankAuthPub!!,
                    subscriberData.customerAuthPriv
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
                    containerInit(
                        EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)
                    )
                }
                val response = client.postToBankSigned<EbicsNpkdRequest, EbicsKeyManagementResponse>(
                    bundle.ebicsUrl,
                    EbicsNpkdRequest.createRequest(
                        bundle.hostId,
                        bundle.partnerId,
                        bundle.userId,
                        getNonce(128),
                        getGregorianDate()
                    ),
                    bundle.customerAuthPriv
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
                    bundle.customerEncPriv
                )
                val data = EbicsOrderUtil.decodeOrderDataXml<HPBResponseOrderData>(dataCompr)

                // put bank's keys into database.
                transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id)

                    subscriber!!.bankAuthenticationPublicKey = SerialBlob(

                        CryptoUtil.loadRsaPublicKeyFromComponents(
                            data.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.modulus,
                            data.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.exponent
                        ).encoded
                    )

                    subscriber.bankEncryptionPublicKey = SerialBlob(
                        CryptoUtil.loadRsaPublicKeyFromComponents(
                            data.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue.modulus,
                            data.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue.exponent
                        ).encoded
                    )
                }

                call.respondText("Bank keys stored in database\n", ContentType.Text.Plain, HttpStatusCode.OK)
                return@post
            }

            post("/ebics/subscribers/{id}/sendHia") {

                val id = expectId(call.parameters["id"])

                val subscriberData = transaction {
                    containerInit(
                        EbicsSubscriberEntity.findById(id) ?: throw SubscriberNotFoundError(HttpStatusCode.NotFound)
                    )
                }

                val responseJaxb = client.postToBankUnsigned<EbicsUnsecuredRequest, EbicsKeyManagementResponse>(
                    subscriberData.ebicsUrl,
                    EbicsUnsecuredRequest.createHia(
                        subscriberData.hostId,
                        subscriberData.userId,
                        subscriberData.partnerId,
                        subscriberData.customerAuthPriv,
                        subscriberData.customerEncPriv
                    )
                )

                if (responseJaxb.value.body.returnCode.value != "000000") {
                    throw EbicsError(responseJaxb.value.body.returnCode.value)
                }

                call.respondText(
                    "Bank accepted authentication and encryption keys\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )

                return@post
            }
        }
    }

    logger.info("Up and running")
    server.start(wait = true)
}
