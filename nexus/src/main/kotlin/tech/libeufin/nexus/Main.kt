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
import io.ktor.client.HttpClient
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
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.util.*
import tech.libeufin.util.InvalidSubscriberStateError
import tech.libeufin.util.ebics_h004.EbicsTypes
import tech.libeufin.util.ebics_h004.HTDResponseOrderData
import java.lang.StringBuilder
import java.security.interfaces.RSAPublicKey
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.EncryptedPrivateKeyInfo
import javax.print.attribute.standard.JobStateReason
import javax.sql.rowset.serial.SerialBlob

fun testData() {
    val pairA = CryptoUtil.generateRsaKeyPair(2048)
    val pairB = CryptoUtil.generateRsaKeyPair(2048)
    val pairC = CryptoUtil.generateRsaKeyPair(2048)
    try {
        transaction {
            addLogger(StdOutSqlLogger)
            EbicsSubscriberEntity.new(id = "default-customer") {
                ebicsURL = "http://localhost:5000/ebicsweb"
                userID = "USER1"
                partnerID = "PARTNER1"
                hostID = "host01"
                signaturePrivateKey = SerialBlob(pairA.private.encoded)
                encryptionPrivateKey = SerialBlob(pairB.private.encoded)
                authenticationPrivateKey = SerialBlob(pairC.private.encoded)
            }
        }
    } catch (e: ExposedSQLException) {
        logger.info("Likely primary key collision for sample data: accepted")
    }
}

data class NexusError(val statusCode: HttpStatusCode, val reason: String) : Exception(reason)

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus")

fun getSubscriberEntityFromId(id: String): EbicsSubscriberEntity {
    return transaction {
        EbicsSubscriberEntity.findById(id) ?: throw NexusError(HttpStatusCode.NotFound, "Subscriber not found from id '$id'")
    }
}

fun getBankAccountDetailsFromAcctid(id: String): EbicsAccountInfoElement {
    return transaction {
        val bankAccount = EbicsAccountInfoEntity.find {
            EbicsAccountsInfoTable.id eq id
        }.firstOrNull() ?: throw NexusError(HttpStatusCode.NotFound, "Bank account not found from account id '$id'")
        EbicsAccountInfoElement(
            accountId = id,
            accountHolderName = bankAccount.accountHolder,
            iban = bankAccount.iban,
            bankCode = bankAccount.bankCode
        )
    }
}
fun getSubscriberDetailsFromBankAccount(bankAccountId: String): EbicsClientSubscriberDetails {
    return transaction {
        val accountInfo = EbicsAccountInfoEntity.findById(bankAccountId) ?: throw NexusError(HttpStatusCode.NotFound, "Bank account ($bankAccountId) not managed by Nexus")
        logger.debug("Mapping bank account: ${bankAccountId}, to customer: ${accountInfo.subscriber.id.value}")
        getSubscriberDetailsFromId(accountInfo.subscriber.id.value)
    }
}

fun getSubscriberDetailsFromId(id: String): EbicsClientSubscriberDetails {
    return transaction {
        val subscriber = EbicsSubscriberEntity.findById(id) ?: throw NexusError(HttpStatusCode.NotFound, "subscriber not found from id '$id'")
        var bankAuthPubValue: RSAPublicKey? = null
        if (subscriber.bankAuthenticationPublicKey != null) {
            bankAuthPubValue = CryptoUtil.loadRsaPublicKey(
                subscriber.bankAuthenticationPublicKey?.toByteArray()!!
            )
        }
        var bankEncPubValue: RSAPublicKey? = null
        if (subscriber.bankEncryptionPublicKey != null) {
            bankEncPubValue = CryptoUtil.loadRsaPublicKey(
                subscriber.bankEncryptionPublicKey?.toByteArray()!!
            )
        }
        EbicsClientSubscriberDetails(
            bankAuthPub = bankAuthPubValue,
            bankEncPub = bankEncPubValue,

            ebicsUrl = subscriber.ebicsURL,
            hostId = subscriber.hostID,
            userId = subscriber.userID,
            partnerId = subscriber.partnerID,

            customerSignPriv = CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.toByteArray()),
            customerAuthPriv = CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray()),
            customerEncPriv = CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.toByteArray())
        )
    }
}

/**
 * Create a PAIN.001 XML document according to the input data.
 * Needs to be called within a transaction block.
 */
fun createPain001document(pain001Entity: Pain001Entity): String {

    /**
     * Every PAIN.001 document contains at least three IDs:
     *
     * 1) MsgId: a unique id for the message itself
     * 2) PmtInfId: the unique id for the payment's set of information
     * 3) EndToEndId: a unique id to be shared between the debtor and
     *    creditor that uniquely identifies the transaction
     *
     * For now and for simplicity, since every PAIN entry in the database
     * has a unique ID, and the three values aren't required to be mutually different,
     * we'll assign the SAME id (= the row id) to all the three aforementioned
     * PAIN id types.
     */

    val s = constructXml(indent = true) {
        root("Document") {
            element("CstmrCdtTrfInitn") {
                element("GrpHdr") {
                    element("MsgId") {
                        text(pain001Entity.id.value.toString())
                    }
                    element("CreDtTm") {
                        val dateMillis = transaction {
                            pain001Entity.date
                        }
                        text(DateTime(dateMillis).toString("Y-M-d"))
                    }
                    element("NbOfTxs") {
                        text("1")
                    }
                    element("CtrlSum") {
                        text(pain001Entity.sum.toString())
                    }
                    element("InitgPty/Nm") {
                        text(pain001Entity.debtorAccount)
                    }
                }
                element("PmtInf") {
                    element("PmtInfId") {
                        text(pain001Entity.id.value.toString())
                    }
                    element("PmtMtd") {
                        text("TRF")
                    }
                    element("BtchBookg") {
                        text("true")
                    }
                    element("NbOfTxs") {
                        text("1")
                    }
                    element("CtrlSum") {
                        text(pain001Entity.sum.toString())
                    }
                    element("PmtTpInf/SvcLvl/Cd") {
                        text("SEPA")
                    }
                    element("ReqdExctnDt") {
                        val dateMillis = transaction {
                            pain001Entity.date
                        }
                        text(DateTime(dateMillis).toString("Y-M-d"))
                    }
                    element("Dbtr/Nm") {
                        text(pain001Entity.debtorAccount)
                    }
                    element("DbtrAcct/Id/IBAN") {
                        text(transaction {
                            EbicsAccountInfoEntity.findById(pain001Entity.debtorAccount)?.iban ?: throw NexusError(HttpStatusCode.NotFound,"Debtor IBAN not found in database")
                        })
                    }
                    element("DbtrAgt/FinInstnId/BIC") {

                        text(transaction {
                            EbicsAccountInfoEntity.findById(pain001Entity.debtorAccount)?.bankCode ?: throw NexusError(HttpStatusCode.NotFound,"Debtor BIC not found in database")
                        })
                    }

                    element("ChrgBr") {
                        text("SLEV")
                    }
                    element("CdtTrfTxInf") {
                        element("PmtId") {
                            element("EndToEndId") {
                                text(pain001Entity.id.value.toString())
                            }
                        }
                        element("Amt/InstdAmt") {
                            attribute("Ccy", "EUR")
                            text(pain001Entity.sum.toString())
                        }
                        element("CdtrAgt/FinInstnId/BIC") {
                            text(pain001Entity.creditorBic)
                        }
                        element("Cdtr/Nm") {
                            text(pain001Entity.creditorName)
                        }
                        element("CdtrAcct/Id/IBAN") {
                            text(pain001Entity.creditorIban)
                        }
                        element("RmtInf/Ustrd") {
                            text(pain001Entity.subject)
                        }
                    }
                }
            }
        }
    }
    return s
}

/**
 * Insert one row in the database, and leaves it marked as non-submitted.
 */
fun createPain001entry(entry: Pain001Data, debtorAccountId: String) {
    val randomId = Random().nextLong()
    transaction {
        Pain001Entity.new {
            subject = entry.subject
            sum = entry.sum
            debtorAccount = debtorAccountId
            creditorName = entry.creditorName
            creditorBic = entry.creditorBic
            creditorIban = entry.creditorIban
            date = DateTime.now().millis
            paymentId = randomId
            msgId = randomId
            endToEndId = randomId
        }
    }
}

fun main() {
    dbCreateTables()
    testData()
    val client = HttpClient() {
        expectSuccess = false // this way, it does not throw exceptions on != 200 responses.
    }
    val server = embeddedServer(Netty, port = 5001) {
        install(CallLogging) {
            this.level = Level.DEBUG
            this.logger = tech.libeufin.nexus.logger
        }
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

            post("/ebics/subscribers/{id}/sendPTK") {
                val id = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                println("PTK order params: $orderParams")
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "PTK", orderParams)
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHAC") {
                val id = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "HAC", orderParams)
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
            }

            get("/ebics/subscribers/{id}/accounts") {
                // this information is only avaiable *after* HTD or HKD has been called
                val id = expectId(call.parameters["id"])
                val ret = EbicsAccountsInfoResponse()
                transaction {
                    EbicsAccountInfoEntity.find {
                        EbicsAccountsInfoTable.subscriber eq id
                    }.forEach {
                        ret.accounts.add(
                            EbicsAccountInfoElement(
                                accountHolderName = it.accountHolder,
                                iban = it.iban,
                                bankCode = it.bankCode,
                                accountId = it.id.value
                            )
                        )
                    }
                }
                call.respond(
                    HttpStatusCode.OK,
                    ret
                )
                return@get
            }

            /**
             * This endpoint gathers all the data needed to create a payment and persists it
             * into the database.  However, it does NOT perform the payment itself!
             */
            post("/ebics/subscribers/{id}/accounts/{acctid}/prepare-payment") {
                val acctid = expectId(call.parameters["acctid"])
                val subscriberId = expectId(call.parameters["id"])

                transaction {
                    val accountinfo = EbicsAccountInfoEntity.findById(acctid)
                    val subscriber = EbicsSubscriberEntity.findById(subscriberId)
                    if (accountinfo?.subscriber != subscriber) {
                        throw NexusError(HttpStatusCode.BadRequest, "Claimed bank account '$acctid' doesn't belong to subscriber '$subscriberId'!")
                    }
                }
                val pain001data = call.receive<Pain001Data>()
                createPain001entry(pain001data, acctid)

                call.respondText("Payment instructions persisted in DB", ContentType.Text.Plain, HttpStatusCode.OK)
                return@post
            }

            /**
             * list all the payments related to customer {id}
             */
            get("/ebics/subscribers/{id}/payments") {

                val id = expectId(call.parameters["id"])
                val ret = PaymentsInfo()
                transaction {
                    EbicsAccountInfoEntity.find {
                        EbicsAccountsInfoTable.subscriber eq id
                    }.forEach {
                        Pain001Entity.find {
                            Pain001Table.debtorAccount eq it.id.value
                        }.forEach {
                            ret.payments.add(
                                PaymentInfoElement(
                                    debtorAccount = it.debtorAccount,
                                    creditorIban = it.creditorIban,
                                    creditorBic = it.creditorBic,
                                    creditorName = it.creditorName,
                                    subject = it.subject,
                                    sum = it.sum,
                                    submitted = it.submitted // whether Nexus processed and sent to the bank
                                )
                            )
                        }
                    }
                }
                call.respond(ret)
                return@get
            }

            /**
             * This function triggers the Nexus to perform all those un-submitted payments.
             * Ideally, this logic will be moved into some more automatic mechanism.
             * NOTE: payments are not yet marked as "done" after this function returns.  This
             * should be done AFTER the PAIN.002 data corresponding to a payment witnesses it.
             */
            post("/ebics/admin/execute-payments") {
                val (painDoc, debtorAccount) = transaction {
                    val entity = Pain001Entity.find {
                        Pain001Table.submitted eq false
                    }.firstOrNull() ?: throw NexusError(HttpStatusCode.Accepted, reason = "No ready payments found")
                    kotlin.Pair(createPain001document(entity), entity.debtorAccount)
                }
                logger.info("Processing payment for bank account: ${debtorAccount}")
                val subscriberDetails = getSubscriberDetailsFromBankAccount(debtorAccount)
                doEbicsUploadTransaction(
                    client,
                    subscriberDetails,
                    "CCC",
                    painDoc.toByteArray(Charsets.UTF_8),
                    EbicsStandardOrderParams()
                )
                call.respondText(
                    "CCC message submitted to the bank",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }

            post("/ebics/subscribers/{id}/fetch-payment-status") {
                // FIXME(marcello?):  Fetch pain.002 and mark transfers in it as "failed"
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "CRZ", EbicsStandardOrderParams())
                when (response) {
                    is EbicsDownloadSuccessResult ->
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK)
                    else -> call.respond(NexusErrorJson("Could not download any PAIN.002"))
                }
                return@post
            }

            post("/ebics/subscribers/{id}/collect-transactions-c52") {
                // FIXME(florian): Download C52 and store the result in the right database table
            }

            post("/ebics/subscribers/{id}/collect-transactions-c53") {
                // FIXME(florian): Download C53 and store the result in the right database table
            }

            post("/ebics/subscribers/{id}/collect-transactions-c54") {
                // FIXME(florian): Download C54 and store the result in the right database table
            }

            get("/ebics/subscribers/{id}/transactions") {
                // FIXME(florian): Display local transaction history stored by the nexus.
            }

            post("/ebics/subscribers/{id}/sendC52") {
                val id = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "C52", orderParams)
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
            }

            post("/ebics/subscribers/{id}/sendC53") {
                val id = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "C53", orderParams)
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        val mem = SeekableInMemoryByteChannel(response.orderData)
                        val zipFile = ZipFile(mem)

                        val s = StringBuilder()

                        zipFile.getEntriesInPhysicalOrder().iterator().forEach { entry ->
                            s.append("<=== File ${entry.name} ===>\n")
                            s.append(zipFile.getInputStream(entry).readAllBytes().toString(Charsets.UTF_8))
                            s.append("\n")
                        }
                        call.respondText(
                            s.toString(),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
            }

            post("/ebics/subscribers/{id}/sendC54") {
                val id = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "C54", orderParams)
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHtd") {
                val customerIdAtNexus = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromId(customerIdAtNexus)
                val response = doEbicsDownloadTransaction(client, subscriberData, "HTD", orderParams)
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHAA") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "HAA", EbicsStandardOrderParams())
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHVZ") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                // FIXME: order params are wrong
                val response = doEbicsDownloadTransaction(client, subscriberData, "HVZ", EbicsStandardOrderParams())
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHVU") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                // FIXME: order params are wrong
                val response = doEbicsDownloadTransaction(client, subscriberData, "HVU", EbicsStandardOrderParams())
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHPD") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "HPD", EbicsStandardOrderParams())
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendHKD") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "HKD", EbicsStandardOrderParams())
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            post("/ebics/subscribers/{id}/sendTSD") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val response = doEbicsDownloadTransaction(client, subscriberData, "TSD", EbicsGenericOrderParams())
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
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
                val timeFormat = SimpleDateFormat("HH:mm:ss")
                val dateLine = dateFormat.format(now)
                val timeLine = timeFormat.format(now)
                var hostID = ""
                transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw NexusError(
                        HttpStatusCode.NotFound, "Subscriber '$id' not found"
                    )
                    val signPubTmp = CryptoUtil.getRsaPublicFromPrivate(
                        CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.toByteArray())
                    )
                    val authPubTmp = CryptoUtil.getRsaPublicFromPrivate(
                        CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray())
                    )
                    val encPubTmp = CryptoUtil.getRsaPublicFromPrivate(
                        CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.toByteArray())
                    )
                    hostID = subscriber.hostID
                    userIdLine = subscriber.userID
                    esExponentLine = signPubTmp.publicExponent.toUnsignedHexString()
                    esModulusLine = signPubTmp.modulus.toUnsignedHexString()
                    encExponentLine = encPubTmp.publicExponent.toUnsignedHexString()
                    encModulusLine = encPubTmp.modulus.toUnsignedHexString()
                    authExponentLine = authPubTmp.publicExponent.toUnsignedHexString()
                    authModulusLine = authPubTmp.modulus.toUnsignedHexString()
                    esKeyHashLine = CryptoUtil.getEbicsPublicKeyHash(signPubTmp).toHexString()
                    encKeyHashLine = CryptoUtil.getEbicsPublicKeyHash(encPubTmp).toHexString()
                    authKeyHashLine = CryptoUtil.getEbicsPublicKeyHash(authPubTmp).toHexString()
                }
                val iniLetter = """
                    |Name: ${usernameLine}
                    |Date: ${dateLine}
                    |Time: ${timeLine}
                    |Recipient: ${recipientLine}
                    |Host ID: ${hostID}
                    |User ID: ${userIdLine}
                    |Partner ID: ${customerIdLine}
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
                    |
                """.trimMargin()

                val hiaLetter = """
                    |Name: ${usernameLine}
                    |Date: ${dateLine}
                    |Time: ${timeLine}
                    |Recipient: ${recipientLine}
                    |Host ID: ${hostID}
                    |User ID: ${userIdLine}
                    |Partner ID: ${customerIdLine}
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
                    |
                """.trimMargin()

                call.respondText(
                    "####INI####:\n${iniLetter}\n\n\n####HIA####:\n${hiaLetter}",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
            }

            get("/ebics/subscribers") {
                val ret = EbicsSubscribersResponseJson()
                transaction {
                    EbicsSubscriberEntity.all().forEach {
                        ret.ebicsSubscribers.add(
                            EbicsSubscriberInfoResponseJson(
                                accountID = it.id.value,
                                hostID = it.hostID,
                                partnerID = it.partnerID,
                                systemID = it.systemID,
                                ebicsURL = it.ebicsURL,
                                userID = it.userID
                            )
                        )
                    }
                }
                call.respond(ret)
                return@get
            }

            get("/ebics/subscribers/{id}") {
                val id = expectId(call.parameters["id"])
                val response = transaction {
                    val tmp = EbicsSubscriberEntity.findById(id) ?: throw NexusError(
                        HttpStatusCode.NotFound, "Subscriber '$id' not found"
                    )
                    EbicsSubscriberInfoResponseJson(
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

            get("/ebics/{id}/sendHev") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val request = makeEbicsHEVRequest(subscriberData)
                val response = client.postToBank(subscriberData.ebicsUrl, request)
                val versionDetails = parseEbicsHEVResponse(response)
                call.respond(
                    HttpStatusCode.OK,
                    EbicsHevResponseJson(versionDetails.versions.map { ebicsVersionSpec ->
                        ProtocolAndVersionJson(
                            ebicsVersionSpec.protocol,
                            ebicsVersionSpec.version
                        )
                    })
                )
                return@get
            }

            post("/ebics/{id}/subscribers") {
                val body = call.receive<EbicsSubscriberInfoRequestJson>()
                val pairA = CryptoUtil.generateRsaKeyPair(2048)
                val pairB = CryptoUtil.generateRsaKeyPair(2048)
                val pairC = CryptoUtil.generateRsaKeyPair(2048)
                val row = try {
                    transaction {
                        EbicsSubscriberEntity.new(id = expectId(call.parameters["id"])) {
                            ebicsURL = body.ebicsURL
                            hostID = body.hostID
                            partnerID = body.partnerID
                            userID = body.userID
                            systemID = body.systemID
                            signaturePrivateKey = SerialBlob(pairA.private.encoded)
                            encryptionPrivateKey = SerialBlob(pairB.private.encoded)
                            authenticationPrivateKey = SerialBlob(pairC.private.encoded)
                        }
                    }
                } catch (e: Exception) {
                    print(e)
                    call.respond(NexusErrorJson("Could not store the new account into database"))
                    return@post
                }
                call.respondText(
                    "Subscriber registered, ID: ${row.id.value}",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }

            post("/ebics/subscribers/{id}/sendIni") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val iniRequest = makeEbicsIniRequest(subscriberData)
                val responseStr = client.postToBank(
                    subscriberData.ebicsUrl,
                    iniRequest
                )
                val resp = parseAndDecryptEbicsKeyManagementResponse(subscriberData, responseStr)
                if (resp.technicalReturnCode != EbicsReturnCode.EBICS_OK) {
                    throw NexusError(HttpStatusCode.InternalServerError,"Unexpected INI response code: ${resp.technicalReturnCode}")
                }
                call.respondText("Bank accepted signature key\n", ContentType.Text.Plain, HttpStatusCode.OK)
                return@post
            }

            post("/ebics/subscribers/{id}/sendHia") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val hiaRequest = makeEbicsHiaRequest(subscriberData)
                val responseStr = client.postToBank(
                    subscriberData.ebicsUrl,
                    hiaRequest
                )
                val resp = parseAndDecryptEbicsKeyManagementResponse(subscriberData, responseStr)
                if (resp.technicalReturnCode != EbicsReturnCode.EBICS_OK) {
                    throw NexusError(HttpStatusCode.InternalServerError,"Unexpected HIA response code: ${resp.technicalReturnCode}")
                }
                call.respondText(
                    "Bank accepted authentication and encryption keys\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }

            post("/ebics/subscribers/{id}/restoreBackup") {
                val body = call.receive<EbicsKeysBackupJson>()
                val id = expectId(call.parameters["id"])
                val subscriber = transaction {
                    EbicsSubscriberEntity.findById(id)
                }
                if (subscriber != null) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        NexusErrorJson("ID exists, please choose a new one")
                    )
                    return@post
                }
                val (authKey, encKey, sigKey) = try {
                    Triple(
                        CryptoUtil.decryptKey(
                            EncryptedPrivateKeyInfo(base64ToBytes(body.authBlob)), body.passphrase!!
                        ),
                        CryptoUtil.decryptKey(
                            EncryptedPrivateKeyInfo(base64ToBytes(body.encBlob)), body.passphrase
                        ),
                        CryptoUtil.decryptKey(
                            EncryptedPrivateKeyInfo(base64ToBytes(body.sigBlob)), body.passphrase
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    logger.info("Restoring keys failed, probably due to wrong passphrase")
                    throw NexusError(HttpStatusCode.BadRequest, reason = "Bad backup given")
                }
                logger.info("Restoring keys, creating new user: $id")
                try {
                    transaction {
                        EbicsSubscriberEntity.new(id = expectId(call.parameters["id"])) {
                            ebicsURL = body.ebicsURL
                            hostID = body.hostID
                            partnerID = body.partnerID
                            userID = body.userID
                            signaturePrivateKey = SerialBlob(sigKey.encoded)
                            encryptionPrivateKey = SerialBlob(encKey.encoded)
                            authenticationPrivateKey = SerialBlob(authKey.encoded)
                        }
                    }
                } catch (e: Exception) {
                    print(e)
                    call.respond(NexusErrorJson("Could not store the new account $id into database"))
                    return@post
                }
                call.respondText(
                    "Keys successfully restored",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }

            get("/ebics/subscribers/{id}/pubkeys") {
                val id = expectId(call.parameters["id"])
                val response = transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw NexusError(
                        HttpStatusCode.NotFound, "Subscriber '$id' not found"
                    )
                    val authPriv = CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray())
                    val authPub = CryptoUtil.getRsaPublicFromPrivate(authPriv)
                    val encPriv = CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.toByteArray())
                    val encPub = CryptoUtil.getRsaPublicFromPrivate(encPriv)
                    val sigPriv = CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.toByteArray())
                    val sigPub = CryptoUtil.getRsaPublicFromPrivate(sigPriv)
                    EbicsPubKeyInfo(
                        bytesToBase64(authPub.encoded),
                        bytesToBase64(encPub.encoded),
                        bytesToBase64(sigPub.encoded)
                    )
                }
                call.respond(
                    HttpStatusCode.OK,
                    response
                )
            }

            post("/ebics/subscribers/{id}/fetch-accounts") {
                val customerIdAtNexus = expectId(call.parameters["id"])
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberData = getSubscriberDetailsFromId(customerIdAtNexus)
                val response = doEbicsDownloadTransaction(client, subscriberData, "HTD", orderParams)
                when (response) {
                    is EbicsDownloadSuccessResult -> {
                        val payload = XMLUtil.convertStringToJaxb<HTDResponseOrderData>(response.orderData.toString(Charsets.UTF_8))
                        transaction {
                            payload.value.partnerInfo.accountInfoList?.forEach {
                                EbicsAccountInfoEntity.new(id = it.id) {
                                    this.subscriber = getSubscriberEntityFromId(customerIdAtNexus)
                                    accountHolder = it.accountHolder
                                    iban = when (val firstAccount = it.accountNumberList?.get(0)) {
                                        is EbicsTypes.GeneralAccountNumber -> firstAccount.value
                                        is EbicsTypes.NationalAccountNumber -> firstAccount.value
                                        else -> throw NexusError(HttpStatusCode.NotFound, reason = "Unknown bank account type because of IBAN type")
                                    }
                                    bankCode = when (val firstBankCode = it.bankCodeList?.get(0)) {
                                        is EbicsTypes.GeneralBankCode -> firstBankCode.value
                                        is EbicsTypes.NationalBankCode -> firstBankCode.value
                                        else -> throw NexusError(HttpStatusCode.NotFound, reason = "Unknown bank account type because of BIC type")
                                    }
                                }
                            }
                        }
                        call.respondText(
                            response.orderData.toString(Charsets.UTF_8),
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    is EbicsDownloadBankErrorResult -> {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            EbicsErrorJson(EbicsErrorDetailJson("bankError", response.returnCode.errorCode))
                        )
                    }
                }
                return@post
            }

            /* performs a keys backup */
            post("/ebics/subscribers/{id}/backup") {
                val id = expectId(call.parameters["id"])
                val body = call.receive<EbicsBackupRequestJson>()
                val response = transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw NexusError(HttpStatusCode.NotFound, "Subscriber '$id' not found")
                    EbicsKeysBackupJson(
                        userID = subscriber.userID,
                        hostID = subscriber.hostID,
                        partnerID = subscriber.partnerID,
                        ebicsURL = subscriber.ebicsURL,
                        authBlob = bytesToBase64(
                            CryptoUtil.encryptKey(
                                subscriber.authenticationPrivateKey.toByteArray(),
                                body.passphrase
                            )
                        ),
                        encBlob = bytesToBase64(
                            CryptoUtil.encryptKey(
                                subscriber.encryptionPrivateKey.toByteArray(),
                                body.passphrase
                            )
                        ),
                        sigBlob = bytesToBase64(
                            CryptoUtil.encryptKey(
                                subscriber.signaturePrivateKey.toByteArray(),
                                body.passphrase
                            )
                        )
                    )
                }
                call.response.headers.append("Content-Disposition", "attachment")
                call.respond(
                    HttpStatusCode.OK,
                    response
                )
            }

            post("/ebics/subscribers/{id}/sendTSU") {
                val id = expectId(call.parameters["id"])
                val subscriberData = getSubscriberDetailsFromId(id)
                val payload = "PAYLOAD"

                doEbicsUploadTransaction(
                    client,
                    subscriberData,
                    "TSU",
                    payload.toByteArray(Charsets.UTF_8),
                    EbicsGenericOrderParams()
                )

                call.respondText(
                    "TST INITIALIZATION & TRANSACTION phases succeeded\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
            }

            post("/ebics/subscribers/{id}/sync") {
                val id = expectId(call.parameters["id"])
                val subscriberDetails = getSubscriberDetailsFromId(id)
                val hpbRequest = makeEbicsHpbRequest(subscriberDetails)
                val responseStr = client.postToBank(subscriberDetails.ebicsUrl, hpbRequest)

                val response = parseAndDecryptEbicsKeyManagementResponse(subscriberDetails, responseStr)
                val orderData =
                    response.orderData ?: throw NexusError(HttpStatusCode.InternalServerError, "HPB response has no order data")
                val hpbData = parseEbicsHpbOrder(orderData)

                // put bank's keys into database.
                transaction {
                    val subscriber = EbicsSubscriberEntity.findById(id) ?: throw NexusError(HttpStatusCode.BadRequest, "Invalid subscriber state")
                    subscriber.bankAuthenticationPublicKey = SerialBlob(hpbData.authenticationPubKey.encoded)
                    subscriber.bankEncryptionPublicKey = SerialBlob(hpbData.encryptionPubKey.encoded)
                }
                call.respondText("Bank keys stored in database\n", ContentType.Text.Plain, HttpStatusCode.OK)
                return@post
            }
        }
    }
    logger.info("Up and running")
    server.start(wait = true)
}
