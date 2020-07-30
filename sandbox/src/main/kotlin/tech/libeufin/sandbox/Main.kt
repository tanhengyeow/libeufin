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
import javax.xml.bind.JAXBContext
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.ktor.http.toHttpDateString
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import java.time.Instant
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.util.AttributeKey
import tech.libeufin.sandbox.PaymentsTable
import tech.libeufin.sandbox.PaymentsTable.amount
import tech.libeufin.sandbox.PaymentsTable.creditorBic
import tech.libeufin.sandbox.PaymentsTable.creditorIban
import tech.libeufin.sandbox.PaymentsTable.creditorName
import tech.libeufin.sandbox.PaymentsTable.currency
import tech.libeufin.sandbox.PaymentsTable.date
import tech.libeufin.sandbox.PaymentsTable.debitorBic
import tech.libeufin.sandbox.PaymentsTable.debitorIban
import tech.libeufin.sandbox.PaymentsTable.debitorName
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.EbicsResponse
import tech.libeufin.util.ebics_h004.EbicsTypes

class CustomerNotFound(id: String?) : Exception("Customer ${id} not found")
class BadInputData(inputData: String?) : Exception("Customer provided invalid input data: ${inputData}")
class UnacceptableFractional(badNumber: BigDecimal) : Exception(
    "Unacceptable fractional part ${badNumber}"
)
lateinit var LOGGER: Logger

data class SandboxError(val statusCode: HttpStatusCode, val reason: String) : Exception()
data class SandboxErrorJson(val error: SandboxErrorDetailJson)
data class SandboxErrorDetailJson(val type: String, val description: String)

class SandboxCommand : CliktCommand() {
    override fun run() = Unit
}

class Serve : CliktCommand("Run sandbox HTTP server") {
    private val dbName by option().default("libeufin-sandbox.sqlite3")
    private val logLevel by option()
    override fun run() {
        LOGGER = LoggerFactory.getLogger("tech.libeufin.sandbox")
        setLogLevel(logLevel)
        serverMain(dbName)
    }
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

fun main(args: Array<String>) {
    SandboxCommand()
        .subcommands(Serve())
        .main(args)
}

fun serverMain(dbName: String) {
    dbCreateTables(dbName)
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
            exception<ArithmeticException> { cause ->
                LOGGER.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText(
                    "Invalid arithmetic attempted.",
                    ContentType.Text.Plain,
                    // here is always the bank's fault, as it should always check
                    // the operands.
                    HttpStatusCode.InternalServerError
                )
            }

            exception<EbicsRequestError> { cause ->
                val resp = EbicsResponse.createForUploadWithError(
                    cause.errorText,
                    cause.errorCode,
                    // assuming that the phase is always transfer,
                    // as errors during initialization should have
                    // already been caught by the chunking logic.
                    EbicsTypes.TransactionPhaseType.TRANSFER
                )

                val hostAuthPriv = transaction {
                    val host = EbicsHostEntity.find {
                        EbicsHostsTable.hostID.upperCase() eq call.attributes.get(EbicsHostIdAttribute).toUpperCase()
                    }.firstOrNull() ?: throw SandboxError(
                        HttpStatusCode.InternalServerError,
                        "Requested Ebics host ID not found."
                    )
                    CryptoUtil.loadRsaPrivateKey(host.authenticationPrivateKey.bytes)
                }
                call.respondText(
                    XMLUtil.signEbicsResponse(resp, hostAuthPriv),
                    ContentType.Application.Xml,
                    HttpStatusCode.OK
                )
            }
            exception<SandboxError> { cause ->
                LOGGER.error("Exception while handling '${call.request.uri}'", cause)
                call.respond(
                    cause.statusCode,
                    SandboxErrorJson(
                        error = SandboxErrorDetailJson(
                            type = "sandbox-error",
                            description = cause.reason
                        )
                    )
                )
            }
            exception<Throwable> { cause ->
                LOGGER.error("Exception while handling '${call.request.uri}'", cause)
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
            get("/") {
                call.respondText("Hello, this is Sandbox\n", ContentType.Text.Plain)
            }
            get("/admin/payments") {
                val ret = PaymentsResponse()
                transaction {
                    PaymentsTable.selectAll().forEach {
                        ret.payments.add(
                            RawPayment(
                                creditorIban = it[creditorIban],
                                debitorIban = it[debitorIban],
                                subject = it[PaymentsTable.subject],
                                date = it[date].toHttpDateString(),
                                amount = it[amount],
                                creditorBic = it[creditorBic],
                                creditorName = it[creditorName],
                                debitorBic = it[debitorBic],
                                debitorName = it[debitorName],
                                currency = it[currency]
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
                   PaymentsTable.insert {
                       it[creditorIban] = body.creditorIban
                       it[creditorBic] = body.creditorBic
                       it[creditorName] = body.creditorName
                       it[debitorIban] = body.debitorIban
                       it[debitorBic] = body.debitorBic
                       it[debitorName] = body.debitorName
                       it[subject] = body.subject
                       it[amount] = body.amount
                       it[currency] = body.currency
                       it[date] = Instant.now().toEpochMilli()
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
                        this.authenticationPrivateKey = ExposedBlob(pairA.private.encoded)
                        this.encryptionPrivateKey = ExposedBlob(pairB.private.encoded)
                        this.signaturePrivateKey = ExposedBlob(pairC.private.encoded)

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