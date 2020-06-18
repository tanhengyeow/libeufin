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

package tech.libeufin.nexus.bankaccount

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.not
import org.jetbrains.exposed.sql.transactions.transaction
import org.w3c.dom.Document
import tech.libeufin.nexus.*
import tech.libeufin.nexus.ebics.submitEbicsPaymentInitiation
import tech.libeufin.util.XMLUtil
import java.time.Instant


suspend fun submitPreparedPayment(httpClient: HttpClient, paymentInitiationId: Long) {
    val r = transaction {
        val paymentInitiation = PaymentInitiationEntity.findById(paymentInitiationId)
        if (paymentInitiation == null) {
            throw NexusError(HttpStatusCode.NotFound, "prepared payment not found")
        }
        object {
            val type = paymentInitiation.bankAccount.defaultBankConnection?.type
            val submitted = paymentInitiation.submitted
        }
    }
    if (r.submitted) {
        return
    }
    when (r.type) {
        null -> throw NexusError(HttpStatusCode.NotFound, "no default bank connection")
        "ebics" -> submitEbicsPaymentInitiation(httpClient, paymentInitiationId)
    }
}

/**
 * Submit all pending prepared payments.
 */
suspend fun submitAllPreparedPayments(httpClient: HttpClient) {
    data class Submission(
        val id: Long
    )
    logger.debug("auto-submitter started")
    val workQueue = mutableListOf<Submission>()
    transaction {
        PaymentInitiationEntity.find {
            PaymentInitiationsTable.submitted eq false
        }.forEach {
            val defaultBankConnectionId = it.bankAccount.defaultBankConnection?.id ?: throw NexusError(
                HttpStatusCode.BadRequest,
                "needs default bank connection"
            )
            val bankConnection = NexusBankConnectionEntity.findById(defaultBankConnectionId) ?: throw NexusError(
                HttpStatusCode.InternalServerError,
                "Bank account '${it.id.value}' doesn't map to any bank connection (named '${defaultBankConnectionId}')"
            )
            if (bankConnection.type != "ebics") {
                logger.info("Skipping non-implemented bank connection '${bankConnection.type}'")
                return@forEach
            }
            workQueue.add(Submission(it.id.value))
        }
    }
    workQueue.forEach {
        submitPreparedPayment(httpClient, it.id)
    }
}


/**
 * Check if the transaction is already found in the database.
 */
private fun findDuplicate(bankAccountId: String, acctSvcrRef: String): RawBankTransactionEntity? {
    // FIXME: make this generic depending on transaction identification scheme
    val ati = "AcctSvcrRef:$acctSvcrRef"
    return transaction {
        RawBankTransactionEntity.find {
            (RawBankTransactionsTable.accountTransactionId eq ati) and (RawBankTransactionsTable.bankAccount eq bankAccountId)
        }.firstOrNull()
    }
}

fun processCamtMessage(
    bankAccountId: String,
    camtDoc: Document
) {
    logger.info("processing CAMT message")
    transaction {
        val acct = NexusBankAccountEntity.findById(bankAccountId)
        if (acct == null) {
            throw NexusError(HttpStatusCode.NotFound, "user not found")
        }
        val transactions = getTransactions(camtDoc)
        logger.info("found ${transactions.size} transactions")
        txloop@ for (tx in transactions) {
            val acctSvcrRef = tx.accountServicerReference
            if (acctSvcrRef == null) {
                // FIXME(dold): Report this!
                logger.error("missing account servicer reference in transaction")
                continue
            }
            val duplicate = findDuplicate(bankAccountId, acctSvcrRef)
            if (duplicate != null) {
                // FIXME(dold): See if an old transaction needs to be superseded by this one
                // https://bugs.gnunet.org/view.php?id=6381
                break
            }

            val rawEntity = RawBankTransactionEntity.new {
                bankAccount = acct
                accountTransactionId = "AcctSvcrRef:$acctSvcrRef"
                amount = tx.amount
                currency = tx.currency
                transactionJson = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tx)
                creditDebitIndicator = tx.creditDebitIndicator.name
                status = tx.status
            }
            if (tx.creditDebitIndicator == CreditDebitIndicator.DBIT) {
                // FIXME: find matching PaymentInitiation by PaymentInformationID, message ID or whatever is present
            }
        }
    }
}

/**
 * Create new transactions for an account based on bank messages it
 * did not see before.
 */
fun ingestBankMessagesIntoAccount(
    bankConnectionId: String,
    bankAccountId: String
) {
    transaction {
        val conn = NexusBankConnectionEntity.findById(bankConnectionId)
        if (conn == null) {
            throw NexusError(HttpStatusCode.InternalServerError, "connection not found")
        }
        val acct = NexusBankAccountEntity.findById(bankAccountId)
        if (acct == null) {
            throw NexusError(HttpStatusCode.InternalServerError, "account not found")
        }
        var lastId = acct.highestSeenBankMessageId
        NexusBankMessageEntity.find {
            (NexusBankMessagesTable.bankConnection eq conn.id) and
                    (NexusBankMessagesTable.id greater acct.highestSeenBankMessageId)
        }.orderBy(Pair(NexusBankMessagesTable.id, SortOrder.ASC)).forEach {
            // FIXME: check if it's CAMT first!
            val doc = XMLUtil.parseStringIntoDom(it.message.bytes.toString(Charsets.UTF_8))
            processCamtMessage(bankAccountId, doc)
            lastId = it.id.value
        }
        acct.highestSeenBankMessageId = lastId
    }
}

/**
 * Retrieve prepared payment from database, raising exception
 * if not found.
 */
fun getPreparedPayment(uuid: Long): PaymentInitiationEntity {
    return transaction {
        PaymentInitiationEntity.findById(uuid)
    } ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Payment '$uuid' not found"
    )
}


/**
 * Insert one row in the database, and leaves it marked as non-submitted.
 * @param debtorAccountId the mnemonic id assigned by the bank to one bank
 * account of the subscriber that is creating the pain entity.  In this case,
 * it will be the account whose money will pay the wire transfer being defined
 * by this pain document.
 */
fun addPreparedPayment(paymentData: Pain001Data, debitorAccount: NexusBankAccountEntity): PaymentInitiationEntity {
    val now = Instant.now().toEpochMilli()
    val nowHex = now.toString(16)
    return transaction {
        PaymentInitiationEntity.new {
            bankAccount = debitorAccount
            subject = paymentData.subject
            sum = paymentData.sum
            creditorName = paymentData.creditorName
            creditorBic = paymentData.creditorBic
            creditorIban = paymentData.creditorIban
            preparationDate = now
            messageId = "leuf-m-pain1-$nowHex"
            endToEndId = "leuf-e-$nowHex"
            paymentInformationId = "leuf-p-$nowHex"
            instructionId = "leuf-i-$nowHex"
        }
    }
}
