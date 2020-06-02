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

import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
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
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import io.ktor.jackson.jackson
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.w3c.dom.Document
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.RawPayment
import java.lang.ArithmeticException
import java.math.BigDecimal
import java.security.interfaces.RSAPublicKey
import java.text.DateFormat
import javax.sql.rowset.serial.SerialBlob
import javax.xml.bind.JAXBContext
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.toHttpDateString
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class CustomerNotFound(id: String?) : Exception("Customer ${id} not found")
class BadInputData(inputData: String?) : Exception("Customer provided invalid input data: ${inputData}")
class UnacceptableFractional(badNumber: BigDecimal) : Exception(
    "Unacceptable fractional part ${badNumber}"
)
val LOGGER: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")

data class SandboxError(
    val statusCode: HttpStatusCode,
    val reason: String
) : java.lang.Exception()

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
    val systemID: String?,
    val keys: SubscriberKeys
)

data class SubscriberKeys(
    val authenticationPublicKey: RSAPublicKey,
    val encryptionPublicKey: RSAPublicKey,
    val signaturePublicKey: RSAPublicKey
)

data class EbicsHostPublicInfo(
    val hostID: String,
    val encryptionPublicKey: RSAPublicKey,
    val authenticationPublicKey: RSAPublicKey
)

inline fun <reified T> Document.toObject(): T {
    val jc = JAXBContext.newInstance(T::class.java)
    val m = jc.createUnmarshaller()
    return m.unmarshal(this, T::class.java).value
}

fun BigDecimal.signToString(): String {

    return if (this.signum() > 0) "+" else ""
    // minus sign is added by default already.
}

fun main() {
    dbCreateTables()
    val server = embeddedServer(Netty, port = 5000) {
        install(CallLogging) {
            this.level = Level.DEBUG
            this.logger = LOGGER
        }
        install(ContentNegotiation) {
            jackson {
                enable(SerializationFeature.INDENT_OUTPUT)
                setDefaultPrettyPrinter(DefaultPrettyPrinter().apply {
                    indentArraysWith(DefaultPrettyPrinter.FixedSpaceIndenter.instance)
                    indentObjectsWith(DefaultIndenter("  ", "\n"))
                })
                registerModule(KotlinModule(nullisSameAsDefault = true))
                //registerModule(JavaTimeModule())
            }
        }
        install(StatusPages) {
            exception<Throwable> { cause ->
                LOGGER.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Internal server error.", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
            }
            exception<ArithmeticException> { cause ->
                LOGGER.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Invalid arithmetic attempted.", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
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
            get("/") {
                call.respondText("Hello, this is Sandbox\n", ContentType.Text.Plain)
            }
            get("/admin/payments") {
                val ret = PaymentsResponse()
                transaction {
                    PaymentEntity.all().forEach {
                        ret.payments.add(
                            RawPayment(
                                creditorIban = it.creditorIban,
                                debitorIban = it.debitorIban,
                                subject = it.subject,
                                date = it.date.toHttpDateString(),
                                amount = it.amount
                            )
                        )
                    }
                }
                call.respond(ret)
                return@get
            }

            /**
             * Adds a new payment to the book.
             */
            post("/admin/payments") {
                val body = call.receive<RawPayment>()
                transaction {
                   PaymentEntity.new {
                       creditorIban = body.creditorIban
                       debitorIban = body.debitorIban
                       subject = body.subject
                       amount = body.amount
                       date = Instant.now().toEpochMilli()
                   }
                }
                call.respondText("Payment created")
                return@post
            }
            /**
             * Associates a new bank account with an existing Ebics subscriber.
             */
            post("/admin/ebics/bank-accounts") {
                val body = call.receive<BankAccountRequest>()
                transaction {
                    val subscriber = getEbicsSubscriberFromDetails(
                        body.subscriber.userID,
                        body.subscriber.partnerID,
                        body.subscriber.hostID
                    )
                    BankAccountEntity.new {
                        this.subscriber = subscriber
                        iban = body.iban
                        bic = body.bic
                        name = body.name
                        label = body.label
                    }
                }
                call.respondText("Bank account created")
                return@post
            }
            /**
             * Creates a new Ebics subscriber.
             */
            post("/admin/ebics/subscribers") {
                val body = call.receive<EbicsSubscriberElement>()
                transaction {
                    EbicsSubscriberEntity.new {
                        partnerId = body.partnerID
                        userId = body.userID
                        systemId = null
                        hostId = body.hostID
                        state = SubscriberState.NEW
                        nextOrderID = 1
                    }
                }
                call.respondText(
                    "Subscriber created.",
                    ContentType.Text.Plain, HttpStatusCode.OK
                )
                return@post
            }
            /**
             * Shows all the Ebics subscribers' details.
             */
            get("/admin/ebics/subscribers") {
                var ret = AdminGetSubscribers()
                transaction {
                    EbicsSubscriberEntity.all().forEach {
                        ret.subscribers.add(
                            EbicsSubscriberElement(
                                userID = it.userId,
                                partnerID = it.partnerId,
                                hostID = it.hostId
                            )
                        )
                    }
                }
                call.respond(ret)
                return@get
            }
            /**
             * Creates a new EBICS host.
             */
            post("/admin/ebics/host") {
                val req = call.receive<EbicsHostCreateRequest>()
                val pairA = CryptoUtil.generateRsaKeyPair(2048)
                val pairB = CryptoUtil.generateRsaKeyPair(2048)
                val pairC = CryptoUtil.generateRsaKeyPair(2048)
                transaction {
                    addLogger(StdOutSqlLogger)
                    EbicsHostEntity.new {
                        this.ebicsVersion = req.ebicsVersion
                        this.hostId = req.hostID
                        this.authenticationPrivateKey = SerialBlob(pairA.private.encoded)
                        this.encryptionPrivateKey = SerialBlob(pairB.private.encoded)
                        this.signaturePrivateKey = SerialBlob(pairC.private.encoded)

                    }
                }
                call.respondText(
                    "Host '${req.hostID}' created.",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }
            /**
             * Show the names of all the Ebics hosts
             */
            get("/admin/ebics/hosts") {
                val ebicsHosts = transaction {
                    EbicsHostEntity.all().map { it.hostId }
                }
                call.respond(EbicsHostsResponse(ebicsHosts))
            }
            /**
             * Serves all the Ebics requests.
             */
            post("/ebicsweb") {
                call.ebicsweb()
            }
        }
    }
    LOGGER.info("Up and running")
    server.start(wait = true)
}