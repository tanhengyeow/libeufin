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
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.w3c.dom.Document
import tech.libeufin.util.Amount
import tech.libeufin.util.CryptoUtil
import java.lang.ArithmeticException
import java.math.BigDecimal
import java.security.interfaces.RSAPublicKey
import java.text.DateFormat
import javax.sql.rowset.serial.SerialBlob
import javax.xml.bind.JAXBContext

class CustomerNotFound(id: String?) : Exception("Customer ${id} not found")
class BadInputData(inputData: String?) : Exception("Customer provided invalid input data: ${inputData}")
class UnacceptableFractional(badNumber: BigDecimal) : Exception(
    "Unacceptable fractional part ${badNumber}"
)
val LOGGER: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")

data class SandboxError(val statusCode: HttpStatusCode, val reason: String) : java.lang.Exception()

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
            gson {
                setDateFormat(DateFormat.LONG)
                setPrettyPrinting()
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
                call.respondText("Hello Sandbox!\n", ContentType.Text.Plain)
            }

            /** EBICS ADMIN ENDPOINTS */
            post("/admin/ebics-subscriber/bank-account") {
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
                call.respondText(
                    "Bank account created, and associated to the subscriber"
                )
                return@post
            }

            post("/admin/ebics-subscriber") {
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
            get("/admin/ebics-subscribers") {
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
            /* Show details about ONE Ebics host */
            get("/ebics/hosts/{id}") {
                val resp = transaction {
                    val host = EbicsHostEntity.find { EbicsHostsTable.hostID eq call.parameters["id"]!! }.firstOrNull()
                    if (host == null) null
                    else EbicsHostResponse(
                        host.hostId,
                        host.ebicsVersion
                    )
                }
                if (resp == null) call.respond(
                    HttpStatusCode.NotFound,
                    SandboxError(HttpStatusCode.NotFound,"host not found")
                )
                else call.respond(resp)
            }
            /** Create a new EBICS host. */
            post("/admin/ebics-host") {
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
            /* Show ONLY names of all the Ebics hosts */
            get("/ebics/hosts") {
                val ebicsHosts = transaction {
                    EbicsHostEntity.all().map { it.hostId }
                }
                call.respond(EbicsHostsResponse(ebicsHosts))
            }

            /** MAIN EBICS handler. */
            post("/ebicsweb") {
                call.ebicsweb()
            }

        }
    }
    LOGGER.info("Up and running")
    server.start(wait = true)
}
