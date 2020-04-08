package tech.libeufin.nexus

import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.post
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.base64ToBytes
import java.lang.Exception
import javax.sql.rowset.serial.SerialBlob

/**
 * This helper function parses a Authorization:-header line, decode the credentials
 * and returns a pair made of username and hashed (sha256) password.  The hashed value
 * will then be compared with the one kept into the database.
 */
fun extractUserAndHashedPassword(authorizationHeader: String): Pair<String, ByteArray> {
    val (username, password) = try {
        val split = authorizationHeader.split(" ")
        val valueUtf8 = String(base64ToBytes(split[1]), Charsets.UTF_8) // newline introduced here: BUG!
        valueUtf8.split(":")
    } catch (e: Exception) {
        throw NexusError(
            HttpStatusCode.BadRequest, "invalid Authorization:-header received"
        )
    }
    return Pair(username, CryptoUtil.hashStringSHA256(password))
}

class Taler(app: Route) {

    init {
        /** transform raw CAMT.053 payment records to more Taler-friendly
         * database rows. */
        digest(app)

        /** process the incoming payments, and craft refund payments (although
         * do not execute them) for those incoming payments that had a wrong
         * (!= public key) subject. */
        refund(app)

        /** Tester for HTTP basic auth. */
        testAuth(app)
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

    private fun SizedIterable<TalerIncomingPaymentEntry>.orderTaler(start: Long): List<TalerIncomingPaymentEntry> {
        return if (start < 0) {
            this.sortedByDescending { it.id }
        } else {
            this.sortedBy { it.id }
        }
    }

    /**
     * Test HTTP basic auth.  Throws error if password is wrong
     *
     * @param authorization the Authorization:-header line.
     * @return subscriber id
     */
    private fun authenticateRequest(authorization: String?): String {
        val headerLine = authorization ?: throw NexusError(
            HttpStatusCode.BadRequest, "Authentication:-header line not found"
        )
        logger.debug("Checking for authorization: $headerLine")
        val subscriber = transaction {
            val (user, pass) = extractUserAndHashedPassword(headerLine)
            EbicsSubscriberEntity.find {
                EbicsSubscribersTable.id eq user and (EbicsSubscribersTable.password eq SerialBlob(pass))
            }.firstOrNull()
        } ?: throw NexusError(HttpStatusCode.Forbidden, "Wrong password")
        return subscriber.id.value
    }

    /**
     * Implement the Taler wire API transfer method.
     */
    private fun transfer(app: Route) {

    }

    private fun getPaytoUri(name: String, iban: String, bic: String): String {
        return "payto://$iban/$bic?receiver-name=$name"
    }

    /**
     * Builds the comparison operator for history entries based on the
     * sign of 'delta'
     */
    private fun getComparisonOperator(delta: Long, start: Long): Op<Boolean> {
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
    private fun handleStartArgument(start: String?, delta: Long): Long {
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
     * Respond with ONLY the good transfer made to the exchange.
     * A 'good' transfer is one whose subject line is a plausible
     * EdDSA public key encoded in Crockford base32.
     */
    private fun historyIncoming(app: Route) {
        app.get("/taler/history/incoming") {
            val subscriberId = authenticateRequest(call.request.headers["Authorization"])
            val delta: Long = expectLong(call.expectUrlParameter("delta"))
            val start: Long = handleStartArgument(call.request.queryParameters["start"], delta)
            val history = TalerIncomingHistory()
            val cmpOp = getComparisonOperator(delta, start)
            transaction {
                val subscriberBankAccount = getBankAccountsInfoFromId(subscriberId)
                TalerIncomingPaymentEntry.find {
                    TalerIncomingPayments.valid eq true and cmpOp
                }.orderTaler(start).forEach {
                    history.incoming_transactions.add(
                        TalerIncomingBankTransaction(
                            date = DateTime.parse(it.payment.bookingDate, DateTimeFormat.forPattern("YYYY-MM-DD")).millis,
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

    /**
     * Respond with all the transfers that the exchange made to merchants.
     * It can include also those transfers made to reimburse some invalid
     * incoming payment.
     */
    private fun historyOutgoing(app: Route) {

    }

    private fun testAuth(app: Route) {
        app.get("/taler/test-auth") {
            authenticateRequest(call.request.headers["Authorization"])
            call.respondText("Authenticated!", ContentType.Text.Plain, HttpStatusCode.OK)
            return@get
        }
    }

    private fun digest(app: Route) {
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
    }

    private fun refund(app: Route) {
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