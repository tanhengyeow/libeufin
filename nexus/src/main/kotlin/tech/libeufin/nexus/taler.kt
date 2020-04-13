package tech.libeufin.nexus

import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.post
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import tech.libeufin.util.Amount
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.toZonedString
import kotlin.math.abs

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
        val timestamp: Long,
        val row_id: Long
    )

    /** History accounting data structures */
    private data class TalerIncomingBankTransaction(
        val row_id: Long,
        val date: Long, // timestamp
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
        val date: Long, // timestamp
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

    private data class TalerAddIncomingResponse(
        val timestamp: Long,
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
    data class AmountWithCurrency(
        val currency: String,
        val amount: Amount
    )

    /**
     * Helper functions
     */

    fun parsePayto(paytoUri: String): Payto {
        // payto://iban/BIC?/IBAN?name=<name>
        val match = Regex("payto://iban/([A-Z0-9]+/)?([A-Z0-9]+)\\?name=(\\w+)").find(paytoUri) ?: throw
                NexusError(HttpStatusCode.BadRequest, "invalid payto URI ($paytoUri)")
        val (bic, iban, name) = match.destructured
        return Payto(name, iban, bic.replace("/", ""))
    }

    fun parseAmount(amount: String): AmountWithCurrency {
        val match = Regex("([A-Z][A-Z][A-Z]):([0-9]+(\\.[0-9]+)?)").find(amount) ?: throw
                NexusError(HttpStatusCode.BadRequest, "invalid payto URI ($amount)")
        val (currency, number) = match.destructured
        return AmountWithCurrency(currency, Amount(number))
    }
    /** Sort query results in descending order for negative deltas, and ascending otherwise.  */
    private fun <T : Entity<Long>> SizedIterable<T>.orderTaler(delta: Int): List<T> {
        return if (delta < 0) {
            this.sortedByDescending { it.id }
        } else {
            this.sortedBy { it.id }
        }
    }
    private fun getPaytoUri(name: String, iban: String, bic: String): String {
        return "payto://iban/$iban/$bic?receiver-name=$name"
    }
    private fun getPaytoUri(iban: String, bic: String): String {
        return "payto://iban/$iban/$bic"
    }
    private fun parseDate(date: String): DateTime {
        return DateTime.parse(date, DateTimeFormat.forPattern("YYYY-MM-DD"))
    }

    /** Builds the comparison operator for history entries based on the sign of 'delta'  */
    private fun getComparisonOperator(delta: Int, start: Long): Op<Boolean> {
        return if (delta < 0) {
            Expression.build {
                TalerIncomingPayments.id less start
            }
        } else {
            Expression.build {
                TalerIncomingPayments.id greater start
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

    /** attaches Taler endpoints to the main Web server */
    init {
        app.post("/taler/transfer") {
            val exchangeId = authenticateRequest(call.request.headers["Authorization"])
            val transferRequest = call.receive<TalerTransferRequest>()

            /**
             * FIXME: check the UID before putting new data into the database.
             */
            val opaque_row_id = transaction {
                val creditorData = parsePayto(transferRequest.credit_account)
                val exchangeBankAccount = getBankAccountsInfoFromId(exchangeId)
                val pain001 = createPain001entity(
                    Pain001Data(
                        creditorIban = creditorData.iban,
                        creditorBic = creditorData.bic,
                        creditorName = creditorData.name,
                        subject = transferRequest.wtid,
                        sum = parseAmount(transferRequest.amount).amount
                    ),
                    exchangeBankAccount.first().id.value
                )
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
                            "This uid (${transferRequest.request_uid}) belong to a different payment altrady"
                        )
                    }
                }
                val row = TalerRequestedPaymentEntity.new {
                    preparedPayment = pain001
                    exchangeBaseUrl = transferRequest.exchange_base_url
                    requestUId = transferRequest.request_uid
                    amount = transferRequest.amount
                    wtid = transferRequest.wtid
                    creditAccount = transferRequest.credit_account
                }
                row.id.value
            }
            call.respond(
                HttpStatusCode.OK,
                TalerTransferResponse(
                    /**
                     * Normally should point to the next round where the background
                     * routing will sent new PAIN.001 data to the bank; work in progress..
                     */
                    timestamp = DateTime.now().millis / 1000,
                    row_id = opaque_row_id
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
                val exchangeBankAccount = getBankAccountsInfoFromId(exchangeId).first()
                val rawPayment = EbicsRawBankTransactionEntity.new {
                    sourceFileName = "test"
                    unstructuredRemittanceInformation = addIncomingData.reserve_pub
                    transactionType = "CRDT"
                    currency = amount.currency
                    this.amount = amount.amount.toPlainString()
                    creditorIban = exchangeBankAccount.iban
                    creditorName = "Exchange's company name"
                    debitorIban = debtor.iban
                    debitorName = debtor.name
                    counterpartBic = debtor.bic
                    bookingDate = DateTime.now().toZonedString()
                    status = "BOOK"
                }
                /** This payment is "valid by default" and will be returned
                 * as soon as the exchange will ask for new payments.  */
                val row = TalerIncomingPaymentEntity.new {
                    payment = rawPayment
                }
                Pair(rawPayment.bookingDate, row.id.value)
            }
            call.respond(HttpStatusCode.OK, TalerAddIncomingResponse(
                timestamp = parseDate(bookingDate).millis / 1000,
                row_id = opaque_row_id
            ))
            return@post
        }

        /** This endpoint triggers the refunding of invalid payments.  'Refunding'
         * in this context means that nexus _prepares_ the payment instruction and
         * places it into a further table.  Eventually, another routine will perform
         * all the prepared payments.  */
        app.post("/ebics/taler/{id}/accounts/{acctid}/refund-invalid-payments") {
            transaction {
                val subscriber = expectIdTransaction(call.parameters["id"])
                val acctid = expectAcctidTransaction(call.parameters["acctid"])
                if (acctid.subscriber.id != subscriber.id) {
                    throw NexusError(
                        HttpStatusCode.Forbidden,
                        "Such subscriber (${subscriber.id}) can't drive such account (${acctid.id})"
                    )
                }
                TalerIncomingPaymentEntity.find {
                    TalerIncomingPayments.refunded eq false and (TalerIncomingPayments.valid eq false)
                }.forEach {
                    createPain001entity(
                        Pain001Data(
                            creditorName = it.payment.debitorName,
                            creditorIban = it.payment.debitorIban,
                            creditorBic = it.payment.counterpartBic,
                            sum = calculateRefund(it.payment.amount),
                            subject = "Taler refund"
                        ),
                        acctid.id.value
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
                val subscriberAccount = getBankAccountsInfoFromId(id).first()
                /**
                 * Search for fresh INCOMING transactions having a BOOK status.  Cancellations and
                 * other status changes will (1) be _appended_ to the payment history, and (2) be
                 * handled _independently_ by another dedicated routine.
                 */
                val latestIncomingPaymentId: Long = TalerIncomingPaymentEntity.getLast()
                EbicsRawBankTransactionEntity.find {
                    EbicsRawBankTransactionsTable.creditorIban eq subscriberAccount.iban and
                            (EbicsRawBankTransactionsTable.status eq "BOOK") and
                            (EbicsRawBankTransactionsTable.id.greater(latestIncomingPaymentId))
                }.forEach {
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
                EbicsRawBankTransactionEntity.find {
                    EbicsRawBankTransactionsTable.id greater latestOutgoingPaymentId and
                            (EbicsRawBankTransactionsTable.status eq "BOOK")
                }.forEach {
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
            val startCmpOp = getComparisonOperator(delta, start)
            /* retrieve database elements */
            val history = TalerOutgoingHistory()
            transaction {
                /** Retrieve all the outgoing payments from the _clean Taler outgoing table_ */
                val subscriberBankAccount = getBankAccountsInfoFromId(subscriberId).first()
                TalerRequestedPaymentEntity.find {
                    TalerRequestedPayments.rawConfirmed.isNotNull() and startCmpOp
                }.orderTaler(delta).subList(0, abs(delta)).forEach {
                    history.outgoing_transactions.add(
                        TalerOutgoingBankTransaction(
                            row_id = it.id.value,
                            amount = it.amount,
                            wtid = it.wtid,
                            date = parseDate(it.rawConfirmed?.bookingDate ?: throw NexusError(
                                HttpStatusCode.InternalServerError, "Null value met after check, VERY strange.")
                            ).millis / 1000,
                            credit_account = it.creditAccount,
                            debit_account = getPaytoUri(subscriberBankAccount.iban, subscriberBankAccount.bankCode),
                            exchange_base_url = "FIXME-to-request-along-subscriber-registration"
                        )
                    )
                }
            }
            call.respond(
                HttpStatusCode.OK,
                history
            )
            return@get
        }
        /** Responds only with the valid incoming payments */
        app.get("/taler/history/incoming") {
            val subscriberId = authenticateRequest(call.request.headers["Authorization"])
            val delta: Int = expectInt(call.expectUrlParameter("delta"))
            val start: Long = handleStartArgument(call.request.queryParameters["start"], delta)
            val history = TalerIncomingHistory()
            val startCmpOp = getComparisonOperator(delta, start)
            transaction {
                val subscriberBankAccount = getBankAccountsInfoFromId(subscriberId)
                TalerIncomingPaymentEntity.find {
                    TalerIncomingPayments.valid eq true and startCmpOp
                }.orderTaler(delta).subList(0, abs(delta)).forEach {
                    history.incoming_transactions.add(
                        TalerIncomingBankTransaction(
                            date = parseDate(it.payment.bookingDate).millis / 1000, // timestamp in seconds
                            row_id = it.id.value,
                            amount = "${it.payment.currency}:${it.payment.amount}",
                            reserve_pub = it.payment.unstructuredRemittanceInformation,
                            debit_account = getPaytoUri(
                                it.payment.debitorName, it.payment.debitorIban, it.payment.counterpartBic
                            ),
                            credit_account = getPaytoUri(
                                it.payment.creditorName, it.payment.creditorIban, subscriberBankAccount.first().bankCode
                            )
                        )
                    )
                }
            }
            call.respond(history)
            return@get
        }
    }
}