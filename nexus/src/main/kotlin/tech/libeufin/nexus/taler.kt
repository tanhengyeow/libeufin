package tech.libeufin.nexus

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.post
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.w3c.dom.Document
import tech.libeufin.util.*
import kotlin.math.abs
import kotlin.math.min

/** Payment initiating data structures: one endpoint "$BASE_URL/transfer". */
data class TalerTransferRequest(
    val request_uid: String,
    val amount: String,
    val exchange_base_url: String,
    val wtid: String,
    val credit_account: String
)

data class TalerTransferResponse(
    // point in time when the nexus put the payment instruction into the database.
    val timestamp: GnunetTimestamp,
    val row_id: Long
)

/** History accounting data structures */
data class TalerIncomingBankTransaction(
    val row_id: Long,
    val date: GnunetTimestamp, // timestamp
    val amount: String,
    val credit_account: String, // payto form,
    val debit_account: String,
    val reserve_pub: String
)

data class TalerIncomingHistory(
    var incoming_transactions: MutableList<TalerIncomingBankTransaction> = mutableListOf()
)

data class TalerOutgoingBankTransaction(
    val row_id: Long,
    val date: GnunetTimestamp, // timestamp
    val amount: String,
    val credit_account: String, // payto form,
    val debit_account: String,
    val wtid: String,
    val exchange_base_url: String
)

data class TalerOutgoingHistory(
    var outgoing_transactions: MutableList<TalerOutgoingBankTransaction> = mutableListOf()
)

/** Test APIs' data structures. */
data class TalerAdminAddIncoming(
    val amount: String,
    val reserve_pub: String,
    /**
     * This account is the one giving money to the exchange.  It doesn't
     * have to be 'created' as it might (and normally is) simply be a payto://
     * address pointing to a bank account hosted in a different financial
     * institution.
     */
    val debit_account: String
)

data class GnunetTimestamp(
    val t_ms: Long
)

data class TalerAddIncomingResponse(
    val timestamp: GnunetTimestamp,
    val row_id: Long
)

/**
 * Helper data structures.
 */
data class Payto(
    val name: String = "NOTGIVEN",
    val iban: String,
    val bic: String = "NOTGIVEN"
)

fun parsePayto(paytoUri: String): Payto {
    /**
     * First try to parse a "iban"-type payto URI.  If that fails,
     * then assume a test is being run under the "x-taler-bank" type.
     * If that one fails too, throw exception.
     *
     * Note: since the Nexus doesn't have the notion of "x-taler-bank",
     * such URIs must yield a iban-compatible tuple of values.  Therefore,
     * the plain bank account number maps to a "iban", and the <bank hostname>
     * maps to a "bic".
     */


    /**
     * payto://iban/BIC/IBAN?name=<name>
     * payto://x-taler-bank/<bank hostname>/<plain account number>
     */

    val ibanMatch = Regex("payto://iban/([A-Z0-9]+)/([A-Z0-9]+)\\?name=(\\w+)").find(paytoUri)
    if (ibanMatch != null) {
        val (bic, iban, name) = ibanMatch.destructured
        return Payto(name, iban, bic.replace("/", ""))
    }
    val xTalerBankMatch = Regex("payto://x-taler-bank/localhost/([0-9]+)").find(paytoUri)
    if (xTalerBankMatch != null) {
        val xTalerBankAcctNo = xTalerBankMatch.destructured.component1()
        return Payto("Taler Exchange", xTalerBankAcctNo, "localhost")
    }

    throw NexusError(HttpStatusCode.BadRequest, "invalid payto URI ($paytoUri)")
}

/** Sort query results in descending order for negative deltas, and ascending otherwise.  */
fun <T : Entity<Long>> SizedIterable<T>.orderTaler(delta: Int): List<T> {
    return if (delta < 0) {
        this.sortedByDescending { it.id }
    } else {
        this.sortedBy { it.id }
    }
}

/**
 * NOTE: those payto-builders default all to the x-taler-bank transport.
 * A mechanism to easily switch transport is needed, as production needs
 * 'iban'.
 */
fun buildPaytoUri(name: String, iban: String, bic: String): String {
    return "payto://iban/$bic/$iban?name=$name"
}

fun buildPaytoUri(iban: String, bic: String): String {
    return "payto://iban/$bic/$iban"
}

/** Builds the comparison operator for history entries based on the sign of 'delta'  */
fun getComparisonOperator(delta: Int, start: Long, table: IdTable<Long>): Op<Boolean> {
    return if (delta < 0) {
        Expression.build {
            table.id less start
        }
    } else {
        Expression.build {
            table.id greater start
        }
    }
}

fun expectLong(param: String?): Long {
    if (param == null) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'$param' is not Long")
    }
    return try {
        param.toLong()
    } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'$param' is not Long")
    }
}

/** Helper handling 'start' being optional and its dependence on 'delta'.  */
fun handleStartArgument(start: String?, delta: Int): Long {
    if (start == null) {
        if (delta >= 0)
            return -1
        return Long.MAX_VALUE
    }
    return expectLong(start)
}

/**
 * The Taler layer cannot rely on the ktor-internal JSON-converter/responder,
 * because this one adds a "charset" extra information in the Content-Type header
 * that makes the GNUnet JSON parser unhappy.
 *
 * The workaround is to explicitly convert the 'data class'-object into a JSON
 * string (what this function does), and use the simpler respondText method.
 */
fun customConverter(body: Any): String {
    return jacksonObjectMapper().writeValueAsString(body)
}

/**
 * This function indicates whether a payment in the raw table was already reported
 * by some other EBICS message.  It works for both incoming and outgoing payments.
 * Basically, it tries to match all the relevant details with those from the records
 * that are already stored in the local "taler" database.
 *
 * @param entry a new raw payment to be checked.
 * @return true if the payment was already "seen" by the Taler layer, false otherwise.
 */
fun duplicatePayment(entry: RawBankTransactionEntity): Boolean {
    return false
}

/**
 * This function checks whether the bank didn't accept one exchange's payment initiation.
 *
 * @param entry the raw entry to check
 * @return true if the payment failed, false if it was successful.
 */
fun paymentFailed(entry: RawBankTransactionEntity): Boolean {
    return false
}

fun getTalerFacadeState(fcid: String): TalerFacadeStateEntity {
    val facade = FacadeEntity.find { FacadesTable.id eq fcid }.firstOrNull() ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Could not find facade '${fcid}'"
    )
    val facadeState = TalerFacadeStateEntity.find {
        TalerFacadeStatesTable.facade eq facade.id.value
    }.firstOrNull() ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Could not find any state for facade: ${fcid}"
    )
    return facadeState
}

fun getTalerFacadeBankAccount(fcid: String): NexusBankAccountEntity {
    val facade = FacadeEntity.find { FacadesTable.id eq fcid }.firstOrNull() ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Could not find facade '${fcid}'"
    )
    val facadeState = TalerFacadeStateEntity.find {
        TalerFacadeStatesTable.facade eq facade.id.value
    }.firstOrNull() ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Could not find any state for facade: ${fcid}"
    )
    val bankAccount = NexusBankAccountEntity.findById(facadeState.bankAccount) ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Could not find any bank account named ${facadeState.bankAccount}"
    )

    return bankAccount
}

// /taler/transfer
suspend fun talerTransfer(call: ApplicationCall) {
    val transferRequest = call.receive<TalerTransferRequest>()
    val amountObj = parseAmount(transferRequest.amount)
    val creditorObj = parsePayto(transferRequest.credit_account)
    val opaque_row_id = transaction {
        val exchangeUser = authenticateRequest(call.request)
        val creditorData = parsePayto(transferRequest.credit_account)
        /** Checking the UID has the desired characteristics */
        TalerRequestedPaymentEntity.find {
            TalerRequestedPayments.requestUId eq transferRequest.request_uid
        }.forEach {
            if (
                (it.amount != transferRequest.amount) or
                (it.creditAccount != transferRequest.exchange_base_url) or
                (it.wtid != transferRequest.wtid)
            ) {
                throw NexusError(
                    HttpStatusCode.Conflict,
                    "This uid (${transferRequest.request_uid}) belongs to a different payment already"
                )
            }
        }
        val exchangeBankAccount = getTalerFacadeBankAccount(expectNonNull(call.parameters["fcid"]))
        val pain001 = addPreparedPayment(
            Pain001Data(
                creditorIban = creditorData.iban,
                creditorBic = creditorData.bic,
                creditorName = creditorData.name,
                subject = transferRequest.wtid,
                sum = amountObj.amount,
                currency = amountObj.currency
            ),
            exchangeBankAccount
        )
        logger.debug("Taler requests payment: ${transferRequest.wtid}")
        val row = TalerRequestedPaymentEntity.new {
            preparedPayment = pain001 // not really used/needed, just here to silence warnings
            exchangeBaseUrl = transferRequest.exchange_base_url
            requestUId = transferRequest.request_uid
            amount = transferRequest.amount
            wtid = transferRequest.wtid
            creditAccount = transferRequest.credit_account
        }
        row.id.value
    }
    return call.respond(
        HttpStatusCode.OK,
        TextContent(
            customConverter(
                TalerTransferResponse(
                    /**
                     * Normally should point to the next round where the background
                     * routine will send new PAIN.001 data to the bank; work in progress..
                     */
                    timestamp = GnunetTimestamp(System.currentTimeMillis()),
                    row_id = opaque_row_id
                )
            ),
            ContentType.Application.Json
        )
    )
}

// /taler/admin/add-incoming
suspend fun talerAddIncoming(call: ApplicationCall): Unit {
    val addIncomingData = call.receive<TalerAdminAddIncoming>()
    val debtor = parsePayto(addIncomingData.debit_account)
    val res = transaction {
        val user = authenticateRequest(call.request)
        val facadeID = expectNonNull(call.parameters["fcid"])
        val facadeState = getTalerFacadeState(facadeID)
        val facadeBankAccount = getTalerFacadeBankAccount(facadeID)
        return@transaction object {
            val facadeLastSeen = facadeState.highestSeenMsgID
            val facadeIban = facadeBankAccount.iban
            val facadeBic = facadeBankAccount.bankCode
            val facadeHolderName = facadeBankAccount.accountHolder
        }
    }
    val httpClient = HttpClient()
    /** forward the payment information to the sandbox.  */
    httpClient.post<String>(
        urlString = "http://localhost:5000/admin/payments",
        block = {
            /** FIXME: ideally Jackson should define such request body.  */
            val parsedAmount = parseAmount(addIncomingData.amount)
            this.body = """{
                "creditorIban": "${res.facadeIban}",
                "creditorBic": "${res.facadeBic}",
                "creditorName": "${res.facadeHolderName}",
                "debitorIban": "${debtor.iban}",
                "debitorBic": "${debtor.bic}",
                "debitorName": "${debtor.name}",
                "amount": "${parsedAmount.amount}",
                "currency": "${parsedAmount.currency}",
                "subject": "${addIncomingData.reserve_pub}"
            }""".trimIndent()
            contentType(ContentType.Application.Json)
        }
    )
    return call.respond(
        TextContent(
            customConverter(
                TalerAddIncomingResponse(
                    timestamp = GnunetTimestamp(
                        System.currentTimeMillis()
                    ),
                    row_id = res.facadeLastSeen
                )
            ),
            ContentType.Application.Json
        )
    )
}

// submits ALL the prepared payments from ALL the Taler facades.
suspend fun submitPreparedPaymentsViaEbics() {
    data class EbicsSubmission(
        val subscriberDetails: EbicsClientSubscriberDetails,
        val pain001document: String
    )
    val workQueue = mutableListOf<EbicsSubmission>()
    transaction {
        TalerFacadeStateEntity.all().forEach {
            val bankConnection = NexusBankConnectionEntity.findById(it.bankConnection) ?: throw NexusError(
                HttpStatusCode.InternalServerError,
                "Such facade '${it.facade.id.value}' doesn't map to any bank connection (named '${it.bankConnection}')"
            )
            if (bankConnection.type != "ebics") {
                logger.info("Skipping non-implemented bank connection '${bankConnection.type}'")
                return@forEach
            }

            val subscriberEntity = EbicsSubscriberEntity.find {
                EbicsSubscribersTable.nexusBankConnection eq it.bankConnection
            }.firstOrNull() ?: throw NexusError(
                HttpStatusCode.InternalServerError,
                "Such facade '${it.facade.id.value}' doesn't map to any Ebics subscriber"
            )
            val bankAccount: NexusBankAccountEntity = NexusBankAccountEntity.findById(it.bankAccount) ?: throw NexusError(
                HttpStatusCode.InternalServerError,
                "Bank account '${it.bankAccount}' not found for facade '${it.id.value}'"
            )
            PreparedPaymentEntity.find { PreparedPaymentsTable.debitorIban eq bankAccount.iban }.forEach {
                val pain001document = createPain001document(it)
                val subscriberDetails = getEbicsSubscriberDetailsInternal(subscriberEntity)
                workQueue.add(EbicsSubmission(subscriberDetails, pain001document))
                // FIXME: the payment must be flagger AFTER the submission happens.
                it.submitted = true
            }
        }
    }
    val httpClient = HttpClient()
    workQueue.forEach {
        doEbicsUploadTransaction(
            httpClient,
            it.subscriberDetails,
            "CCT",
            it.pain001document.toByteArray(Charsets.UTF_8),
            EbicsStandardOrderParams()
        )
    }
}

/**
 * Crawls the database to find ALL the users that have a Taler
 * facade and process their histories respecting the TWG policy.
 * The two main tasks it does are: (1) marking as invalid those
 * payments with bad subject line, and (2) see if previously requested
 * payments got booked as outgoing payments (and mark them accordingly
 * in the local table).
 */
fun ingestTalerTransactions() {
    fun ingest(subscriberAccount: NexusBankAccountEntity, facade: FacadeEntity) {
        logger.debug("Ingesting transactions for Taler facade: ${facade.id.value}")
        val facadeState = getTalerFacadeState(facade.id.value)
        var lastId = facadeState.highestSeenMsgID
        RawBankTransactionEntity.find {
            /** Those with exchange bank account involved */
            RawBankTransactionsTable.bankAccount eq subscriberAccount.id.value and
                    /** Those that are booked */
                    (RawBankTransactionsTable.status eq "BOOK") and
                    /** Those that came later than the latest processed payment */
                    (RawBankTransactionsTable.id.greater(lastId))
        }.orderBy(Pair(RawBankTransactionsTable.id, SortOrder.ASC)).forEach {
            // Incoming payment.
            if (it.transactionType == "CRDT") {
                if (CryptoUtil.checkValidEddsaPublicKey(it.unstructuredRemittanceInformation)) {
                    TalerIncomingPaymentEntity.new {
                        payment = it
                        valid = true
                    }
                } else {
                    TalerIncomingPaymentEntity.new {
                        payment = it
                        valid = false
                    }
                }
            }
            // Outgoing payment
            if (it.transactionType == "DBIT") {
                logger.debug("Ingesting outgoing payment: ${it.unstructuredRemittanceInformation}")
                var talerRequested = TalerRequestedPaymentEntity.find {
                    TalerRequestedPayments.wtid eq it.unstructuredRemittanceInformation
                }.firstOrNull() ?: throw NexusError(
                    HttpStatusCode.InternalServerError,
                    "Payment '${it.unstructuredRemittanceInformation}' shows in history, but was never requested!"
                )
                talerRequested.rawConfirmed = it
            }
            lastId = it.id.value
        }
        facadeState.highestSeenMsgID = lastId
    }
    // invoke ingestion for all the facades
    transaction {
        FacadeEntity.find {
            FacadesTable.type eq "taler-wire-gateway"
        }.forEach {
            val subscriberAccount = getTalerFacadeBankAccount(it.id.value)
            ingest(subscriberAccount, it)
        }
    }
}

suspend fun historyOutgoing(call: ApplicationCall): Unit {
    val param = call.expectUrlParameter("delta")
    val delta: Int = try {
        param.toInt()
    } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'${param}' is not Int")
    }
    val start: Long = handleStartArgument(call.request.queryParameters["start"], delta)
    val startCmpOp = getComparisonOperator(delta, start, TalerRequestedPayments)
    /* retrieve database elements */
    val history = TalerOutgoingHistory()
    transaction {
        val user = authenticateRequest(call.request)
        /** Retrieve all the outgoing payments from the _clean Taler outgoing table_ */
        val subscriberBankAccount = getTalerFacadeBankAccount(expectNonNull(call.parameters["fcid"]))
        val reqPayments = TalerRequestedPaymentEntity.find {
            TalerRequestedPayments.rawConfirmed.isNotNull() and startCmpOp
        }.orderTaler(delta)
        if (reqPayments.isNotEmpty()) {
            reqPayments.subList(0, min(abs(delta), reqPayments.size)).forEach {
                history.outgoing_transactions.add(
                    TalerOutgoingBankTransaction(
                        row_id = it.id.value,
                        amount = it.amount,
                        wtid = it.wtid,
                        date = GnunetTimestamp(
                            it.rawConfirmed?.bookingDate?.div(1000) ?: throw NexusError(
                                HttpStatusCode.InternalServerError, "Null value met after check, VERY strange."
                            )
                        ),
                        credit_account = it.creditAccount,
                        debit_account = buildPaytoUri(subscriberBankAccount.iban, subscriberBankAccount.bankCode),
                        exchange_base_url = "FIXME-to-request-along-subscriber-registration"
                    )
                )
            }
        }
    }
    call.respond(
        HttpStatusCode.OK,
        TextContent(customConverter(history), ContentType.Application.Json)
    )
}

// /taler/history/incoming
suspend fun historyIncoming(call: ApplicationCall): Unit {
    val param = call.expectUrlParameter("delta")
    val delta: Int = try {
        param.toInt()
    } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'${param}' is not Int")
    }
    val start: Long = handleStartArgument(call.request.queryParameters["start"], delta)
    val history = TalerIncomingHistory()
    val startCmpOp = getComparisonOperator(delta, start, TalerIncomingPayments)
    transaction {
        val orderedPayments = TalerIncomingPaymentEntity.find {
            TalerIncomingPayments.valid eq true and startCmpOp
        }.orderTaler(delta)
        if (orderedPayments.isNotEmpty()) {
            orderedPayments.subList(0, min(abs(delta), orderedPayments.size)).forEach {
                history.incoming_transactions.add(
                    TalerIncomingBankTransaction(
                        date = GnunetTimestamp(it.payment.bookingDate / 1000),
                        row_id = it.id.value,
                        amount = "${it.payment.currency}:${it.payment.amount}",
                        reserve_pub = it.payment.unstructuredRemittanceInformation,
                        credit_account = buildPaytoUri(
                            it.payment.bankAccount.accountHolder,
                            it.payment.bankAccount.iban,
                            it.payment.bankAccount.bankCode
                        ),
                        debit_account = buildPaytoUri(
                            it.payment.counterpartName,
                            it.payment.counterpartIban,
                            it.payment.counterpartBic
                        )
                    )
                )
            }
        }
    }
    return call.respond(TextContent(customConverter(history), ContentType.Application.Json))
}

fun talerFacadeRoutes(route: Route) {
    route.post("/transfer") {
        talerTransfer(call)
        return@post
    }
    route.post("/admin/add-incoming") {
        talerAddIncoming(call)
        return@post
    }
    route.get("/history/outgoing") {
        historyOutgoing(call)
        return@get
    }
    route.get("/history/incoming") {
        historyIncoming(call)
        return@get
    }
    route.get("") {
        call.respondText("Hello, this is Taler Facade")
        return@get
    }
}