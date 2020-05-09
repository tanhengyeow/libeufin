package tech.libeufin.nexus

import com.google.gson.Gson
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
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import tech.libeufin.util.*
import kotlin.math.abs
import kotlin.math.min

class Taler(app: Route) {

    /** Payment initiating data structures: one endpoint "$BASE_URL/transfer". */
    private data class TalerTransferRequest(
        val request_uid: String,
        val amount: String,
        val exchange_base_url: String,
        val wtid: String,
        val credit_account: String
    )
    private data class TalerTransferResponse(
        // point in time when the nexus put the payment instruction into the database.
        val timestamp: GnunetTimestamp,
        val row_id: Long
    )

    /** History accounting data structures */
    private data class TalerIncomingBankTransaction(
        val row_id: Long,
        val date: GnunetTimestamp, // timestamp
        val amount: String,
        val credit_account: String, // payto form,
        val debit_account: String,
        val reserve_pub: String
    )
    private data class TalerIncomingHistory(
        var incoming_transactions: MutableList<TalerIncomingBankTransaction> = mutableListOf()
    )
    private data class TalerOutgoingBankTransaction(
        val row_id: Long,
        val date: GnunetTimestamp, // timestamp
        val amount: String,
        val credit_account: String, // payto form,
        val debit_account: String,
        val wtid: String,
        val exchange_base_url: String
    )
    private data class TalerOutgoingHistory(
        var outgoing_transactions: MutableList<TalerOutgoingBankTransaction> = mutableListOf()
    )

    /** Test APIs' data structures. */
    private data class TalerAdminAddIncoming(
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

    private data class GnunetTimestamp(
        val t_ms: Long
    )
    private data class TalerAddIncomingResponse(
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
    /**
     * Helper functions
     */
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
    private fun <T : Entity<Long>> SizedIterable<T>.orderTaler(delta: Int): List<T> {
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
    private fun buildPaytoUri(name: String, iban: String, bic: String): String {
        return "payto://x-taler-bank/localhost/$iban"
    }
    private fun buildPaytoUri(iban: String, bic: String): String {
        return "payto://x-taler-bank/localhost/$iban"
    }

    /** Builds the comparison operator for history entries based on the sign of 'delta'  */
    private fun getComparisonOperator(delta: Int, start: Long, table: IdTable<Long>): Op<Boolean> {
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
    private fun handleStartArgument(start: String?, delta: Int): Long {
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
    private fun customConverter(body: Any): String {
        return Gson().toJson(body)
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
    private fun duplicatePayment(entry: RawBankTransactionEntity): Boolean {
        return false
    }

    /**
     * This function checks whether the bank didn't accept one exchange's payment initiation.
     *
     * @param entry the raw entry to check
     * @return true if the payment failed, false if it was successful.
     */
    private fun paymentFailed(entry: RawBankTransactionEntity): Boolean {
        return false
    }

    /** Attach Taler endpoints to the main Web server */

    init {
        app.get("/taler") {
            call.respondText("Taler Gateway Hello\n", ContentType.Text.Plain, HttpStatusCode.OK)
            return@get
        }
        app.post("/taler/transfer") {
            val exchangeId = authenticateRequest(call.request.headers["Authorization"])
            val transferRequest = call.receive<TalerTransferRequest>()
            val amountObj = parseAmount(transferRequest.amount)
            val creditorObj = parsePayto(transferRequest.credit_account)
            val opaque_row_id = transaction {
                val creditorData = parsePayto(transferRequest.credit_account)
                val exchangeBankAccount = getBankAccountFromNexusUserId(exchangeId)
                val nexusUser = extractNexusUser(exchangeId)
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
                val pain001 = addPreparedPayment(
                    Pain001Data(
                        creditorIban = creditorData.iban,
                        creditorBic = creditorData.bic,
                        creditorName = creditorData.name,
                        subject = transferRequest.wtid,
                        sum = amountObj.amount,
                        currency = amountObj.currency,
                        debitorName = exchangeBankAccount.accountHolder,
                        debitorBic = exchangeBankAccount.bankCode,
                        debitorIban = exchangeBankAccount.iban
                    ),
                    nexusUser
                )
                val rawEbics = if (!isProduction()) {
                    RawBankTransactionEntity.new {
                        sourceFileName = "test"
                        unstructuredRemittanceInformation = transferRequest.wtid
                        transactionType = "DBIT"
                        currency = amountObj.currency
                        this.amount = amountObj.amount.toPlainString()
                        debitorName = "Exchange Company"
                        debitorIban = exchangeBankAccount.iban
                        creditorName = creditorObj.name
                        creditorIban = creditorObj.iban
                        counterpartBic = creditorObj.bic
                        bookingDate = DateTime.now().millis
                        this.nexusUser = nexusUser
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
            call.respond(
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
            return@post
        }
        /** Test-API that creates one new payment addressed to the exchange.  */
        app.post("/taler/admin/add-incoming") {
            val exchangeId = authenticateRequest(call.request.headers["Authorization"])
            val addIncomingData = call.receive<TalerAdminAddIncoming>()
            val debtor = parsePayto(addIncomingData.debit_account)
            val amount = parseAmount(addIncomingData.amount)
            val (bookingDate, opaque_row_id) = transaction {
                val exchangeBankAccount = getBankAccountFromNexusUserId(exchangeId)
                val rawPayment = RawBankTransactionEntity.new {
                    sourceFileName = "test"
                    unstructuredRemittanceInformation = addIncomingData.reserve_pub
                    transactionType = "CRDT"
                    currency = amount.currency
                    this.amount = amount.amount.toPlainString()
                    creditorIban = exchangeBankAccount.iban
                    creditorName = exchangeBankAccount.accountHolder
                    debitorIban = debtor.iban
                    debitorName = debtor.name
                    counterpartBic = debtor.bic
                    bookingDate = DateTime.now().millis
                    status = "BOOK"
                    nexusUser = extractNexusUser(exchangeId)
                }
                /** This payment is "valid by default" and will be returned
                 * as soon as the exchange will ask for new payments.  */
                val row = TalerIncomingPaymentEntity.new {
                    payment = rawPayment
                    valid = true
                }
                Pair(rawPayment.bookingDate, row.id.value)
            }
            call.respond(
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
            return@post
        }

        /** This endpoint triggers the refunding of invalid payments.  'Refunding'
         * in this context means that nexus _prepares_ the payment instruction and
         * places it into a further table.  Eventually, another routine will perform
         * all the prepared payments.  */
        app.post("/ebics/taler/{id}/accounts/{acctid}/refund-invalid-payments") {
            transaction {
                val nexusUser = extractNexusUser(call.parameters["id"])
                val acctid = expectAcctidTransaction(call.parameters["acctid"])
                if (!subscriberHasRights(getEbicsSubscriberFromUser(nexusUser), acctid)) {
                    throw NexusError(
                        HttpStatusCode.Forbidden,
                        "The requester can't drive such account (${acctid.id})"
                    )
                }
                val requesterBankAccount = getBankAccountFromNexusUserId(nexusUser.id.value)
                TalerIncomingPaymentEntity.find {
                    TalerIncomingPayments.refunded eq false and (TalerIncomingPayments.valid eq false)
                }.forEach {
                    addPreparedPayment(
                        Pain001Data(
                            creditorName = it.payment.debitorName,
                            creditorIban = it.payment.debitorIban,
                            creditorBic = it.payment.counterpartBic,
                            sum = calculateRefund(it.payment.amount),
                            subject = "Taler refund",
                            debitorIban = requesterBankAccount.iban,
                            debitorBic = requesterBankAccount.bankCode,
                            debitorName = requesterBankAccount.accountHolder,
                            currency = it.payment.currency
                        ),
                        nexusUser
                    )
                    it.refunded = true
                }
            }
            return@post
        }

        /** This endpoint triggers the examination of raw incoming payments aimed
         * at separating the good payments (those that will lead to a new reserve
         * being created), from the invalid payments (those with a invalid subject
         * that will soon be refunded.)  Recently, the examination of raw OUTGOING
         * payment was added as well.
         */
        app.post("/ebics/taler/{id}/crunch-raw-transactions") {
            val id = expectId(call.parameters["id"])
            // first find highest ID value of already processed rows.
            transaction {
                val subscriberAccount = getBankAccountFromNexusUserId(id)
                /**
                 * Search for fresh incoming payments in the raw table, and making pointers
                 * from the Taler incoming payments table to the found fresh payments.
                 */
                val latestIncomingPaymentId: Long = TalerIncomingPaymentEntity.getLast()
                RawBankTransactionEntity.find {
                    /** select payments having the exchange as the credited party */
                    RawBankTransactionsTable.creditorIban eq subscriberAccount.iban and
                            (RawBankTransactionsTable.status eq "BOOK") and
                            /** avoid processing old payments from the raw table */
                            (RawBankTransactionsTable.id.greater(latestIncomingPaymentId))
                }.forEach {
                    if (duplicatePayment(it)) {
                        logger.warn("Incomint payment already seen")
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
                /**
                 * Search for fresh OUTGOING transactions acknowledged by the bank.  As well
                 * searching only for BOOKed transactions, even though status changes should
                 * be really unexpected here.
                 */
                val latestOutgoingPaymentId = TalerRequestedPaymentEntity.getLast()
                RawBankTransactionEntity.find {
                    RawBankTransactionsTable.id greater latestOutgoingPaymentId and
                            ( RawBankTransactionsTable.debitorIban eq  subscriberAccount.iban)
                }.forEach {
                    if (paymentFailed(it)) {
                        logger.error("Bank didn't accept one payment from the exchange")
                        throw NexusError(
                            HttpStatusCode.InternalServerError,
                            "Bank didn't accept one payment from the exchange"
                        )
                    }
                    if (duplicatePayment(it)) {
                        logger.warn("Incomint payment already seen")
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

            call.respondText (
                "New raw payments Taler-processed",
                ContentType.Text.Plain,
                HttpStatusCode.OK
            )
            return@post
        }
        /** Responds only with the payments that the EXCHANGE made.  Typically to
         * merchants but possibly to refund invalid incoming payments.  A payment is
         * counted only if was once confirmed by the bank.
         */
        app.get("/taler/history/outgoing") {
            /* sanitize URL arguments */
            val subscriberId = authenticateRequest(call.request.headers["Authorization"])
            val delta: Int = expectInt(call.expectUrlParameter("delta"))
            val start: Long = handleStartArgument(call.request.queryParameters["start"], delta)
            val startCmpOp = getComparisonOperator(delta, start, TalerRequestedPayments)
            /* retrieve database elements */
            val history = TalerOutgoingHistory()
            transaction {
                /** Retrieve all the outgoing payments from the _clean Taler outgoing table_ */
                val subscriberBankAccount = getBankAccountFromNexusUserId(subscriberId)
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
            return@get
        }
        /** Responds only with the valid incoming payments */
        app.get("/taler/history/incoming") {
            val exchangeId = authenticateRequest(call.request.headers["Authorization"])
            val delta: Int = expectInt(call.expectUrlParameter("delta"))
            val start: Long = handleStartArgument(call.request.queryParameters["start"], delta)
            val history = TalerIncomingHistory()
            val startCmpOp = getComparisonOperator(delta, start, TalerIncomingPayments)
            transaction {
                /**
                 * Below, the test harness creates the exchange's bank account
                 * object based on the payto:// given as the funds receiver.
                 *
                 * This is needed because nexus takes this information from the
                 * bank - normally - but tests are currently avoiding any interaction
                 * with banks or sandboxes.
                 */
                if (!isProduction()) {
                    val EXCHANGE_BANKACCOUNT_ID = "exchange-bankaccount-id"
                    if (BankAccountEntity.findById(EXCHANGE_BANKACCOUNT_ID) == null) {
                        val newBankAccount = BankAccountEntity.new(id = EXCHANGE_BANKACCOUNT_ID) {
                            accountHolder = "Test Exchange"
                            iban = "42"
                            bankCode = "localhost"
                        }
                        val nexusUser = extractNexusUser(exchangeId)
                        BankAccountMapEntity.new {
                            bankAccount = newBankAccount
                            ebicsSubscriber = getEbicsSubscriberFromUser(nexusUser)
                            this.nexusUser = nexusUser
                        }
                    }
                }
                val exchangeBankAccount = getBankAccountFromNexusUserId(exchangeId)
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
                                debit_account = buildPaytoUri(
                                    it.payment.debitorName, it.payment.debitorIban, it.payment.counterpartBic
                                ),
                                credit_account = buildPaytoUri(
                                    it.payment.creditorName, it.payment.creditorIban, exchangeBankAccount.bankCode
                                )
                            )
                        )
                    }
                }
            }
            call.respond(TextContent(customConverter(history), ContentType.Application.Json))
            return@get
        }
    }
}