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
import java.util.*
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
        val debit_account: String
    )

    private data class TalerAddIncomingResponse(
        val timestamp: Long,
        val row_id: Long
    )

    /** Helper data structures. */
    data class Payto(
        val name: String,
        val iban: String,
        val bic: String?
    )
    data class AmountWithCurrency(
        val currency: String,
        val amount: Amount
    )

    /** Helper functions */

    fun parsePayto(paytoUri: String): Payto {
        val match = Regex("payto://.*/([A-Z0-9]+)/([A-Z0-9]+)?\\?name=(\\w+)").find(paytoUri) ?: throw
                NexusError(HttpStatusCode.BadRequest, "invalid payto URI ($paytoUri)")
        val (iban, bic, name) = match.destructured
        return Payto(name, iban, bic)
    }

    fun parseAmount(amount: String): AmountWithCurrency {
        val match = Regex("([A-Z][A-Z][A-Z]):([0-9]+(\\.[0-9]+)?)").find(amount) ?: throw
                NexusError(HttpStatusCode.BadRequest, "invalid payto URI ($amount)")
        val (currency, number) = match.destructured
        return AmountWithCurrency(currency, Amount(number))
    }

    private fun <T : Entity<Long>> SizedIterable<T>.orderTaler(delta: Int): List<T> {
        return if (delta < 0) {
            this.sortedByDescending { it.id }
        } else {
            this.sortedBy { it.id }
        }
    }
    private fun getPaytoUri(name: String, iban: String, bic: String): String {
        return "payto://$iban/$bic?receiver-name=$name"
    }
    private fun parseDate(date: String): DateTime {
        return DateTime.parse(date, DateTimeFormat.forPattern("YYYY-MM-DD"))
    }
    /**
     * Builds the comparison operator for history entries based on the
     * sign of 'delta'
     */
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
    /**
     * Helper handling 'start' being optional and its dependence on 'delta'.
     */
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
        app.post("/taler/admin/add-incoming") {
            val addIncomingData = call.receive<TalerAdminAddIncoming>()
            /** Decompose amount and payto fields.  */


            call.respond(HttpStatusCode.OK, NexusErrorJson("Not implemented"))
            return@post
        }
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
                TalerIncomingPaymentEntry.find {
                    TalerIncomingPayments.processed eq false and (TalerIncomingPayments.valid eq false)
                }.forEach {
                    createPain001entry(
                        Pain001Data(
                            creditorName = it.payment.debitorName,
                            creditorIban = it.payment.debitorIban,
                            creditorBic = it.payment.counterpartBic,
                            sum = calculateRefund(it.payment.amount),
                            subject = "Taler refund"
                        ),
                        acctid.id.value
                    )
                    it.processed = true
                }
            }
            return@post
        }
        app.post("/ebics/taler/{id}/digest-incoming-transactions") {
            val id = expectId(call.parameters["id"])
            // first find highest ID value of already processed rows.
            transaction {
                /**
                 * The following query avoids to put a "taler processed" flag-column into
                 * the raw ebics transactions table.  Such table should not contain taler-related
                 * information.
                 *
                 * This latestId value points at the latest id in the _raw transactions table_
                 * that was last processed.  On the other hand, the "row_id" value that the exchange
                 * will get along each history element will be the id in the _digested entries table_.
                 */
                val latestId: Long = TalerIncomingPaymentEntry.all().sortedByDescending {
                    it.payment.id
                }.firstOrNull()?.payment?.id?.value ?: -1
                val subscriberAccount = getBankAccountsInfoFromId(id).first()
                /* search for fresh transactions having the exchange IBAN in the creditor field.  */
                EbicsRawBankTransactionEntry.find {
                    EbicsRawBankTransactionsTable.creditorIban eq subscriberAccount.iban and
                            (EbicsRawBankTransactionsTable.id.greater(latestId))
                }.forEach {
                    if (CryptoUtil.checkValidEddsaPublicKey(it.unstructuredRemittanceInformation)) {
                        TalerIncomingPaymentEntry.new {
                            payment = it
                            valid = true
                        }
                    } else {
                        TalerIncomingPaymentEntry.new {
                            payment = it
                            valid = false
                        }
                    }
                }
            }
            call.respondText (
                "New raw payments Taler-processed",
                ContentType.Text.Plain,
                HttpStatusCode.OK
            )
            return@post
        }
        app.get("/taler/history/outgoing") {
            /* sanitize URL arguments */
            val subscriberId = authenticateRequest(call.request.headers["Authorization"])
            val delta: Int = expectInt(call.expectUrlParameter("delta"))
            val start: Long = handleStartArgument(call.request.queryParameters["start"], delta)
            val startCmpOp = getComparisonOperator(delta, start)
            /* retrieve database elements */
            val history = TalerOutgoingHistory()
            transaction {
                /** Retrieve all the outgoing payments from the _raw transactions table_ */
                val subscriberBankAccount = getBankAccountsInfoFromId(subscriberId)
                EbicsRawBankTransactionEntry.find {
                    EbicsRawBankTransactionsTable.debitorIban eq subscriberBankAccount.first().iban and startCmpOp
                }.orderTaler(delta).subList(0, abs(delta)).forEach {
                    history.outgoing_transactions.add(
                        TalerOutgoingBankTransaction(
                            row_id = it.id.value,
                            amount = "${it.currency}:${it.amount}",
                            wtid = it.unstructuredRemittanceInformation,
                            date = parseDate(it.bookingDate).millis / 1000,
                            credit_account = it.creditorIban,
                            debit_account = it.debitorIban,
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
        app.get("/taler/history/incoming") {
            val subscriberId = authenticateRequest(call.request.headers["Authorization"])
            val delta: Int = expectInt(call.expectUrlParameter("delta"))
            val start: Long = handleStartArgument(call.request.queryParameters["start"], delta)
            val history = TalerIncomingHistory()
            val startCmpOp = getComparisonOperator(delta, start)
            transaction {
                val subscriberBankAccount = getBankAccountsInfoFromId(subscriberId)
                TalerIncomingPaymentEntry.find {
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