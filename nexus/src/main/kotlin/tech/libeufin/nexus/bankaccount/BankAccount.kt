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
import org.jetbrains.exposed.sql.transactions.transaction
import org.w3c.dom.Document
import tech.libeufin.nexus.*
import tech.libeufin.nexus.ebics.fetchEbicsBySpec
import tech.libeufin.nexus.ebics.submitEbicsPaymentInitiation
import tech.libeufin.nexus.server.FetchSpecJson
import tech.libeufin.nexus.server.Pain001Data
import tech.libeufin.util.XMLUtil
import java.time.Instant


suspend fun submitPaymentInitiation(httpClient: HttpClient, paymentInitiationId: Long) {
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
suspend fun submitAllPaymentInitiations(httpClient: HttpClient) {
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
        submitPaymentInitiation(httpClient, it.id)
    }
}


/**
 * Check if the transaction is already found in the database.
 */
private fun findDuplicate(bankAccountId: String, acctSvcrRef: String): NexusBankTransactionEntity? {
    // FIXME: make this generic depending on transaction identification scheme
    val ati = "AcctSvcrRef:$acctSvcrRef"
    return transaction {
        NexusBankTransactionEntity.find {
            (NexusBankTransactionsTable.accountTransactionId eq ati) and (NexusBankTransactionsTable.bankAccount eq bankAccountId)
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

            val rawEntity = NexusBankTransactionEntity.new {
                bankAccount = acct
                accountTransactionId = "AcctSvcrRef:$acctSvcrRef"
                amount = tx.amount
                currency = tx.currency
                transactionJson = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tx)
                creditDebitIndicator = tx.creditDebitIndicator.name
                status = tx.status
            }
            rawEntity.flush()
            if (tx.creditDebitIndicator == CreditDebitIndicator.DBIT) {
                val t0 = tx.details.getOrNull(0)
                val msgId = t0?.references?.messageIdentification
                val pmtInfId = t0?.references?.paymentInformationIdentification
                if (t0 != null && msgId != null && pmtInfId != null) {
                    val paymentInitiation = PaymentInitiationEntity.find {
                        (PaymentInitiationsTable.messageId eq msgId) and
                                (PaymentInitiationsTable.bankAccount eq acct.id) and
                                (PaymentInitiationsTable.paymentInformationId eq pmtInfId)

                    }.firstOrNull()
                    if (paymentInitiation != null) {
                        paymentInitiation.confirmationTransaction = rawEntity
                    }
                }
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
 * Retrieve payment initiation from database, raising exception if not found.
 */
fun getPaymentInitiation(uuid: Long): PaymentInitiationEntity {
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
fun addPaymentInitiation(paymentData: Pain001Data, debitorAccount: NexusBankAccountEntity): PaymentInitiationEntity {
    return transaction {
        val now = Instant.now().toEpochMilli()
        val nowHex = now.toString(16)
        val painCounter = debitorAccount.pain001Counter++
        val painHex = painCounter.toString(16)
        val acctHex = debitorAccount.id.hashCode().toString(16).substring(0, 4)
        PaymentInitiationEntity.new {
            bankAccount = debitorAccount
            subject = paymentData.subject
            sum = paymentData.sum
            creditorName = paymentData.creditorName
            creditorBic = paymentData.creditorBic
            creditorIban = paymentData.creditorIban
            preparationDate = now
            messageId = "leuf-mp1-$nowHex-$painHex-$acctHex"
            endToEndId = "leuf-e-$nowHex-$painHex-$acctHex"
            paymentInformationId = "leuf-p-$nowHex-$painHex-$acctHex"
            instructionId = "leuf-i-$nowHex-$painHex-$acctHex"
        }
    }
}

suspend fun fetchTransactionsInternal(
    client: HttpClient,
    fetchSpec: FetchSpecJson,
    userId: String,
    accountid: String
) {
    val res = transaction {
        val acct = NexusBankAccountEntity.findById(accountid)
        if (acct == null) {
            throw NexusError(
                HttpStatusCode.NotFound,
                "Account not found"
            )
        }
        val conn = acct.defaultBankConnection
        if (conn == null) {
            throw NexusError(
                HttpStatusCode.BadRequest,
                "No default bank connection (explicit connection not yet supported)"
            )
        }
        return@transaction object {
            val connectionType = conn.type
            val connectionName = conn.id.value
        }
    }
    when (res.connectionType) {
        "ebics" -> {
            // FIXME(dold): Support fetching not only the latest transactions.
            // It's not clear what's the nicest way to support this.
            fetchEbicsBySpec(
                fetchSpec,
                client,
                res.connectionName
            )
            ingestBankMessagesIntoAccount(res.connectionName, accountid)
        }
        else -> throw NexusError(
            HttpStatusCode.BadRequest,
            "Connection type '${res.connectionType}' not implemented"
        )
    }
}
