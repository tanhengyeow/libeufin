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
fun findCustomer(id: String?): BankCustomerEntity {

    val idN = try {
        id!!.toInt()
    } catch (e: Exception) {
        e.printStackTrace()
        throw BadInputData(id)
    }

    return transaction {
        addLogger(StdOutSqlLogger)
        BankCustomerEntity.findById(idN) ?: throw CustomerNotFound(id)
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

fun sampleData() {
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
        val customerEntity = BankCustomerEntity.new {
            addLogger(StdOutSqlLogger)
            customerName = "Mina"
        }
        LOGGER.debug("Creating customer number: ${customerEntity.id}")
        EbicsSubscriberEntity.new {
            partnerId = "PARTNER1"
            userId = "USER1"
            systemId = null
            hostId = "HOST01"
            state = SubscriberState.NEW
            nextOrderID = 1
            bankCustomer = customerEntity
        }
        for (i in listOf(Amount("-0.44"), Amount("6.02"))) {
            BankTransactionEntity.new {
                counterpart = "IBAN"
                amount = i
                subject = "transaction $i"
                operationDate = DateTime.now().millis
                valueDate = DateTime.now().millis
                localCustomer = customerEntity
            }
        }
    }
}

/**
 * @param id the customer whose history must be returned.  This
 * id is local to the bank and is not reused/encoded into other
 * EBICS id values.
 *
 * @return result set of all the operations related to the customer
 * identified by @p id.
 */
fun extractHistory(id: Int, start: String?, end: String?): SizedIterable<BankTransactionEntity> {
    val s = if (start != null) DateTime.parse(start) else DateTime(0)
    val e = if (end != null) DateTime.parse(end) else DateTime.now()

    LOGGER.debug("Fetching history from $s to $e")

    return transaction {
        addLogger(StdOutSqlLogger)
        BankTransactionEntity.find {
            BankTransactionsTable.localCustomer eq id and BankTransactionsTable.valueDate.between(s.millis, e.millis)
        }
    }
}

fun calculateBalance(id: Int, start: String?, end: String?): BigDecimal {
    val s = if (start != null) DateTime.parse(start) else DateTime(0)
    val e = if (end != null) DateTime.parse(end) else DateTime.now()

    var ret = BigDecimal(0)

    transaction {
        BankTransactionEntity.find {
            BankTransactionsTable.localCustomer eq id and BankTransactionsTable.operationDate.between(s.millis, e.millis)
        }.forEach { ret += it.amount }
    }
    return ret
}

fun main() {

    dbCreateTables()
    sampleData()

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

            post("/{id}/history") {
                val req = call.receive<CustomerHistoryRequest>()
                val customer = findCustomer(call.parameters["id"])
                val ret = CustomerHistoryResponse()
                val history = extractHistory(customer.id.value, req.start, req.end)
                transaction {
                    history.forEach {
                        ret.history.add(
                            CustomerHistoryResponseElement(
                                subject = it.subject,
                                amount = "${it.amount.signToString()}${it.amount} EUR",
                                counterpart = it.counterpart,
                                operationDate = DateTime(it.operationDate).toString("Y-M-d"),
                                valueDate = DateTime(it.valueDate).toString("Y-M-d")
                            )
                        )
                    }
                }
                call.respond(ret)
                return@post
            }

            get("/{id}/balance") {
                val customer = findCustomer(call.parameters["id"])
                val balance = calculateBalance(customer.id.value, null, null)
                call.respond(
                    CustomerBalance(
                    name = customer.customerName,
                    balance = "${balance} EUR"
                    )
                )
                return@get
            }

            get("/admin/subscribers") {
                var ret = AdminGetSubscribers()
                transaction {
                    EbicsSubscriberEntity.all().forEach {
                        ret.subscribers.add(
                            AdminSubscriberElement(
                            userId = it.userId, partnerID = it.partnerId, hostID = it.hostId, name = it.bankCustomer.customerName))
                    }
                }
                call.respond(ret)
                return@get
            }

            post("/admin/add/subscriber") {
                val body = call.receive<AdminAddSubscriberRequest>()

                transaction {
                    val customerEntity = BankCustomerEntity.new {
                        addLogger(StdOutSqlLogger)
                        customerName = body.name
                    }
                    EbicsSubscriberEntity.new {
                        partnerId = body.partnerID
                        userId = body.userID
                        systemId = null
                        hostId = body.hostID
                        state = SubscriberState.NEW
                        nextOrderID = 1
                        bankCustomer = customerEntity
                    }
                }

                call.respondText("Subscriber created.", ContentType.Text.Plain, HttpStatusCode.OK)
                return@post
            }

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
                val pairA = CryptoUtil.generateRsaKeyPair(2048)
                val pairB = CryptoUtil.generateRsaKeyPair(2048)
                val pairC = CryptoUtil.generateRsaKeyPair(2048)
                transaction {
                    addLogger(StdOutSqlLogger)
                    EbicsHostEntity.new {
                        this.ebicsVersion = req.ebicsVersion
                        this.hostId = req.hostId
                        this.authenticationPrivateKey = SerialBlob(pairA.private.encoded)
                        this.encryptionPrivateKey = SerialBlob(pairB.private.encoded)
                        this.signaturePrivateKey = SerialBlob(pairC.private.encoded)

                    }
                }
                call.respondText(
                    "Host created.",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post

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
    LOGGER.info("Up and running")
    server.start(wait = true)
}
