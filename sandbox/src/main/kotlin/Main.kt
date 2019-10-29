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
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.request.receiveText
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import tech.libeufin.sandbox.db.*
import tech.libeufin.schema.ebics_h004.*
import tech.libeufin.schema.ebics_hev.HEVResponse
import tech.libeufin.schema.ebics_hev.SystemReturnCodeType
import tech.libeufin.schema.ebics_s001.SignaturePubKeyOrderData
import java.math.BigInteger
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.RSAPrivateKeySpec
import java.security.spec.RSAPublicKeySpec
import java.text.DateFormat
import java.util.*
import java.util.zip.InflaterInputStream

val logger = LoggerFactory.getLogger("tech.libeufin.sandbox")
val xmlProcess = XMLUtil()
val getEbicsHostId = { "LIBEUFIN-SANDBOX" }
val getEbicsVersion = { "H004" }
val getEbicsRevision = { 1 }


/**
 * Instantiate a new RSA public key.
 *
 * @param exponent
 * @param modulus
 * @return key
 */
fun loadRsaPublicKey(modulus: ByteArray, exponent: ByteArray): PublicKey {

    val modulusBigInt = BigInteger(1, modulus)
    val exponentBigInt = BigInteger(1, exponent)

    val keyFactory = KeyFactory.getInstance("RSA")
    val tmp = RSAPublicKeySpec(modulusBigInt, exponentBigInt)
    return keyFactory.generatePublic(tmp)
}

/**
 * The function tries to get the bank private key from the database.
 * If it does not find it, it generates a new one and stores it in
 * database.
 *
 * @return the key (whether from database or freshly created)
 */
fun getOrMakePrivateKey(): PrivateKey {

    // bank has always one private key in database.
    var tmp = transaction {
        EbicsBankPrivateKey.findById(1)
    }

    // must generate one now
    if (tmp == null) {

        val privateExponent =
            BigInteger(PRIVATE_KEY_EXPONENT_LENGTH, Random()) // shall be set to some well-known value?
        val privateModulus = BigInteger(PRIVATE_KEY_MODULUS_LENGTH, Random())

        tmp = transaction {
            EbicsBankPrivateKey.new {
                modulus = privateModulus.toByteArray()
                exponent = privateExponent.toByteArray()
            }
        }
    }

    val keySpec = RSAPrivateKeySpec(
        BigInteger(tmp.modulus),
        BigInteger(tmp.exponent)
    )

    val factory = KeyFactory.getInstance("RSA")
    val privateKey = factory.generatePrivate(keySpec)

    return privateKey
}


private suspend fun ApplicationCall.adminCustomers() {
    val body = try {
        receive<CustomerRequest>()
    } catch (e: Exception) {
        e.printStackTrace()
        respond(
            HttpStatusCode.BadRequest,
            SandboxError(e.message.toString())
        )
        return
    }
    logger.info(body.toString())

    val returnId = transaction {
        val myUserId = EbicsUser.new { }
        val myPartnerId = EbicsPartner.new { }
        val mySystemId = EbicsSystem.new { }
        val subscriber = EbicsSubscriber.new {
            userId = myUserId
            partnerId = myPartnerId
            systemId = mySystemId
            state = SubscriberStates.NEW
        }
        println("subscriber ID: ${subscriber.id.value}")
        val customer = BankCustomer.new {
            name = body.name
            ebicsSubscriber = subscriber
        }
        println("name: ${customer.name}")
        return@transaction customer.id.value
    }

    respond(
        HttpStatusCode.OK,
        CustomerResponse(id = returnId)
    )
}

private suspend fun ApplicationCall.adminCustomersInfo() {
    val id: Int = try {
        parameters["id"]!!.toInt()
    } catch (e: NumberFormatException) {
        respond(
            HttpStatusCode.BadRequest,
            SandboxError(e.message.toString())
        )
        return
    }

    val customerInfo = transaction {
        val customer = BankCustomer.findById(id) ?: return@transaction null
        CustomerInfo(
            customer.name,
            ebicsInfo = CustomerEbicsInfo(
                customer.ebicsSubscriber.userId.userId!!
            )
        )
    }

    if (null == customerInfo) {
        respond(
            HttpStatusCode.NotFound,
            SandboxError("id $id not found")
        )
        return
    }

    respond(HttpStatusCode.OK, customerInfo)
}

private suspend fun ApplicationCall.adminCustomersKeyletter() {
    val body = try {
        receive<IniHiaLetters>()
    } catch (e: Exception) {
        e.printStackTrace()
        respond(
            HttpStatusCode.BadRequest,
            SandboxError(e.message.toString())
        )
        return
    }

    val ebicsUserID = transaction {
        EbicsUser.find { EbicsUsers.userId eq body.ini.userId }.firstOrNull()
    }

    if (ebicsUserID == null) {
        respond(
            HttpStatusCode.NotFound,
            SandboxError("User ID not found")
        )
        return
    }

    val ebicsSubscriber = EbicsSubscriber.find {
        EbicsSubscribers.userId eq EntityID(ebicsUserID.id.value, EbicsUsers)
    }.firstOrNull()

    if (ebicsSubscriber == null) {
        respond(
            HttpStatusCode.InternalServerError,
            SandboxError("Bank had internal errors retrieving the Subscriber")
        )
        return
    }

    // check signature key
    var modulusFromDd = BigInteger(ebicsSubscriber.signatureKey?.modulus)
    var exponentFromDb = BigInteger(ebicsSubscriber.signatureKey?.exponent)
    var modulusFromLetter = body.ini.public_modulus.toBigInteger(16)
    var exponentFromLetter = body.ini.public_modulus.toBigInteger(16)

    if (!((modulusFromDd == modulusFromLetter) && (exponentFromDb == exponentFromLetter))) {
        logger.info("Signature key mismatches for ${ebicsUserID.userId}")
        respond(
            HttpStatusCode.NotAcceptable,
            SandboxError("Signature Key mismatches!")
        )
        return
    }

    logger.info("Signature key from user ${ebicsUserID.userId} becomes RELEASED")
    ebicsSubscriber.signatureKey?.state = KeyStates.RELEASED

    // check identification and authentication key
    modulusFromDd = BigInteger(ebicsSubscriber.authenticationKey?.modulus)
    exponentFromDb = BigInteger(ebicsSubscriber.authenticationKey?.exponent)
    modulusFromLetter = body.hia.ia_public_modulus.toBigInteger(16)
    exponentFromLetter = body.hia.ia_public_exponent.toBigInteger(16)

    if (!((modulusFromDd == modulusFromLetter) && (exponentFromDb == exponentFromLetter))) {
        logger.info("Identification and authorization key mismatches for ${ebicsUserID.userId}")
        respond(
            HttpStatusCode.NotAcceptable,
            SandboxError("Identification and authorization key mismatches!")
        )
        return
    }

    logger.info("Authentication key from user ${ebicsUserID.userId} becomes RELEASED")
    ebicsSubscriber.authenticationKey?.state = KeyStates.RELEASED

    // check encryption key
    modulusFromDd = BigInteger(ebicsSubscriber.encryptionKey?.modulus)
    exponentFromDb = BigInteger(ebicsSubscriber.encryptionKey?.exponent)
    modulusFromLetter = body.hia.enc_public_modulus.toBigInteger(16)
    exponentFromLetter = body.hia.enc_public_exponent.toBigInteger(16)

    if (!((modulusFromDd == modulusFromLetter) && (exponentFromDb == exponentFromLetter))) {
        logger.info("Encryption key mismatches for ${ebicsUserID.userId}")
        respond(
            HttpStatusCode.NotAcceptable,
            SandboxError("Encryption key mismatches!")
        )
        return
    }

    logger.info("Encryption key from user ${ebicsUserID.userId} becomes RELEASED")
    ebicsSubscriber.encryptionKey?.state = KeyStates.RELEASED


    // TODO change subscriber status!
    ebicsSubscriber.state = SubscriberStates.READY

    respond(
        HttpStatusCode.OK,
        "Your status has changed to READY"
    )
}

private suspend fun ApplicationCall.respondEbicsKeyManagement(
    errorText: String,
    errorCode: String,
    statusCode: HttpStatusCode
) {
    val responseXml = EbicsResponse().apply {
        header = EbicsResponse.Header().apply {
            mutable = ResponseMutableHeaderType().apply {
                reportText = errorText
                returnCode = errorCode
            }
        }
    }
    val text = XMLUtil.convertJaxbToString(responseXml)
    respondText(text, ContentType.Application.Xml, statusCode)
}

private suspend fun ApplicationCall.respondEbicsInvalidXml() {
    respondEbicsKeyManagement("[EBICS_INVALID_XML]", "091010", HttpStatusCode.BadRequest)
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

            if (bodyJaxb.value.header.static.hostID != getEbicsHostId()) {
                respondEbicsKeyManagement("[EBICS_INVALID_HOST_ID]", "091011", HttpStatusCode.NotFound)
                return
            }

            val ebicsUserID = transaction {
                EbicsUser.find { EbicsUsers.userId eq bodyJaxb.value.header.static.userID }.firstOrNull()
            }

            if (ebicsUserID == null) {
                respondEbicsKeyManagement("[EBICS_UNKNOWN_USER]", "091003", HttpStatusCode.NotFound)
                return
            }

            val ebicsSubscriber = transaction {
                EbicsSubscriber.find {
                    EbicsSubscribers.userId eq EntityID(ebicsUserID.id.value, EbicsUsers)
                }.firstOrNull()
            }

            if (ebicsSubscriber == null) {
                respondEbicsKeyManagement("[EBICS_INTERNAL_ERROR]", "061099", HttpStatusCode.InternalServerError)
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

                    try {
                        loadRsaPublicKey(
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
                        ebicsSubscriber.signatureKey = EbicsPublicKey.new {
                            modulus = keyObject.value.signaturePubKeyInfo.pubKeyValue.rsaKeyValue.modulus
                            exponent = keyObject.value.signaturePubKeyInfo.pubKeyValue.rsaKeyValue.exponent
                            state = KeyStates.NEW
                        }

                        if (ebicsSubscriber.state == SubscriberStates.NEW) {
                            ebicsSubscriber.state = SubscriberStates.PARTIALLY_INITIALIZED_INI
                        }

                        if (ebicsSubscriber.state == SubscriberStates.PARTIALLY_INITIALIZED_HIA) {
                            ebicsSubscriber.state = SubscriberStates.INITIALIZED
                        }
                    }

                    logger.info("Signature key inserted in database _and_ subscriber state changed accordingly")
                }

                "HIA" -> {
                    val keyObject = XMLUtil.convertStringToJaxb<HIARequestOrderDataType>(payload.toString(US_ASCII))

                    try {
                        loadRsaPublicKey(
                            keyObject.value.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.modulus,
                            keyObject.value.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.exponent
                        )
                        loadRsaPublicKey(
                            keyObject.value.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue.modulus,
                            keyObject.value.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue.exponent
                        )
                    } catch (e: Exception) {
                        logger.info("User gave at least one invalid HIA key")
                        e.printStackTrace()
                        respondEbicsInvalidXml()
                        return
                    }

                    // put try-catch block here? (FIXME)
                    transaction {
                        ebicsSubscriber.authenticationKey = EbicsPublicKey.new {
                            modulus = keyObject.value.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.modulus
                            exponent = keyObject.value.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.exponent
                            state = KeyStates.NEW
                        }

                        if (ebicsSubscriber.state == SubscriberStates.NEW) {
                            ebicsSubscriber.state = SubscriberStates.PARTIALLY_INITIALIZED_HIA
                        }

                        if (ebicsSubscriber.state == SubscriberStates.PARTIALLY_INITIALIZED_INI) {
                            ebicsSubscriber.state = SubscriberStates.INITIALIZED
                        }
                    }
                }
            }

            respondEbicsKeyManagement("[EBICS_OK]", "000000", HttpStatusCode.OK)
            return
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
    val server = embeddedServer(Netty, port = 5000) {

        install(CallLogging)
        install(ContentNegotiation) {
            gson {
                setDateFormat(DateFormat.LONG)
                setPrettyPrinting()
            }
        }
        routing {
            get("/") {
                logger.debug("GET: not implemented")
                call.respondText("Hello LibEuFin!\n", ContentType.Text.Plain)
                return@get
            }

            post("/admin/customers") {
                call.adminCustomers()
                return@post
            }

            get("/admin/customers/{id}") {
                call.adminCustomersInfo()
                return@get
            }

            post("/admin/customers/{id}/ebics/keyletter") {
                call.adminCustomersKeyletter()
                return@post
            }

            post("/ebicsweb") {
                call.ebicsweb()
                return@post
            }
        }
    }
    logger.info("Up and running")
    server.start(wait = true)
}

