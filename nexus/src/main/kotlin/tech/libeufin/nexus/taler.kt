package tech.libeufin.nexus

import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.post
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.util.Amount
import tech.libeufin.util.CryptoUtil

class Taler(app: Route) {

    init {
        /** transform raw CAMT.053 payment records to more Taler-friendly
         * database rows. */
        digest(app)

        /** process the incoming payments, and craft refund payments (although
         * do not execute them) for those incoming payments that had a wrong
         * (!= public key) subject. */
        refund(app)

        /**
         * NOTE: Taler exchanges do authenticate via the HTTP Basic auth mechanism,
         * which is currently _missing_ in the nexus.  Therefore, a mapping from auth
         * header lines to ebics_subscriber needs to be implemented!
         */
    }

    /**
     * Payment initiating data structures: one endpoint "$BASE_URL/transfer".
     */
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

    /**
     * History accounting data structures
     */

    /**
     * Incoming payments.
     */
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

    /**
     * Outgoing payments.
     */
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

    /**
     * Test APIs' data structures.
     */
    private data class TalerAdminAddIncoming(
        val amount: String,
        val reserve_pub: String,
        val debit_account: String
    )

    private data class TalerAddIncomingResponse(
        val timestamp: Long,
        val row_id: Long
    )

    fun digest(app: Route) {
        app.post("/ebics/taler/{id}/digest-incoming-transactions") {
            val id = expectId(call.parameters["id"])
            // first find highest ID value of already processed rows.
            transaction {
                // avoid re-processing raw payments
                val latest = TalerIncomingPaymentEntry.all().sortedByDescending {
                    it.payment.id
                }.firstOrNull()

                val payments = if (latest == null) {
                    EbicsRawBankTransactionEntry.find {
                        EbicsRawBankTransactionsTable.nexusSubscriber eq id
                    }
                } else {
                    EbicsRawBankTransactionEntry.find {
                        EbicsRawBankTransactionsTable.id.greater(latest.id) and
                                (EbicsRawBankTransactionsTable.nexusSubscriber eq id)
                    }
                }
                payments.forEach {
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
    }

    fun refund(app: Route) {

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
    }
}