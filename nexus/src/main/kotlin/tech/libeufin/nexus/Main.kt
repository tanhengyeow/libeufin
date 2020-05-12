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
import tech.libeufin.util.ebics_h004.EbicsResponse
import java.text.DateFormat
import java.util.zip.InflaterInputStream
import javax.crypto.EncryptedPrivateKeyInfo
import javax.sql.rowset.serial.SerialBlob

data class NexusError(val statusCode: HttpStatusCode, val reason: String) : Exception()
val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus")

suspend fun handleEbicsSendMSG(client: HttpClient, subscriber: EbicsClientSubscriberDetails, msg: String): String {
    when (msg.toUpperCase()) {
        "HIA" -> {
            val request = makeEbicsHiaRequest(subscriber)
            return client.postToBank(
                subscriber.ebicsUrl,
                request
            )
        }
        "INI" -> {
            val request = makeEbicsIniRequest(subscriber)
            return client.postToBank(
                subscriber.ebicsUrl,
                request
            )
        }
        "HPB" -> {
            /** should NOT put bank's keys into any table.  */
            val request = makeEbicsHpbRequest(subscriber)
            return client.postToBank(
                subscriber.ebicsUrl,
                request
            )
        }
        "HEV" -> {
            val request = makeEbicsHEVRequest(subscriber)
            return client.postToBank(subscriber.ebicsUrl, request)
        }
        else -> throw NexusError(
            HttpStatusCode.NotFound,
            "Message $msg not found"
        )
    }
}

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
                val preparedPayment = getPreparedPayment(body.uuid)
                if (preparedPayment.nexusUser.id.value != userId) throw NexusError(
                    HttpStatusCode.Forbidden,
                    "No rights over such payment"
                )
                if (preparedPayment.submitted) {
                    throw NexusError(
                        HttpStatusCode.PreconditionFailed,
                        "Payment ${body.uuid} was submitted already"
                    )
                }
                val pain001document = createPain001document(preparedPayment)
                if (body.transport != null) {
                    // type and name aren't null
                    when (body.transport.type) {
                        "ebics" -> {
                            submitPaymentEbics(
                                client, userId, body.transport.name, pain001document
                            )
                        }
                        else -> throw NexusError(
                            HttpStatusCode.NotFound,
                            "Transport type '${body.transport.type}' not implemented"
                        )
                    }
                } else {
                    // default to ebics and "first" transport from user
                    submitPaymentEbics(
                        client, userId, null, pain001document
                    )
                }
                preparedPayment.submitted = true
                call.respondText("Payment ${body.uuid} submitted")
                return@post
            }
            /**
             * Shows information about one particular prepared payment.
             */
            get("/bank-accounts/{accountid}/prepared-payments/{uuid}") {
                val userId = authenticateRequest(call.request.headers["Authorization"])
                val preparedPayment = getPreparedPayment(expectId(call.parameters["uuid"]))
                if (preparedPayment.nexusUser.id.value != userId) throw NexusError(
                    HttpStatusCode.Forbidden,
                    "No rights over such payment"
                )
                call.respond(
                    PaymentStatus(
                        uuid = preparedPayment.id.value,
                        submitted = preparedPayment.submitted,
                        creditorName = preparedPayment.creditorName,
                        creditorBic = preparedPayment.creditorBic,
                        creditorIban = preparedPayment.creditorIban,
                        amount = "${preparedPayment.sum}:${preparedPayment.currency}",
                        subject = preparedPayment.subject,
                        submissionDate = DateTime(preparedPayment.submissionDate).toDashedDate(),
                        preparationDate = DateTime(preparedPayment.preparationDate).toDashedDate()
                    )
                )
                return@get
            }
            /**
             * Adds a new prepared payment.
             */
            post("/bank-accounts/{accountid}/prepared-payments") {
                val userId = authenticateRequest(call.request.headers["Authorization"])
                val bankAccount = getBankAccount(userId, expectId(call.parameters["accountid"]))
                val body = call.receive<PreparedPaymentRequest>()
                val amount = parseAmount(body.amount)
                val paymentEntity = addPreparedPayment(
                    Pain001Data(
                        creditorIban = body.iban,
                        creditorBic = body.bic,
                        creditorName = body.name,
                        debitorAccount = bankAccount.id.value,
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
             *
             * NOTE: 'accountid' is not used.  Transaction are asked on
             * the basis of a transport subscriber (regardless of their
             * bank account details)
             */
            post("/bank-accounts/{accountid}/collected-transactions") {
                val userId = authenticateRequest(call.request.headers["Authorization"])
                val body = call.receive<CollectedTransaction>()
                if (body.transport != null) {
                    when (body.transport.type) {
                        "ebics" -> {
                            downloadAndPersistC5xEbics(
                                "C53",
                                client,
                                userId,
                                body.start,
                                body.end,
                                body.transport.name
                            )
                        }
                        else -> throw NexusError(
                            HttpStatusCode.BadRequest,
                            "Transport type '${body.transport.type}' not implemented"
                        )
                    }
                } else {
                    downloadAndPersistC5xEbics(
                        "C53",
                        client,
                        userId,
                        body.start,
                        body.end,
                        null
                    )
                }
                call.respondText("Collection performed")
                return@post
            }
            /**
             * Asks list of transactions ALREADY downloaded from the bank.
             */
            get("/bank-accounts/{accountid}/collected-transactions") {
                val userId = authenticateRequest(call.request.headers["Authorization"])
                val start = call.request.queryParameters["start"]
                val end = call.request.queryParameters["end"]
                val ret = Transactions()
                transaction {
                    RawBankTransactionEntity.find {
                        RawBankTransactionsTable.nexusUser eq userId and
                                RawBankTransactionsTable.bookingDate.between(
                                    parseDashedDate(start ?: "1970-01-01"),
                                    parseDashedDate(end ?: DateTime.now().toDashedDate())
                                )
                    }.forEach {
                        ret.transactions.add(
                            Transaction(
                                account = it.bankAccount.id.value,
                                counterpartBic = it.counterpartBic,
                                counterpartIban = it.counterpartIban,
                                counterpartName = it.counterpartName,
                                date = DateTime(it.bookingDate).toDashedDate(),
                                subject = it.unstructuredRemittanceInformation,
                                amount = "${it.currency}:${it.amount}"
                            )
                        )
                    }
                }
                return@get
            }
            /**
             * Adds a new bank transport.
             */
            post("/bank-transports") {
                val userId = authenticateRequest(call.request.headers["Authorization"])
                // user exists and is authenticated.
                val body = call.receive<BankTransport>()
                when (body.backup) {
                    is EbicsKeysBackupJson -> {
                        val (authKey, encKey, sigKey) = try {
                            Triple(
                                CryptoUtil.decryptKey(
                                    EncryptedPrivateKeyInfo(base64ToBytes(body.backup.authBlob)),
                                    body.backup.passphrase
                                ),
                                CryptoUtil.decryptKey(
                                    EncryptedPrivateKeyInfo(base64ToBytes(body.backup.encBlob)),
                                    body.backup.passphrase
                                ),
                                CryptoUtil.decryptKey(
                                    EncryptedPrivateKeyInfo(base64ToBytes(body.backup.sigBlob)),
                                    body.backup.passphrase
                                )
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                            logger.info("Restoring keys failed, probably due to wrong passphrase")
                            throw NexusError(
                                HttpStatusCode.BadRequest,
                                "Bad backup given"
                            )
                        }
                        logger.info("Restoring keys, creating new user: $userId")
                        try {
                            transaction {
                                EbicsSubscriberEntity.new(userId) {
                                    this.nexusUser = extractNexusUser(userId)
                                    ebicsURL = body.backup.ebicsURL
                                    hostID = body.backup.hostID
                                    partnerID = body.backup.partnerID
                                    userID = body.backup.userID
                                    signaturePrivateKey = SerialBlob(sigKey.encoded)
                                    encryptionPrivateKey = SerialBlob(encKey.encoded)
                                    authenticationPrivateKey = SerialBlob(authKey.encoded)
                                }
                            }
                        } catch (e: Exception) {
                            print(e)
                            call.respond(
                                NexusErrorJson("Could not store the new account into database")
                            )
                            return@post
                        }
                    }
                }
                when (body.new) {
                    is EbicsNewTransport -> {
                        val pairA = CryptoUtil.generateRsaKeyPair(2048)
                        val pairB = CryptoUtil.generateRsaKeyPair(2048)
                        val pairC = CryptoUtil.generateRsaKeyPair(2048)
                        transaction {
                            EbicsSubscriberEntity.new {
                                nexusUser = extractNexusUser(userId)
                                ebicsURL = body.new.ebicsURL
                                hostID = body.new.hostID
                                partnerID = body.new.partnerID
                                userID = body.new.userID
                                systemID = body.new.systemID
                                signaturePrivateKey = SerialBlob(pairA.private.encoded)
                                encryptionPrivateKey = SerialBlob(pairB.private.encoded)
                                authenticationPrivateKey = SerialBlob(pairC.private.encoded)
                            }
                        }
                        call.respondText(
                            "EBICS user successfully created",
                            ContentType.Text.Plain,
                            HttpStatusCode.OK
                        )
                        return@post
                    }
                }
                call.respond(
                    HttpStatusCode.BadRequest,
                    "Neither backup nor new transport given in request"
                )
                return@post
            }
            /**
             * Sends to the bank a message "MSG" according to the transport
             * "transportName".  Does not modify any DB table.
             */
            post("/bank-transports/send{MSG}") {
                val userId = authenticateRequest(call.request.headers["Authorization"])
                val body = call.receive<Transport>()
                when (body.type) {
                    "ebics" -> {
                        val response = handleEbicsSendMSG(
                            client,
                            getEbicsSubscriberDetails(userId, body.name),
                            expectId(call.parameters["MSG"]))
                        call.respondText(response)
                    }
                    else -> throw NexusError(
                        HttpStatusCode.NotImplemented,
                        "Transport '${body.type}' not implemented.  Use 'ebics'"
                    )
                }
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