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

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
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
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.util.*
import tech.libeufin.util.CryptoUtil.hashpw
import tech.libeufin.util.ebics_h004.HTDResponseOrderData
import java.util.*
import java.util.zip.InflaterInputStream
import javax.crypto.EncryptedPrivateKeyInfo
import javax.sql.rowset.serial.SerialBlob

data class NexusError(val statusCode: HttpStatusCode, val reason: String) :
    Exception("${reason} (HTTP status $statusCode)")

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus")

class NexusCommand : CliktCommand() {
    override fun run() = Unit
}

class Serve : CliktCommand("Run nexus HTTP server") {
    override fun run() {
        serverMain()
    }
}


class Superuser : CliktCommand("Add superuser or change pw") {
    private val username by argument()
    private val password by option().prompt(requireConfirmation = true, hideInput = true)
    override fun run() {
        dbCreateTables()
        transaction {
            val hashedPw = hashpw(password)
            val user = NexusUserEntity.findById(username)
            if (user == null) {
                NexusUserEntity.new(username) {
                    this.passwordHash = hashedPw
                    this.superuser = true
                }
            } else {
                if (!user.superuser) {
                    println("Can only change password for superuser with this command.")
                    throw ProgramResult(1)
                }
                user.passwordHash = hashedPw
            }
        }
    }
}

fun main(args: Array<String>) {
    NexusCommand()
        .subcommands(Serve(), Superuser())
        .main(args)
}

suspend inline fun <reified T : Any> ApplicationCall.receiveJson(): T {
    try {
        return this.receive<T>()
    } catch (e: MissingKotlinParameterException) {
        throw NexusError(HttpStatusCode.BadRequest, "Missing value for ${e.pathReference}")
    } catch (e: MismatchedInputException) {
        throw NexusError(HttpStatusCode.BadRequest, "Invalid value for ${e.pathReference}")
    }
}

fun createEbicsBankConnectionFromBackup(
    bankConnectionName: String,
    user: NexusUserEntity,
    passphrase: String?,
    backup: JsonNode
) {
    if (passphrase === null) {
        throw NexusError(HttpStatusCode.BadRequest, "EBICS backup needs passphrase")
    }
    val bankConn = NexusBankConnectionEntity.new(bankConnectionName) {
        owner = user
        type = "ebics"
    }
    val ebicsBackup = jacksonObjectMapper().treeToValue(backup, EbicsKeysBackupJson::class.java)
    val (authKey, encKey, sigKey) = try {
        Triple(
            CryptoUtil.decryptKey(
                EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.authBlob)),
                passphrase
            ),
            CryptoUtil.decryptKey(
                EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.encBlob)),
                passphrase
            ),
            CryptoUtil.decryptKey(
                EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.sigBlob)),
                passphrase
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
    try {
        EbicsSubscriberEntity.new {
            ebicsURL = ebicsBackup.ebicsURL
            hostID = ebicsBackup.hostID
            partnerID = ebicsBackup.partnerID
            userID = ebicsBackup.userID
            signaturePrivateKey = SerialBlob(sigKey.encoded)
            encryptionPrivateKey = SerialBlob(encKey.encoded)
            authenticationPrivateKey = SerialBlob(authKey.encoded)
            nexusBankConnection = bankConn
        }
    } catch (e: Exception) {
        throw NexusError(
            HttpStatusCode.BadRequest,
            "exception: $e"
        )
    }
    return
}

fun createEbicsBankConnection(bankConnectionName: String, user: NexusUserEntity, data: JsonNode) {
    val bankConn = NexusBankConnectionEntity.new(bankConnectionName) {
        owner = user
        type = "ebics"
    }
    val data = jacksonObjectMapper().treeToValue(data, EbicsNewTransport::class.java)
    val pairA = CryptoUtil.generateRsaKeyPair(2048)
    val pairB = CryptoUtil.generateRsaKeyPair(2048)
    val pairC = CryptoUtil.generateRsaKeyPair(2048)
    EbicsSubscriberEntity.new {
        ebicsURL = data.ebicsURL
        hostID = data.hostID
        partnerID = data.partnerID
        userID = data.userID
        systemID = data.systemID
        signaturePrivateKey = SerialBlob(pairA.private.encoded)
        encryptionPrivateKey = SerialBlob(pairB.private.encoded)
        authenticationPrivateKey = SerialBlob(pairC.private.encoded)
        nexusBankConnection = bankConn
    }
}

fun requireBankConnection(call: ApplicationCall, parameterKey: String): NexusBankConnectionEntity {
    val name = call.parameters[parameterKey]
    if (name == null) {
        throw NexusError(HttpStatusCode.InternalServerError, "no parameter for bank connection")
    }
    val conn = NexusBankConnectionEntity.findById(name)
    if (conn == null) {
        throw NexusError(HttpStatusCode.NotFound, "bank connection '$name' not found")
    }
    return conn
}


fun serverMain() {
    dbCreateTables()
    val client = HttpClient {
        expectSuccess = false // this way, it does not throw exceptions on != 200 responses.
    }
    val server = embeddedServer(Netty, port = 5001) {

        install(CallLogging) {
            this.level = Level.DEBUG
            this.logger = tech.libeufin.nexus.logger
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

        /**
         * Allow request body compression.  Needed by Taler.
         */
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
                val ret = transaction {
                    val currentUser = authenticateRequest(call.request)
                    UserResponse(
                        username = currentUser.id.value,
                        superuser = currentUser.superuser
                    )
                }
                call.respond(HttpStatusCode.OK, ret)
                return@get
            }

            get("/users") {
                val users = transaction {
                    transaction {
                        NexusUserEntity.all().map {
                            UserInfo(it.id.value, it.superuser)
                        }
                    }
                }
                val usersResp = UsersResponse(users)
                call.respond(HttpStatusCode.OK, usersResp)
                return@get
            }

            /**
             * Add a new ordinary user in the system (requires superuser privileges)
             */
            post("/users") {
                val body = call.receiveJson<User>()
                transaction {
                    val currentUser = authenticateRequest(call.request)
                    if (!currentUser.superuser) {
                        throw NexusError(HttpStatusCode.Forbidden, "only superuser can do that")
                    }
                    NexusUserEntity.new(body.username) {
                        passwordHash = hashpw(body.password)
                        superuser = false
                    }
                }
                call.respondText(
                    "New NEXUS user registered. ID: ${body.username}",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
                return@post
            }

            get("/bank-connection-protocols") {
                call.respond(HttpStatusCode.OK, BankProtocolsResponse(listOf("ebics", "loopback")))
                return@get
            }

            post("/bank-connection-protocols/ebics/test-host") {
                val r = call.receiveJson<EbicsHostTestRequest>()
                val qr = doEbicsHostVersionQuery(client, r.ebicsBaseUrl, r.ebicsHostId)
                call.respond(HttpStatusCode.OK, qr)
                return@post
            }

            /**
             * Shows the bank accounts belonging to the requesting user.
             */
            get("/bank-accounts") {
                val bankAccounts = BankAccounts()
                transaction {
                    authenticateRequest(call.request)
                    // FIXME(dold): Only return accounts the user has at least read access to?
                    BankAccountEntity.all().forEach {
                        bankAccounts.accounts.add(BankAccount(it.accountHolder, it.iban, it.bankCode, it.id.value))
                    }
                }
                call.respond(bankAccounts)
                return@get
            }

            /**
             * Submit one particular payment to the bank.
             */
            post("/bank-accounts/{accountid}/prepared-payments/{uuid}/submit") {
                val uuid = ensureNonNull(call.parameters["uuid"])
                val accountId = ensureNonNull(call.parameters["accountid"])
                val res = transaction {
                    val user = authenticateRequest(call.request)
                    val preparedPayment = getPreparedPayment(uuid)
                    if (preparedPayment.submitted) {
                        throw NexusError(
                            HttpStatusCode.PreconditionFailed,
                            "Payment ${uuid} was submitted already"
                        )
                    }
                    val bankAccount = BankAccountEntity.findById(accountId)
                    if (bankAccount == null) {
                        throw NexusError(HttpStatusCode.NotFound, "unknown bank account")
                    }
                    val defaultBankConnection = bankAccount.defaultBankConnection
                    if (defaultBankConnection == null) {
                        throw NexusError(HttpStatusCode.NotFound, "needs a default connection")
                    }
                    val subscriberDetails = getEbicsSubscriberDetails(user.id.value, defaultBankConnection.id.value)
                    return@transaction object {
                        val pain001document = createPain001document(preparedPayment)
                        val bankConnectionType = defaultBankConnection.type
                        val subscriberDetails = subscriberDetails
                    }
                }
                // type and name aren't null
                when (res.bankConnectionType) {
                    "ebics" -> {
                        logger.debug("Uploading PAIN.001: ${res.pain001document}")
                        doEbicsUploadTransaction(
                            client,
                            res.subscriberDetails,
                            "CCT",
                            res.pain001document.toByteArray(Charsets.UTF_8),
                            EbicsStandardOrderParams()
                        )
                    }
                    else -> throw NexusError(
                        HttpStatusCode.NotFound,
                        "Transport type '${res.bankConnectionType}' not implemented"
                    )
                }
                transaction {
                    val preparedPayment = getPreparedPayment(uuid)
                    preparedPayment.submitted = true
                }
                call.respondText("Payment ${uuid} submitted")
                return@post
            }

            /**
             * Shows information about one particular prepared payment.
             */
            get("/bank-accounts/{accountid}/prepared-payments/{uuid}") {
                val res = transaction {
                    val user = authenticateRequest(call.request)
                    val preparedPayment = getPreparedPayment(ensureNonNull(call.parameters["uuid"]))
                    return@transaction object {
                        val preparedPayment = preparedPayment
                    }
                }
                call.respond(
                    PaymentStatus(
                        uuid = res.preparedPayment.id.value,
                        submitted = res.preparedPayment.submitted,
                        creditorName = res.preparedPayment.creditorName,
                        creditorBic = res.preparedPayment.creditorBic,
                        creditorIban = res.preparedPayment.creditorIban,
                        amount = "${res.preparedPayment.sum}:${res.preparedPayment.currency}",
                        subject = res.preparedPayment.subject,
                        submissionDate = DateTime(res.preparedPayment.submissionDate).toDashedDate(),
                        preparationDate = DateTime(res.preparedPayment.preparationDate).toDashedDate()
                    )
                )
                return@get
            }
            /**
             * Adds a new prepared payment.
             */
            post("/bank-accounts/{accountid}/prepared-payments") {
                val body = call.receive<PreparedPaymentRequest>()
                val accountId = ensureNonNull(call.parameters["accountid"])
                val res = transaction {
                    authenticateRequest(call.request)
                    val bankAccount = BankAccountEntity.findById(accountId)
                    if (bankAccount == null) {
                        throw NexusError(HttpStatusCode.NotFound, "unknown bank account")
                    }
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
                        bankAccount
                    )
                    return@transaction object {
                        val uuid = paymentEntity.id.value
                    }
                }
                call.respond(
                    HttpStatusCode.OK,
                    PreparedPaymentResponse(uuid = res.uuid)
                )
                return@post
            }

            /**
             * Downloads new transactions from the bank.
             */
            post("/bank-accounts/{accountid}/fetch-transactions") {
                val accountid = call.parameters["accountid"]
                if (accountid == null) {
                    throw NexusError(
                        HttpStatusCode.BadRequest,
                        "Account id missing"
                    )
                }
                val res = transaction {
                    val user = authenticateRequest(call.request)
                    val acct = BankAccountEntity.findById(accountid)
                    if (acct == null) {
                        throw NexusError(
                            HttpStatusCode.NotFound,
                            "Account not found"
                        )
                    }
                    val conn = acct.defaultBankConnection
                    if (conn == null) {
                        throw NexusError(
                            HttpStatusCode.BadRequest,
                            "No default bank connection (explicit connection not yet supported)"
                        )
                    }
                    val subscriberDetails = getEbicsSubscriberDetails(user.id.value, conn.id.value)
                    return@transaction object {
                        val connectionType = conn.type
                        val connectionName = conn.id.value
                        val userId = user.id.value
                        val subscriberDetails = subscriberDetails
                    }
                }
                val body = call.receive<CollectedTransaction>()
                when (res.connectionType) {
                    "ebics" -> {
                        downloadAndPersistC5xEbics(
                            "C53",
                            client,
                            res.userId,
                            body.start,
                            body.end,
                            res.subscriberDetails
                        )
                    }
                    else -> throw NexusError(
                        HttpStatusCode.BadRequest,
                        "Connection type '${res.connectionType}' not implemented"
                    )
                }
                call.respondText("Collection performed")
                return@post
            }

            /**
             * Asks list of transactions ALREADY downloaded from the bank.
             */
            get("/bank-accounts/{accountid}/transactions") {
                val bankAccount = expectNonNull(call.parameters["accountid"])
                val start = call.request.queryParameters["start"]
                val end = call.request.queryParameters["end"]
                val ret = Transactions()
                transaction {
                    val userId = transaction { authenticateRequest(call.request).id.value }
                    RawBankTransactionEntity.find {
                        RawBankTransactionsTable.nexusUser eq userId and
                                (RawBankTransactionsTable.bankAccount eq bankAccount) and
                                RawBankTransactionsTable.bookingDate.between(
                                    parseDashedDate(start ?: "1970-01-01").millis,
                                    parseDashedDate(end ?: DateTime.now().toDashedDate()).millis
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
                call.respond(ret)
                return@get
            }

            /**
             * Adds a new bank transport.
             */
            post("/bank-connections") {
                // user exists and is authenticated.
                val body = call.receive<CreateBankConnectionRequestJson>()
                transaction {
                    val user = authenticateRequest(call.request)
                    when (body) {
                        is CreateBankConnectionFromBackupRequestJson -> {
                            createEbicsBankConnectionFromBackup(body.name, user, body.passphrase, body.data)
                        }
                        is CreateBankConnectionFromNewRequestJson -> {
                            createEbicsBankConnection(body.name, user, body.data)
                        }
                    }
                }
//                val bankConnectionName = body.name
//                val bankConnectionType = body.get("type").textValue()
//                transaction {
//                    val user = authenticateRequest(call.request)
//                    when (bankConnectionType) {
//                        "ebics" -> {
//                            createEbicsBankConnection(bankConnectionName, user, body)
//                        }
//                        else -> {
//                            throw NexusError(
//                                HttpStatusCode.BadRequest,
//                                "Invalid bank connection type '${bankConnectionType}'"
//                            )
//                        }
//                    }
//                }
                call.respond(object {})
            }

            get("/bank-connections") {
                val connList = mutableListOf<BankConnectionInfo>()
                transaction {
                    NexusBankConnectionEntity.all().forEach {
                        connList.add(BankConnectionInfo(it.id.value, it.type))
                    }
                }
                call.respond(BankConnectionsList(connList))
            }

            post("/bank-connections/{connid}/connect") {
                throw NotImplementedError()
            }

            post("/bank-connections/{connid}/ebics/send-ini") {
                val subscriber = transaction {
                    val user = authenticateRequest(call.request)
                    val conn = requireBankConnection(call, "connid")
                    if (conn.type != "ebics") {
                        throw NexusError(
                            HttpStatusCode.BadRequest,
                            "bank connection is not of type 'ebics' (but '${conn.type}')"
                        )
                    }
                    getEbicsSubscriberDetails(user.id.value, conn.id.value)
                }
                val resp = doEbicsIniRequest(client, subscriber)
                call.respond(resp)
            }

            post("/bank-connections/{connid}/ebics/send-hia") {
                val subscriber = transaction {
                    val user = authenticateRequest(call.request)
                    val conn = requireBankConnection(call, "connid")
                    if (conn.type != "ebics") {
                        throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
                    }
                    getEbicsSubscriberDetails(user.id.value, conn.id.value)
                }
                val resp = doEbicsHiaRequest(client, subscriber)
                call.respond(resp)
            }

            post("/bank-connections/{connid}/ebics/send-hpb") {
                val subscriberDetails = transaction {
                    val user = authenticateRequest(call.request)
                    val conn = requireBankConnection(call, "connid")
                    if (conn.type != "ebics") {
                        throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
                    }
                    getEbicsSubscriberDetails(user.id.value, conn.id.value)
                }
                val hpbData = doEbicsHpbRequest(client, subscriberDetails)
                transaction {
                    val conn = requireBankConnection(call, "connid")
                    val subscriber =
                        EbicsSubscriberEntity.find { EbicsSubscribersTable.nexusBankConnection eq conn.id }.first()
                    subscriber.bankAuthenticationPublicKey = SerialBlob(hpbData.authenticationPubKey.encoded)
                    subscriber.bankEncryptionPublicKey = SerialBlob(hpbData.encryptionPubKey.encoded)
                }
                call.respond(object {})
            }

            /**
             * Directly import accounts.  Used for testing.
             */
            post("/bank-connections/{connid}/ebics/import-accounts") {
                val subscriberDetails = transaction {
                    val user = authenticateRequest(call.request)
                    val conn = requireBankConnection(call, "connid")
                    if (conn.type != "ebics") {
                        throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
                    }
                    getEbicsSubscriberDetails(user.id.value, conn.id.value)
                }
                val response = doEbicsDownloadTransaction(
                    client, subscriberDetails, "HTD", EbicsStandardOrderParams()
                )
                when (response) {
                    is EbicsDownloadBankErrorResult -> {
                        throw NexusError(
                            HttpStatusCode.BadGateway,
                            response.returnCode.errorCode
                        )
                    }
                    is EbicsDownloadSuccessResult -> {
                        val payload = XMLUtil.convertStringToJaxb<HTDResponseOrderData>(
                            response.orderData.toString(Charsets.UTF_8)
                        )
                        transaction {
                            val conn = requireBankConnection(call, "connid")
                            payload.value.partnerInfo.accountInfoList?.forEach {
                                val bankAccount = BankAccountEntity.new(id = it.id) {
                                    accountHolder = it.accountHolder ?: "NOT-GIVEN"
                                    iban = extractFirstIban(it.accountNumberList)
                                        ?: throw NexusError(HttpStatusCode.NotFound, reason = "bank gave no IBAN")
                                    bankCode = extractFirstBic(it.bankCodeList) ?: throw NexusError(
                                        HttpStatusCode.NotFound,
                                        reason = "bank gave no BIC"
                                    )
                                    defaultBankConnection = conn
                                }
                            }
                        }
                        response.orderData.toString(Charsets.UTF_8)
                    }
                }
                call.respond(object {})
            }

            post("/bank-connections/{connid}/ebics/download/{msgtype}") {
                val orderType = requireNotNull(call.parameters["msgtype"]).toUpperCase(Locale.ROOT)
                if (orderType.length != 3) {
                    throw NexusError(HttpStatusCode.BadRequest, "ebics order type must be three characters")
                }
                val paramsJson = call.receive<EbicsStandardOrderParamsJson>()
                val orderParams = paramsJson.toOrderParams()
                val subscriberDetails = transaction {
                    val user = authenticateRequest(call.request)
                    val conn = requireBankConnection(call, "connid")
                    if (conn.type != "ebics") {
                        throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
                    }
                    getEbicsSubscriberDetails(user.id.value, conn.id.value)
                }
                val response = doEbicsDownloadTransaction(
                    client,
                    subscriberDetails,
                    orderType,
                    orderParams
                )
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