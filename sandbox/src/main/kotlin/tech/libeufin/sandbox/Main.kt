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
import org.jetbrains.exposed.sql.GreaterEqOp
import org.jetbrains.exposed.sql.LessEqOp
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.dateTimeParam
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.w3c.dom.Document
import java.lang.ArithmeticException
import java.math.BigDecimal
import java.security.interfaces.RSAPublicKey
import java.text.DateFormat
import javax.sql.rowset.serial.SerialBlob
import javax.xml.bind.JAXBContext


class CustomerNotFound(id: String?) : Exception("Customer ${id} not found")
class BadInputData(inputData: String?) : Exception("Customer provided invalid input data: ${inputData}")
class BadAmount(badValue: Any?) : Exception("Value '${badValue}' is not a valid amount")
class UnacceptableFractional(statusCode: HttpStatusCode, badNumber: BigDecimal) : Exception(
    "Unacceptable fractional part ${badNumber}"
)


fun findCustomer(id: String?): BankCustomerEntity {

    val idN = try {
        id!!.toInt()
    } catch (e: Exception) {
        e.printStackTrace()
        throw BadInputData(id)
    }

    return transaction {

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
            name = "Mina"
        }

        EbicsSubscriberEntity.new {
            partnerId = "PARTNER1"
            userId = "USER1"
            systemId = null
            state = SubscriberState.NEW
            nextOrderID = 1
            bankCustomer = customerEntity
        }

        for (i in listOf<Amount>(Amount("-0.44"), Amount("6.02"))) {
            BankTransactionEntity.new {
                counterpart = "IBAN"
                amount = i
                subject = "transaction $i"
                date = DateTime.now()
                localCustomer = customerEntity
            }

        }
    }


}

fun extractHistoryForEach(id: Int, start: String?, end: String?, builder: (BankTransactionEntity) -> Any) {
    val s = if (start != null) DateTime.parse(start) else DateTime(0)
    val e = if (end != null) DateTime.parse(end) else DateTime.now()

    transaction {
        BankTransactionEntity.find {
            BankTransactionsTable.localCustomer eq id and
                    BankTransactionsTable.date.between(s, e)
        }.forEach {
            builder(it)
        }
    }
}

fun calculateBalance(id: Int, start: String?, end: String?): BigDecimal {
    val s = if (start != null) DateTime.parse(start) else DateTime(0)
    val e = if (end != null) DateTime.parse(end) else DateTime.now()

    var ret = BigDecimal(0)

    transaction {
        BankTransactionEntity.find {
            BankTransactionsTable.localCustomer eq id and BankTransactionsTable.date.between(s, e)
        }.forEach { ret += it.amount }
    }
    return ret
}

val LOGGER: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")

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
                LOGGER.debug("Fetching history from ${req.start}, to ${req.end}")

                val customer = findCustomer(call.parameters["id"])
                val ret = CustomerHistoryResponse()

                extractHistoryForEach(customer.id.value, req.start, req.end) {
                    ret.history.add(
                        CustomerHistoryResponseElement(
                            subject = it.subject,
                            amount = "${it.amount.signToString()}${it.amount} EUR",
                            counterpart = it.counterpart,
                            date = it.date.toString("Y-M-d")
                        )
                    )
                }
                call.respond(ret)
                return@post
            }

            get("/{id}/balance") {

                val customer = findCustomer(call.parameters["id"])
                val balance = calculateBalance(customer.id.value, null, null)

                call.respond(
                    CustomerBalance(
                    name = customer.name,
                    balance = "${balance} EUR"
                    )
                )

                return@get
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
                transaction {
                    EbicsHostEntity.new {
                        this.ebicsVersion = req.ebicsVersion
                        this.hostId = hostId
                    }
                }
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
