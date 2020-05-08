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
import io.ktor.features.*
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.ApplicationReceivePipeline
import io.ktor.request.ApplicationReceiveRequest
import io.ktor.request.receive
import io.ktor.request.uri
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.io.ByteReadChannel
import kotlinx.coroutines.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.io.jvm.javaio.toInputStream
import kotlinx.io.core.ExperimentalIoApi
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.HTDResponseOrderData
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.InflaterInputStream
import javax.crypto.EncryptedPrivateKeyInfo
import javax.sql.rowset.serial.SerialBlob

data class NexusError(val statusCode: HttpStatusCode, val reason: String) : Exception()
val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus")

@ExperimentalIoApi
@KtorExperimentalAPI
fun main() {
    dbCreateTables()
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
            exception<NexusError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText(
                    cause.reason,
                    ContentType.Text.Plain,
                    cause.statusCode
                )
            }
            exception<UtilError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText(
                    cause.reason,
                    ContentType.Text.Plain,
                    cause.statusCode
                )
            }
            exception<Exception> { cause ->
                logger.error("Uncaught exception while handling '${call.request.uri}'", cause)
                logger.error(cause.toString())
                call.respondText(
                    "Internal server error",
                    ContentType.Text.Plain,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        intercept(ApplicationCallPipeline.Fallback) {
            if (this.call.response.status() == null) {
                call.respondText("Not found (no route matched).\n", ContentType.Text.Plain, HttpStatusCode.NotFound)
                return@intercept finish()
            }
        }
        receivePipeline.intercept(ApplicationReceivePipeline.Before) {
            if (this.context.request.headers["Content-Encoding"] == "deflate") {
                logger.debug("About to inflate received data")
                val deflated = this.subject.value as ByteReadChannel
                val inflated = InflaterInputStream(deflated.toInputStream())
                proceedWith(ApplicationReceiveRequest(this.subject.typeInfo, inflated.toByteReadChannel()))
                return@intercept
            }
            proceed()
            return@intercept
        }
        routing {
            /**
             * Shows information about the requesting user.
             */
            get("/user") {
                val userId = authenticateRequest(call.request.headers["Authorization"])
                val ret = transaction {
                    NexusUserEntity.findById(userId)
                    UserResponse(
                        username = userId,
                        superuser = userId.equals("admin")
                    )
                }
                call.respond(HttpStatusCode.OK, ret)
                return@get
            }
            /**
             * Add a new ordinary user in the system (requires "admin" privileges)
             */
            post("/users") {
                authenticateAdminRequest(call.request.headers["Authorization"])
                val body = call.receive<User>()
                if (body.username.equals("admin")) throw NexusError(
                    HttpStatusCode.Forbidden,
                    "'admin' is a reserved username"
                )
                transaction {
                    NexusUserEntity.new(body.username) {
                        password = SerialBlob(CryptoUtil.hashStringSHA256(body.password))
                    }
                }
                call.respondText(
                    "New NEXUS user registered. ID: ${body.username}",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }
            /**
             * Shows the bank accounts belonging to the requesting user.
             */
            get("/bank-accounts") {
                val userId = authenticateRequest(call.request.headers["Authorization"])
                val bankAccounts = BankAccounts()
                getBankAccountsFromNexusUserId(userId).forEach {
                    bankAccounts.accounts.add(
                        BankAccount(
                            holder = it.accountHolder,
                            iban = it.iban,
                            bic = it.bankCode,
                            account = it.id.value
                        )
                    )
                }
                return@get
            }
            /**
             * Submit one particular payment at the bank.
             */
            post("/bank-accounts/{accountid}/prepared-payments/submit") {
                val userId = authenticateRequest(call.request.headers["Authorization"])
                val body = call.receive<SubmitPayment>()
                val preparedPayment = transaction {
                    Pain001Entity.findById(body.uuid)
                } ?: throw NexusError(
                    HttpStatusCode.NotFound,
                    "Could not find prepared payment: ${body.uuid}"
                )
                if (preparedPayment.submitted) {
                    throw NexusError(
                        HttpStatusCode.PreconditionFailed,
                        "Payment ${body.uuid} was submitted already"
                    )
                }
                val pain001document = createPain001document(preparedPayment)
                when (body.transport) {
                    "ebics" -> {
                        val subscriberDetails = getSubscriberDetailsFromNexusUserId(userId)
                        logger.debug("Uploading PAIN.001: ${pain001document}")
                        doEbicsUploadTransaction(
                            client,
                            subscriberDetails,
                            "CCT",
                            pain001document.toByteArray(Charsets.UTF_8),
                            EbicsStandardOrderParams()
                        )
                        /** mark payment as 'submitted' */
                        transaction {
                            val payment = Pain001Entity.findById(body.uuid) ?: throw NexusError(
                                HttpStatusCode.InternalServerError,
                                "Severe internal error: could not find payment in DB after having submitted it to the bank"
                            )
                            payment.submitted = true
                        }
                        call.respondText(
                            "CCT message submitted to the bank",
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                    }
                    else -> throw NexusError(
                        HttpStatusCode.NotImplemented,
                        "Bank transport ${body.transport} is not implemented"
                    )
                }
                return@post
            }
            /**
             * Shows information about one particular prepared payment.
             */
            get("/bank-accounts/{accountid}/prepared-payments/{uuid}") {
                return@get
            }
            /**
             * Adds a new prepared payment.
             */
            post("/bank-accounts/{accountid}/prepared-payments") {
                val userId = authenticateRequest(call.request.headers["Authorization"])
                val body = call.receive<PreparedPaymentRequest>()
                val debitBankAccount = getBankAccount(expectId(call.parameters["accountid"]))
                val amount = parseAmount(body.amount)
                val paymentEntity = createPain001entity(
                    Pain001Data(
                        creditorIban = body.iban,
                        creditorBic = body.bic,
                        creditorName = body.name,
                        debitorIban = debitBankAccount.iban,
                        debitorBic = debitBankAccount.bankCode,
                        debitorName = debitBankAccount.accountHolder,
                        sum = amount.amount,
                        currency = amount.currency,
                        subject = body.subject
                    ),
                    extractNexusUser(userId)
                )
                call.respond(
                    HttpStatusCode.OK,
                    PreparedPaymentResponse(uuid = paymentEntity.id.value)
                )
                return@post
            }
            /**
             * Downloads new transactions from the bank.
             */
            post("/bank-accounts/{accountid}/collected-transactions") {
                val userId = authenticateRequest(call.request.headers["Authorization"])
                val body = call.receive<CollectedTransaction>()
                when (body.transport) {
                    "ebics" -> {
                        val orderParams = EbicsStandardOrderParamsJson(
                            EbicsDateRangeJson(
                                body.start,
                                body.end
                            )
                        ).toOrderParams()
                        val subscriberData = getSubscriberDetailsFromNexusUserId(userId)
                        when (val response = doEbicsDownloadTransaction(client, subscriberData, "C53", orderParams)) {
                            is EbicsDownloadSuccessResult -> {
                                /**
                                 * The current code is _heavily_ dependent on the way GLS returns
                                 * data.  For example, GLS makes one ZIP entry for each "Ntry" element
                                 * (a bank transfer), but per the specifications one bank can choose to
                                 * return all the "Ntry" elements into one single ZIP entry, or even unzipped
                                 * at all.
                                 */
                                response.orderData.unzipWithLambda {
                                    logger.debug("C53 entry: ${it.second}")
                                    val fileName = it.first
                                    val camt53doc = XMLUtil.parseStringIntoDom(it.second)
                                    transaction {
                                        RawBankTransactionEntity.new {
                                            sourceFileName = fileName
                                            unstructuredRemittanceInformation = camt53doc.pickString("//*[local-name()='Ntry']//*[local-name()='Ustrd']")
                                            transactionType = camt53doc.pickString("//*[local-name()='Ntry']//*[local-name()='CdtDbtInd']")
                                            currency = camt53doc.pickString("//*[local-name()='Ntry']//*[local-name()='Amt']/@Ccy")
                                            amount = camt53doc.pickString("//*[local-name()='Ntry']//*[local-name()='Amt']")
                                            status = camt53doc.pickString("//*[local-name()='Ntry']//*[local-name()='Sts']")
                                            bookingDate = parseDashedDate(camt53doc.pickString("//*[local-name()='BookgDt']//*[local-name()='Dt']")).millis
                                            nexusUser = extractNexusUser(userId)
                                            creditorName = camt53doc.pickString("//*[local-name()='RltdPties']//*[local-name()='Dbtr']//*[local-name()='Nm']")
                                            creditorIban = camt53doc.pickString("//*[local-name()='CdtrAcct']//*[local-name()='IBAN']")
                                            debitorName = camt53doc.pickString("//*[local-name()='RltdPties']//*[local-name()='Dbtr']//*[local-name()='Nm']")
                                            debitorIban = camt53doc.pickString("//*[local-name()='DbtrAcct']//*[local-name()='IBAN']")
                                            counterpartBic = camt53doc.pickString("//*[local-name()='RltdAgts']//*[local-name()='BIC']")
                                        }
                                    }
                                }
                                call.respondText(
                                    "C53 data persisted into the database (WIP).",
                                    ContentType.Text.Plain,
                                    HttpStatusCode.OK
                                )
                            }
                            is EbicsDownloadBankErrorResult -> {
                                call.respond(
                                    HttpStatusCode.BadGateway,
                                    EbicsErrorJson(
                                        EbicsErrorDetailJson(
                                            "bankError",
                                            response.returnCode.errorCode
                                        )
                                    )
                                )
                            }
                        }
                    }
                    else -> throw NexusError(
                        HttpStatusCode.NotImplemented,
                        "Bank transport ${body.transport} is not implemented"
                    )
                }
                return@post
            }
            /**
             * Queries list of transactions ALREADY downloaded from the bank.
             */
            get("/bank-accounts/{accountid}/collected-transactions") {
                return@get
            }
            /**
             * Adds a new bank transport.
             */
            post("/bank-transports") {
                return@post
            }
            /**
             * Sends to the bank a message "MSG" according to the transport
             * "transportName".  Does not alterate any DB table.
             */
            post("/bank-transports/{transportName}/send{MSG}") {
                return@post
            }
            /**
             * Sends the bank a message "MSG" according to the transport
             * "transportName".  DOES alterate DB tables.
             */
            post("/bank-transports/{transportName}/sync{MSG}") {
                return@post
            }

            /**
             * Hello endpoint.
             */
            get("/") {
                call.respondText("Hello by nexus!\n")
                return@get
            }
        }
    }
    logger.info("Up and running")
    server.start(wait = true)
}