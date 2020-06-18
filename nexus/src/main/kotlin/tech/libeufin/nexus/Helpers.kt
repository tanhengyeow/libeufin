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
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.w3c.dom.Document
import tech.libeufin.util.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter


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

/**
 * retrieves the initiated payment and marks it as "performed
 * by the bank".  This avoids to submit it again.
 */
fun markInitiatedAsConfirmed(subject: String, debtorIban: String, rawUuid: Long) {
    // not introducing a 'transaction {}' block since
    // this function should be always be invoked from one.
    val initiatedPayment = PaymentInitiationEntity.find {
        PaymentInitiationsTable.subject eq subject and
                (PaymentInitiationsTable.debitorIban eq debtorIban)
    }.firstOrNull()
    if (initiatedPayment == null) {
        logger.info("Payment '$subject' was never programmatically prepared")
        return
    }
    val rawEntity = RawBankTransactionEntity.findById(rawUuid) ?: throw NexusError(
        HttpStatusCode.InternalServerError, "Raw payment '$rawUuid' disappeared from database"
    )
    initiatedPayment.rawConfirmation = rawEntity
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
        txloop@for (tx in transactions) {
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
                // assuming batches contain always one element, as aren't fully
                // implemented now.
                val uniqueBatchElement = tx.details.get(0)
                markInitiatedAsConfirmed(
                    // if the user has two initiated payments under the same
                    // IBAN with the same subject, then this logic will cause
                    // problems.  But a programmatic user should take care of this.
                    uniqueBatchElement.unstructuredRemittanceInformation,
                    if (uniqueBatchElement.relatedParties.debtorAccount !is AccountIdentificationIban) {
                        throw NexusError(
                            HttpStatusCode.InternalServerError,
                            "Parsed CAMT didn't have IBAN in debtor!"
                        )
                    } else {
                        uniqueBatchElement.relatedParties.debtorAccount.iban
                    },
                    rawEntity.id.value
                )
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
 * Create a PAIN.001 XML document according to the input data.
 * Needs to be called within a transaction block.
 */
fun createPain001document(paymentData: PaymentInitiationEntity): String {
    /**
     * Every PAIN.001 document contains at least three IDs:
     *
     * 1) MsgId: a unique id for the message itself
     * 2) PmtInfId: the unique id for the payment's set of information
     * 3) EndToEndId: a unique id to be shared between the debtor and
     *    creditor that uniquely identifies the transaction
     *
     * For now and for simplicity, since every PAIN entry in the database
     * has a unique ID, and the three values aren't required to be mutually different,
     * we'll assign the SAME id (= the row id) to all the three aforementioned
     * PAIN id types.
     */
    val debitorBankAccountLabel = transaction {
        val debitorBankAcount = NexusBankAccountEntity.find {
            NexusBankAccountsTable.iban eq paymentData.debitorIban and
                    (NexusBankAccountsTable.bankCode eq paymentData.debitorBic)
        }.firstOrNull() ?: throw NexusError(
            HttpStatusCode.NotFound,
            "Please download bank accounts details first (HTD)"
        )
        debitorBankAcount.id.value
    }

    val s = constructXml(indent = true) {
        root("Document") {
            attribute("xmlns", "urn:iso:std:iso:20022:tech:xsd:pain.001.001.03")
            attribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            attribute("xsi:schemaLocation", "urn:iso:std:iso:20022:tech:xsd:pain.001.001.03 pain.001.001.03.xsd")
            element("CstmrCdtTrfInitn") {
                element("GrpHdr") {
                    element("MsgId") {
                        text(paymentData.id.value.toString())
                    }
                    element("CreDtTm") {
                        val dateMillis = transaction {
                            paymentData.preparationDate
                        }
                        val dateFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        val instant = Instant.ofEpochSecond(dateMillis / 1000)
                        val zoned = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
                        text(dateFormatter.format(zoned))
                    }
                    element("NbOfTxs") {
                        text("1")
                    }
                    element("CtrlSum") {
                        text(paymentData.sum.toString())
                    }
                    element("InitgPty/Nm") {
                        text(debitorBankAccountLabel)
                    }
                }
                element("PmtInf") {
                    element("PmtInfId") {
                        text(paymentData.id.value.toString())
                    }
                    element("PmtMtd") {
                        text("TRF")
                    }
                    element("BtchBookg") {
                        text("true")
                    }
                    element("NbOfTxs") {
                        text("1")
                    }
                    element("CtrlSum") {
                        text(paymentData.sum.toString())
                    }
                    element("PmtTpInf/SvcLvl/Cd") {
                        text("SEPA")
                    }
                    element("ReqdExctnDt") {
                        val dateMillis = transaction {
                            paymentData.preparationDate
                        }
                        text(importDateFromMillis(dateMillis).toDashedDate())
                    }
                    element("Dbtr/Nm") {
                        text(debitorBankAccountLabel)
                    }
                    element("DbtrAcct/Id/IBAN") {
                        text(paymentData.debitorIban)
                    }
                    element("DbtrAgt/FinInstnId/BIC") {
                        text(paymentData.debitorBic)
                    }
                    element("ChrgBr") {
                        text("SLEV")
                    }
                    element("CdtTrfTxInf") {
                        element("PmtId") {
                            element("EndToEndId") {
                                // text(pain001Entity.id.value.toString())
                                text("NOTPROVIDED")
                            }
                        }
                        element("Amt/InstdAmt") {
                            attribute("Ccy", paymentData.currency)
                            text(paymentData.sum.toString())
                        }
                        element("CdtrAgt/FinInstnId/BIC") {
                            text(paymentData.creditorBic)
                        }
                        element("Cdtr/Nm") {
                            text(paymentData.creditorName)
                        }
                        element("CdtrAcct/Id/IBAN") {
                            text(paymentData.creditorIban)
                        }
                        element("RmtInf/Ustrd") {
                            text(paymentData.subject)
                        }
                    }
                }
            }
        }
    }
    return s
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
    return transaction {
        PaymentInitiationEntity.new {
            bankAccount = debitorAccount
            subject = paymentData.subject
            sum = paymentData.sum
            debitorIban = debitorAccount.iban
            debitorBic = debitorAccount.bankCode
            debitorName = debitorAccount.accountHolder
            creditorName = paymentData.creditorName
            creditorBic = paymentData.creditorBic
            creditorIban = paymentData.creditorIban
            preparationDate = Instant.now().toEpochMilli()
            endToEndId = 0
        }
    }
}

fun ensureNonNull(param: String?): String {
    return param ?: throw NexusError(
        HttpStatusCode.BadRequest, "Bad ID given: ${param}"
    )
}

fun ensureLong(param: String?): Long {
    val asString = ensureNonNull(param)
    return asString.toLongOrNull() ?: throw NexusError(
        HttpStatusCode.BadRequest, "Parameter is not a number: ${param}"
    )
}

/**
 * This helper function parses a Authorization:-header line, decode the credentials
 * and returns a pair made of username and hashed (sha256) password.  The hashed value
 * will then be compared with the one kept into the database.
 */
fun extractUserAndPassword(authorizationHeader: String): Pair<String, String> {
    logger.debug("Authenticating: $authorizationHeader")
    val (username, password) = try {
        val split = authorizationHeader.split(" ")
        val plainUserAndPass = String(base64ToBytes(split[1]), Charsets.UTF_8)
        plainUserAndPass.split(":")
    } catch (e: java.lang.Exception) {
        throw NexusError(
            HttpStatusCode.BadRequest,
            "invalid Authorization:-header received"
        )
    }
    return Pair(username, password)
}