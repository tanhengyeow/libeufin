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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
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
import io.ktor.request.*
import io.ktor.response.respond
import io.ktor.response.respondBytes
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.nexus.ebics.*
import tech.libeufin.util.*
import tech.libeufin.util.CryptoUtil.hashpw
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder
import java.time.Duration
import java.util.zip.InflaterInputStream

data class NexusError(val statusCode: HttpStatusCode, val reason: String) :
    Exception("$reason (HTTP status $statusCode)")

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus")

class NexusCommand : CliktCommand() {
    override fun run() = Unit
}

class Serve : CliktCommand("Run nexus HTTP server") {
    private val dbName by option().default("libeufin-nexus.sqlite3")
    override fun run() {
        serverMain(dbName)
    }
}

class Superuser : CliktCommand("Add superuser or change pw") {
    private val dbName by option().default("libeufin-nexus.sqlite3")
    private val username by argument()
    private val password by option().prompt(requireConfirmation = true, hideInput = true)
    override fun run() {
        dbCreateTables(dbName)
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

/**
 * Test HTTP basic auth.  Throws error if password is wrong,
 * and makes sure that the user exists in the system.
 *
 * @param authorization the Authorization:-header line.
 * @return user id
 */
fun authenticateRequest(request: ApplicationRequest): NexusUserEntity {
    val authorization = request.headers["Authorization"]
    val headerLine = if (authorization == null) throw NexusError(
        HttpStatusCode.BadRequest, "Authentication:-header line not found"
    ) else authorization
    val (username, password) = extractUserAndPassword(headerLine)
    val user = NexusUserEntity.find {
        NexusUsersTable.id eq username
    }.firstOrNull()
    if (user == null) {
        throw NexusError(HttpStatusCode.Unauthorized, "Unknown user '$username'")
    }
    if (!CryptoUtil.checkpw(password, user.passwordHash)) {
        throw NexusError(HttpStatusCode.Forbidden, "Wrong password")
    }
    return user
}


fun createLoopbackBankConnection(bankConnectionName: String, user: NexusUserEntity, data: JsonNode) {
    val bankConn = NexusBankConnectionEntity.new(bankConnectionName) {
        owner = user
        type = "loopback"
    }
    val bankAccount = jacksonObjectMapper().treeToValue(data, BankAccount::class.java)
    NexusBankAccountEntity.new(bankAccount.account) {
        iban = bankAccount.iban
        bankCode = bankAccount.bic
        accountHolder = bankAccount.holder
        defaultBankConnection = bankConn
        highestSeenBankMessageId = 0
    }
}

fun createEbicsBankConnection(bankConnectionName: String, user: NexusUserEntity, data: JsonNode) {
    val bankConn = NexusBankConnectionEntity.new(bankConnectionName) {
        owner = user
        type = "ebics"
    }
    val newTransportData = jacksonObjectMapper().treeToValue(data, EbicsNewTransport::class.java)
    val pairA = CryptoUtil.generateRsaKeyPair(2048)
    val pairB = CryptoUtil.generateRsaKeyPair(2048)
    val pairC = CryptoUtil.generateRsaKeyPair(2048)
    EbicsSubscriberEntity.new {
        ebicsURL = newTransportData.ebicsURL
        hostID = newTransportData.hostID
        partnerID = newTransportData.partnerID
        userID = newTransportData.userID
        systemID = newTransportData.systemID
        signaturePrivateKey = ExposedBlob((pairA.private.encoded))
        encryptionPrivateKey = ExposedBlob((pairB.private.encoded))
        authenticationPrivateKey = ExposedBlob((pairC.private.encoded))
        nexusBankConnection = bankConn
        ebicsIniState = EbicsInitState.NOT_SENT
        ebicsHiaState = EbicsInitState.NOT_SENT
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

fun ApplicationRequest.hasBody(): Boolean {
    if (this.isChunked()) {
        return true
    }
    val contentLengthHeaderStr = this.headers["content-length"]
    if (contentLengthHeaderStr != null) {
        try {
            val cl = contentLengthHeaderStr.toInt()
            return cl != 0
        } catch (e: NumberFormatException) {
            return false
        }
    }
    return false
}

inline fun reportAndIgnoreErrors(f: () -> Unit) {
    try {
        f()
    } catch (e: java.lang.Exception) {
        logger.error("ignoring exception", e)
    }
}

fun moreFrequentBackgroundTasks(httpClient: HttpClient) {
    GlobalScope.launch {
        while (true) {
            logger.debug("Running more frequent background jobs")
            reportAndIgnoreErrors {
                downloadTalerFacadesTransactions(
                    httpClient,
                    FetchSpecLatestJson(FetchLevel.ALL, null)
                )
            }
            // FIXME: should be done automatically after raw ingestion
            reportAndIgnoreErrors { ingestTalerTransactions() }
            reportAndIgnoreErrors { submitPreparedPaymentsViaEbics() }
            logger.debug("More frequent background jobs done")
            delay(Duration.ofSeconds(1))
        }
    }
}

fun lessFrequentBackgroundTasks(httpClient: HttpClient) {
    GlobalScope.launch {
        while (true) {
            logger.debug("Less frequent background job")
            try {
                //downloadTalerFacadesTransactions(httpClient, "C53")
            } catch (e: Exception) {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                e.printStackTrace(pw)
                logger.info("==== Less frequent background task exception ====\n${sw}======")
            }
            delay(Duration.ofSeconds(10))
        }
    }
}

/** Crawls all the facades, and requests history for each of its creators. */
suspend fun downloadTalerFacadesTransactions(httpClient: HttpClient, fetchSpec: FetchSpecJson) {
    val work = mutableListOf<Pair<String, String>>()
    transaction {
        TalerFacadeStateEntity.all().forEach {
            logger.debug("Fetching history for facade: ${it.id.value}, bank account: ${it.bankAccount}")
            work.add(Pair(it.facade.creator.id.value, it.bankAccount))
        }
    }
    work.forEach {
        fetchTransactionsInternal(
            client = httpClient,
            fetchSpec = fetchSpec,
            userId = it.first,
            accountid = it.second
        )
    }
}

fun <T> expectNonNull(param: T?): T {
    return param ?: throw EbicsProtocolError(
        HttpStatusCode.BadRequest,
        "Non-null value expected."
    )
}

fun ApplicationCall.expectUrlParameter(name: String): String {
    return this.request.queryParameters[name]
        ?: throw EbicsProtocolError(HttpStatusCode.BadRequest, "Parameter '$name' not provided in URI")
}

private suspend fun fetchTransactionsInternal(
    client: HttpClient,
    fetchSpec: FetchSpecJson,
    userId: String,
    accountid: String
) {
    val res = transaction {
        val acct = NexusBankAccountEntity.findById(accountid)
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
        return@transaction object {
            val connectionType = conn.type
            val connectionName = conn.id.value
        }
    }
    when (res.connectionType) {
        "ebics" -> {
            // FIXME(dold): Support fetching not only the latest transactions.
            // It's not clear what's the nicest way to support this.
            fetchEbicsBySpec(
                fetchSpec,
                client,
                res.connectionName
            )
            ingestBankMessagesIntoAccount(res.connectionName, accountid)
        }
        else -> throw NexusError(
            HttpStatusCode.BadRequest,
            "Connection type '${res.connectionType}' not implemented"
        )
    }
}

fun serverMain(dbName: String) {
    dbCreateTables(dbName)
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
            exception<EbicsProtocolError> { cause ->
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

        lessFrequentBackgroundTasks(client)
        moreFrequentBackgroundTasks(client)
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

            route("/bank-connection-protocols/ebics") {
                ebicsBankProtocolRoutes(client)
            }

            /**
             * Shows the bank accounts belonging to the requesting user.
             */
            get("/bank-accounts") {
                val bankAccounts = BankAccounts()
                transaction {
                    authenticateRequest(call.request)
                    // FIXME(dold): Only return accounts the user has at least read access to?
                    NexusBankAccountEntity.all().forEach {
                        bankAccounts.accounts.add(BankAccount(it.accountHolder, it.iban, it.bankCode, it.id.value))
                    }
                }
                call.respond(bankAccounts)
                return@get
            }

            get("/bank-accounts/{accountid}") {
                val accountId = ensureNonNull(call.parameters["accountid"])
                val res = transaction {
                    val user = authenticateRequest(call.request)
                    val bankAccount = NexusBankAccountEntity.findById(accountId)
                    if (bankAccount == null) {
                        throw NexusError(HttpStatusCode.NotFound, "unknown bank account")
                    }
                    val holderEnc = URLEncoder.encode(bankAccount.accountHolder, "UTF-8")
                    return@transaction object {
                        val defaultBankConnection = bankAccount.defaultBankConnection?.id?.value
                        val accountPaytoUri = "payto://iban/${bankAccount.iban}?receiver-name=$holderEnc"
                    }
                }
                call.respond(res)
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
                    val bankAccount = NexusBankAccountEntity.findById(accountId)
                    if (bankAccount == null) {
                        throw NexusError(HttpStatusCode.NotFound, "unknown bank account")
                    }
                    val defaultBankConnection = bankAccount.defaultBankConnection
                        ?: throw NexusError(HttpStatusCode.NotFound, "needs a default connection")
                    return@transaction object {
                        val pain001document = createPain001document(preparedPayment)
                        val bankConnectionType = defaultBankConnection.type
                        val connId = defaultBankConnection.id.value
                    }
                }
                // type and name aren't null
                when (res.bankConnectionType) {
                    "ebics" -> {
                        submitEbicsPaymentInitiation(client, res.connId, res.pain001document)
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
                val sd = res.preparedPayment.submissionDate
                call.respond(
                    PaymentStatus(
                        uuid = res.preparedPayment.id.value,
                        submitted = res.preparedPayment.submitted,
                        creditorName = res.preparedPayment.creditorName,
                        creditorBic = res.preparedPayment.creditorBic,
                        creditorIban = res.preparedPayment.creditorIban,
                        amount = "${res.preparedPayment.sum}:${res.preparedPayment.currency}",
                        subject = res.preparedPayment.subject,
                        submissionDate = if (sd != null) {
                            importDateFromMillis(sd).toDashedDate()
                        } else null,
                        preparationDate = importDateFromMillis(res.preparedPayment.preparationDate).toDashedDate()
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
                    val bankAccount = NexusBankAccountEntity.findById(accountId)
                    if (bankAccount == null) {
                        throw NexusError(HttpStatusCode.NotFound, "unknown bank account")
                    }
                    val amount = parseAmount(body.amount)
                    val paymentEntity = addPreparedPayment(
                        Pain001Data(
                            creditorIban = body.iban,
                            creditorBic = body.bic,
                            creditorName = body.name,
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
                val user = transaction { authenticateRequest(call.request) }
                val fetchSpec = if (call.request.hasBody()) {
                    call.receive<FetchSpecJson>()
                } else {
                    FetchSpecLatestJson(FetchLevel.ALL, null)
                }
                fetchTransactionsInternal(
                    client,
                    fetchSpec,
                    user.id.value,
                    accountid
                )
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
                    authenticateRequest(call.request).id.value
                    RawBankTransactionEntity.all().map {
                        val tx = jacksonObjectMapper().readValue(it.transactionJson, BankTransaction::class.java)
                        ret.transactions.add(tx)
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
                            val type = body.data.get("type")
                            if (type == null || !type.isTextual) {
                                throw NexusError(HttpStatusCode.BadRequest, "backup needs type")
                            }
                            when (type.textValue()) {
                                "ebics" -> {
                                    createEbicsBankConnectionFromBackup(body.name, user, body.passphrase, body.data)
                                }
                                else -> {
                                    throw NexusError(HttpStatusCode.BadRequest, "backup type not supported")
                                }
                            }
                        }
                        is CreateBankConnectionFromNewRequestJson -> {
                            when (body.type) {
                                "ebics" -> {
                                    createEbicsBankConnection(body.name, user, body.data)
                                }
                                "loopback" -> {
                                    createLoopbackBankConnection(body.name, user, body.data)

                                }
                                else -> {
                                    throw NexusError(
                                        HttpStatusCode.BadRequest,
                                        "connection type ${body.type} not supported"
                                    )
                                }
                            }
                        }
                    }
                }
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

            get("/bank-connections/{connid}") {
                val resp = transaction {
                    val user = authenticateRequest(call.request)
                    val conn = requireBankConnection(call, "connid")
                    when (conn.type) {
                        "ebics" -> {
                            getEbicsConnectionDetails(conn)
                        }
                        else -> {
                            throw NexusError(
                                HttpStatusCode.BadRequest,
                                "bank connection is not of type 'ebics' (but '${conn.type}')"
                            )
                        }
                    }
                }
                call.respond(resp)
            }

            post("/bank-connections/{connid}/export-backup") {
                transaction { authenticateRequest(call.request) }
                val body = call.receive<BackupRequestJson>()
                val response = run {
                    val conn = requireBankConnection(call, "connid")
                    when (conn.type) {
                        "ebics" -> {
                            exportEbicsKeyBackup(conn.id.value, body.passphrase)
                        }
                        else -> {
                            throw NexusError(
                                HttpStatusCode.BadRequest,
                                "bank connection is not of type 'ebics' (but '${conn.type}')"
                            )
                        }
                    }
                }
                call.response.headers.append("Content-Disposition", "attachment")
                call.respond(
                    HttpStatusCode.OK,
                    response
                )
            }

            post("/bank-connections/{connid}/connect") {
                val conn = transaction {
                    authenticateRequest(call.request)
                    requireBankConnection(call, "connid")
                }
                when (conn.type) {
                    "ebics" -> {
                        connectEbics(client, conn.id.value)
                    }
                }
                call.respond(object {})
            }

            get("/bank-connections/{connid}/messages") {
                val ret = transaction {
                    val list = BankMessageList()
                    val conn = requireBankConnection(call, "connid")
                    NexusBankMessageEntity.find { NexusBankMessagesTable.bankConnection eq conn.id }.map {
                        list.bankMessages.add(BankMessageInfo(it.messageId, it.code, it.message.bytes.size.toLong()))
                    }
                    list
                }
                call.respond(ret)
            }

            get("/bank-connections/{connid}/messages/{msgid}") {
                val ret = transaction {
                    val msgid = call.parameters["msgid"]
                    if (msgid == null || msgid == "") {
                        throw NexusError(HttpStatusCode.BadRequest, "missing or invalid message ID")
                    }
                    val msg = NexusBankMessageEntity.find { NexusBankMessagesTable.messageId eq msgid }.firstOrNull()
                    if (msg == null) {
                        throw NexusError(HttpStatusCode.NotFound, "bank message not found")
                    }
                    return@transaction object {
                        val msgContent = msg.message.bytes
                    }
                }
                call.respondBytes(ret.msgContent, ContentType("application", "xml"))
            }

            post("/facades") {
                val body = call.receive<FacadeInfo>()
                val newFacade = transaction {
                    val user = authenticateRequest(call.request)
                    FacadeEntity.new(body.name) {
                        type = body.type
                        creator = user
                    }
                }
                transaction {
                    TalerFacadeStateEntity.new {
                        bankAccount = body.config.bankAccount
                        bankConnection = body.config.bankConnection
                        intervalIncrement = body.config.intervalIncremental
                        reserveTransferLevel = body.config.reserveTransferLevel
                        facade = newFacade
                    }
                }
                call.respondText("Facade created")
                return@post
            }

            route("/bank-connections/{connid}/ebics") {
                ebicsBankConnectionRoutes(client)
            }

            route("/facades/{fcid}/taler") {
                talerFacadeRoutes(this)
            }
            /**
             * Hello endpoint.
             */
            get("/") {
                call.respondText("Hello, this is Nexus.\n")
                return@get
            }
        }
    }
    logger.info("Up and running")
    server.start(wait = true)
}
