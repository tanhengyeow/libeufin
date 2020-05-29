package tech.libeufin.nexus

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.post
import org.apache.http.client.methods.RequestBuilder.post
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
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
     * payto://iban/BIC?/IBAN?name=<name>
     * payto://x-taler-bank/<bank hostname>/<plain account number>
     */

    val ibanMatch = Regex("payto://iban/([A-Z0-9]+/)?([A-Z0-9]+)\\?name=(\\w+)").find(paytoUri)
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
    return "payto://x-taler-bank/localhost/$iban"
}
fun buildPaytoUri(iban: String, bic: String): String {
    return "payto://x-taler-bank/localhost/$iban"
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
/** Helper handling 'start' being optional and its dependence on 'delta'.  */
fun handleStartArgument(start: String?, delta: Int): Long {
    return expectLong(start) ?: if (delta >= 0) {
        /**
         * Using -1 as the smallest value, as some DBMS might use 0 and some
         * others might use 1 as the smallest row id.
         */
        -1
    } else {
        /**
         * NOTE: the database currently enforces there MAX_VALUE is always
         * strictly greater than any row's id in the database.  In fact, the
         * database throws exception whenever a new row is going to occupy
         * the MAX_VALUE with its id.
         */
        Long.MAX_VALUE
    }
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

fun getFacadeBankAccount(nexusUser: NexusUserEntity): NexusBankAccountEntity {
    val facade = FacadeEntity.find { FacadesTable.creator eq nexusUser.id.value }.firstOrNull() ?: throw NexusError(
        HttpStatusCode.NotFound, "Could not find any facade from ${nexusUser.id.value}"
    )
    val bankAccount = NexusBankAccountEntity.findById(facade.config.bankAccount) ?: throw NexusError(
        HttpStatusCode.NotFound, "Could not find any bank account named ${facade.config.bankAccount}"
    )
    return bankAccount
}

// /taler/transfer
suspend fun talerTransfer(call: ApplicationCall): Unit {
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
        val exchangeBankAccount = getFacadeBankAccount(exchangeUser)
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
        val rawEbics = if (!isProduction()) {
            RawBankTransactionEntity.new {
                unstructuredRemittanceInformation = transferRequest.wtid
                transactionType = "DBIT"
                currency = amountObj.currency
                this.amount = amountObj.amount.toPlainString()
                counterpartBic = creditorObj.bic
                counterpartIban = creditorObj.iban
                counterpartName = creditorObj.name
                bankAccount = exchangeBankAccount
                bookingDate = DateTime.now().millis
                status = "BOOK"
            }
        } else null
        val row = TalerRequestedPaymentEntity.new {
            preparedPayment = pain001 // not really used/needed, just here to silence warnings
            exchangeBaseUrl = transferRequest.exchange_base_url
            requestUId = transferRequest.request_uid
            amount = transferRequest.amount
            wtid = transferRequest.wtid
            creditAccount = transferRequest.credit_account
            rawConfirmed = rawEbics
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
                    timestamp = GnunetTimestamp(DateTime.now().millis),
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
    val amount = parseAmount(addIncomingData.amount)
    val (bookingDate, opaque_row_id) = transaction {
        val exchangeUser = authenticateRequest(call.request)
        val rawPayment = RawBankTransactionEntity.new {
            unstructuredRemittanceInformation = addIncomingData.reserve_pub
            transactionType = "CRDT"
            currency = amount.currency
            this.amount = amount.amount.toPlainString()
            counterpartBic = debtor.bic
            counterpartName = debtor.name
            counterpartIban = debtor.iban
            bookingDate = DateTime.now().millis
            status = "BOOK"
            bankAccount = getFacadeBankAccount(exchangeUser)
        }
        /** This payment is "valid by default" and will be returned
         * as soon as the exchange will ask for new payments.  */
        val row = TalerIncomingPaymentEntity.new {
            payment = rawPayment
            valid = true
        }
        Pair(rawPayment.bookingDate, row.id.value)
    }
    return call.respond(
        TextContent(
            customConverter(
                TalerAddIncomingResponse(
                    timestamp = GnunetTimestamp(bookingDate/ 1000),
                    row_id = opaque_row_id
                )
            ),
            ContentType.Application.Json
        )
    )
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
    fun ingestIncoming(subscriberAccount: NexusBankAccountEntity) {
        val latestIncomingPaymentId: Long = TalerIncomingPaymentEntity.getLast()
        RawBankTransactionEntity.find {
            /** Those with exchange bank account involved */
            RawBankTransactionsTable.bankAccount eq subscriberAccount.id.value and
                    /** Those that are incoming */
                    (RawBankTransactionsTable.transactionType eq "CRDT") and
                    /** Those that are booked */
                    (RawBankTransactionsTable.status eq "BOOK") and
                    /** Those that came later than the latest processed payment */
                    (RawBankTransactionsTable.id.greater(latestIncomingPaymentId))
        }.forEach {
            if (duplicatePayment(it)) {
                logger.warn("Incoming payment already seen")
                throw NexusError(
                    HttpStatusCode.InternalServerError,
                    "Incoming payment already seen"
                )
            }
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

    }
    fun ingestOutgoing(subscriberAccount: NexusBankAccountEntity) {
        val latestOutgoingPaymentId = TalerRequestedPaymentEntity.getLast()
        RawBankTransactionEntity.find {
            /** Those that came after the last processed payment */
            RawBankTransactionsTable.id greater latestOutgoingPaymentId and
                    /** Those involving the exchange bank account */
                    (RawBankTransactionsTable.bankAccount eq subscriberAccount.id.value) and
                    /** Those that are outgoing */
                    (RawBankTransactionsTable.transactionType eq "DBIT")
        }.forEach {
            if (paymentFailed(it)) {
                logger.error("Bank didn't accept one payment from the exchange")
                throw NexusError(
                    HttpStatusCode.InternalServerError,
                    "Bank didn't accept one payment from the exchange"
                )
            }
            if (duplicatePayment(it)) {
                logger.warn("Incoming payment already seen")
                throw NexusError(
                    HttpStatusCode.InternalServerError,
                    "Outgoing payment already seen"
                )
            }
            var talerRequested = TalerRequestedPaymentEntity.find {
                TalerRequestedPayments.wtid eq it.unstructuredRemittanceInformation
            }.firstOrNull() ?: throw NexusError(
                HttpStatusCode.InternalServerError,
                "Unrecognized fresh outgoing payment met (subject: ${it.unstructuredRemittanceInformation})."
            )
            talerRequested.rawConfirmed = it
        }
    }

    transaction {
        FacadeEntity.find {
            FacadesTable.type eq "taler-wire-gateway"
        }.forEach {
            val subscriberAccount = getFacadeBankAccount(it.creator)
            ingestIncoming(subscriberAccount)
            ingestOutgoing(subscriberAccount)
        }
    }
}

suspend fun historyOutgoing(call: ApplicationCall): Unit {
    val delta: Int = expectInt(call.expectUrlParameter("delta"))
    val start: Long = handleStartArgument(call.request.queryParameters["start"], delta)
    val startCmpOp = getComparisonOperator(delta, start, TalerRequestedPayments)
    /* retrieve database elements */
    val history = TalerOutgoingHistory()
    transaction {
        val user = authenticateRequest(call.request)
        /** Retrieve all the outgoing payments from the _clean Taler outgoing table_ */
        val subscriberBankAccount = getFacadeBankAccount(user)
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
                        date = GnunetTimestamp(it.rawConfirmed?.bookingDate?.div(1000) ?: throw NexusError(
                            HttpStatusCode.InternalServerError, "Null value met after check, VERY strange.")),
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
    val exchangeUser = authenticateRequest(call.request)
    val delta: Int = expectInt(call.expectUrlParameter("delta"))
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
    route.get("/taler") {
        call.respondText("Hello Taler")
        return@get
    }
}