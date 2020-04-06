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
import tech.libeufin.util.CryptoUtil

class Taler(app: Route) {

    init {
        digest(app)
        refund(app)
    }

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