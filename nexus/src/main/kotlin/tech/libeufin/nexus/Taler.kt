/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

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
import tech.libeufin.nexus.bankaccount.addPaymentInitiation
import tech.libeufin.nexus.iso20022.CamtBankAccountEntry
import tech.libeufin.nexus.iso20022.CreditDebitIndicator
import tech.libeufin.nexus.iso20022.EntryStatus
import tech.libeufin.nexus.iso20022.TransactionDetails
import tech.libeufin.nexus.server.Pain001Data
import tech.libeufin.nexus.server.authenticateRequest
import tech.libeufin.nexus.server.expectNonNull
import tech.libeufin.nexus.server.expectUrlParameter
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.EbicsProtocolError
import tech.libeufin.util.parseAmount
import tech.libeufin.util.parsePayto
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

/** Sort query results in descending order for negative deltas, and ascending otherwise.  */
fun <T : Entity<Long>> SizedIterable<T>.orderTaler(delta: Int): List<T> {
    return if (delta < 0) {
        this.sortedByDescending { it.id }
    } else {
        this.sortedBy { it.id }
    }
}

/**
 * Build an IBAN payto URI.
 */
fun buildIbanPaytoUri(iban: String, bic: String?, name: String): String {
    if (bic != null) {
        return "payto://iban/$bic/$iban?receiver-name=$name"
    } else {
        return "payto://iban/$iban?receiver-name=$name"
    }
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
 * Tries to extract a valid reserve public key from the raw subject line
 */
fun extractReservePubFromSubject(rawSubject: String): String? {
    val re = "\\b[a-z0-9A-Z]{52}\\b".toRegex()
    val result = re.find(rawSubject) ?: return null
    return result.value.toUpperCase()
}

private fun getTalerFacadeState(fcid: String): TalerFacadeStateEntity {
    val facade = FacadeEntity.find { FacadesTable.id eq fcid }.firstOrNull() ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Could not find facade '${fcid}'"
    )
    val facadeState = TalerFacadeStateEntity.find {
        TalerFacadeStateTable.facade eq facade.id.value
    }.firstOrNull() ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Could not find any state for facade: ${fcid}"
    )
    return facadeState
}

private fun getTalerFacadeBankAccount(fcid: String): NexusBankAccountEntity {
    val facade = FacadeEntity.find { FacadesTable.id eq fcid }.firstOrNull() ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Could not find facade '${fcid}'"
    )
    val facadeState = TalerFacadeStateEntity.find {
        TalerFacadeStateTable.facade eq facade.id.value
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

/**
 * Handle a Taler Wire Gateway /transfer request.
 */
private suspend fun talerTransfer(call: ApplicationCall) {
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
        val pain001 = addPaymentInitiation(
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

/**
 * Serve a /taler/admin/add-incoming
 */
private suspend fun talerAddIncoming(call: ApplicationCall, httpClient: HttpClient): Unit {
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


private fun ingestIncoming(payment: NexusBankTransactionEntity, txDtls: TransactionDetails) {
    val subject = txDtls.unstructuredRemittanceInformation
    val debtorName = txDtls.debtor?.name
    if (debtorName == null) {
        logger.warn("empty debtor name")
        return
    }
    val debtorAcct = txDtls.debtorAccount
    if (debtorAcct == null) {
        // FIXME: Report payment, we can't even send it back
        logger.warn("empty debitor account")
        return
    }
    val debtorIban = debtorAcct.iban
    if (debtorIban == null) {
        // FIXME: Report payment, we can't even send it back
        logger.warn("non-iban debitor account")
        return
    }
    val debtorAgent = txDtls.debtorAgent
    if (debtorAgent == null) {
        // FIXME: Report payment, we can't even send it back
        logger.warn("missing debitor agent")
        return
    }
    val reservePub = extractReservePubFromSubject(subject)
    if (reservePub == null) {
        // FIXME: send back!
        logger.warn("could not find reserve pub in remittance information")
        return
    }
    if (!CryptoUtil.checkValidEddsaPublicKey(reservePub)) {
        // FIXME: send back!
        logger.warn("invalid public key")
        return
    }
    TalerIncomingPaymentEntity.new {
        this.payment = payment
        reservePublicKey = reservePub
        timestampMs = System.currentTimeMillis()
        incomingPaytoUri = buildIbanPaytoUri(debtorIban, debtorAgent.bic, debtorName)
    }
    return
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
        logger.debug("Ingesting transactions for Taler facade ${facade.id.value}")
        val facadeState = getTalerFacadeState(facade.id.value)
        var lastId = facadeState.highestSeenMsgID
        NexusBankTransactionEntity.find {
            /** Those with exchange bank account involved */
            NexusBankTransactionsTable.bankAccount eq subscriberAccount.id.value and
                    /** Those that are booked */
                    (NexusBankTransactionsTable.status eq EntryStatus.BOOK) and
                    /** Those that came later than the latest processed payment */
                    (NexusBankTransactionsTable.id.greater(lastId))
        }.orderBy(Pair(NexusBankTransactionsTable.id, SortOrder.ASC)).forEach {
            // Incoming payment.
            val tx = jacksonObjectMapper().readValue(it.transactionJson, CamtBankAccountEntry::class.java)
            val txDetails = tx.details
            if (txDetails == null) {
                // We don't support batch transactions at the moment!
                logger.warn("batch transactions not supported")
            } else {
                when (tx.creditDebitIndicator) {
                    CreditDebitIndicator.CRDT -> ingestIncoming(it, txDtls = txDetails)
                }
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

/**
 * Handle a /taler/history/outgoing request.
 */
private suspend fun historyOutgoing(call: ApplicationCall) {
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
        val reqPayments = mutableListOf<TalerRequestedPaymentEntity>()
        val reqPaymentsWithUnconfirmed = TalerRequestedPaymentEntity.find {
            startCmpOp
        }.orderTaler(delta)
        reqPaymentsWithUnconfirmed.forEach {
            if (it.preparedPayment.confirmationTransaction != null) {
                reqPayments.add(it)
            }
        }
        if (reqPayments.isNotEmpty()) {
            reqPayments.subList(0, min(abs(delta), reqPayments.size)).forEach {
                history.outgoing_transactions.add(
                    TalerOutgoingBankTransaction(
                        row_id = it.id.value,
                        amount = it.amount,
                        wtid = it.wtid,
                        date = GnunetTimestamp(it.preparedPayment.preparationDate),
                        credit_account = it.creditAccount,
                        debit_account = buildIbanPaytoUri(
                            subscriberBankAccount.iban,
                            subscriberBankAccount.bankCode,
                            subscriberBankAccount.accountHolder
                        ),
                        exchange_base_url = "FIXME-to-request-along-subscriber-registration"
                    )
                )
            }
        }
    }
    call.respond(TextContent(customConverter(history), ContentType.Application.Json))
}

/**
 * Handle a /taler/history/incoming request.
 */
private suspend fun historyIncoming(call: ApplicationCall): Unit {
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
            startCmpOp
        }.orderTaler(delta)
        if (orderedPayments.isNotEmpty()) {
            orderedPayments.subList(0, min(abs(delta), orderedPayments.size)).forEach {
                history.incoming_transactions.add(
                    TalerIncomingBankTransaction(
                        // Rounded timestamp
                        date = GnunetTimestamp((it.timestampMs / 1000) * 1000),
                        row_id = it.id.value,
                        amount = "${it.payment.currency}:${it.payment.amount}",
                        reserve_pub = it.reservePublicKey,
                        credit_account = buildIbanPaytoUri(
                            it.payment.bankAccount.iban,
                            it.payment.bankAccount.bankCode,
                            it.payment.bankAccount.accountHolder
                        ),
                        debit_account = it.incomingPaytoUri
                    )
                )
            }
        }
    }
    return call.respond(TextContent(customConverter(history), ContentType.Application.Json))
}

fun talerFacadeRoutes(route: Route, httpClient: HttpClient) {
    route.post("/transfer") {
        talerTransfer(call)
        return@post
    }
    route.post("/admin/add-incoming") {
        talerAddIncoming(call, httpClient)
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