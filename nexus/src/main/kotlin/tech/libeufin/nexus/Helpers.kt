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
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.request.ApplicationRequest
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import org.w3c.dom.Document
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.EbicsTypes
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

fun isProduction(): Boolean {
    return System.getenv("NEXUS_PRODUCTION") != null
}

fun calculateRefund(amount: String): Amount {
    // fixme: must apply refund fees!
    return Amount(amount)
}

/**
 * Skip national only-numeric bank account ids, and return the first IBAN in list
 */
fun extractFirstIban(bankAccounts: List<EbicsTypes.AbstractAccountNumber>?): String? {
    if (bankAccounts == null)
        return null

    for (item in bankAccounts) {
        if (item is EbicsTypes.GeneralAccountNumber) {
            if (item.international)
                return item.value
        }
    }
    return null
}

/**
 * Skip national only-numeric codes, and returns the first BIC in list
 */
fun extractFirstBic(bankCodes: List<EbicsTypes.AbstractBankCode>?): String? {
    if (bankCodes == null)
        return null

    for (item in bankCodes) {
        if (item is EbicsTypes.GeneralBankCode) {
            if (item.international)
                return item.value
        }
    }
    return null
}


fun getEbicsSubscriberDetailsInternal(subscriber: EbicsSubscriberEntity): EbicsClientSubscriberDetails {
    var bankAuthPubValue: RSAPublicKey? = null
    if (subscriber.bankAuthenticationPublicKey != null) {
        bankAuthPubValue = CryptoUtil.loadRsaPublicKey(
            subscriber.bankAuthenticationPublicKey?.bytes!!
        )
    }
    var bankEncPubValue: RSAPublicKey? = null
    if (subscriber.bankEncryptionPublicKey != null) {
        bankEncPubValue = CryptoUtil.loadRsaPublicKey(
            subscriber.bankEncryptionPublicKey?.bytes!!
        )
    }
    return EbicsClientSubscriberDetails(
        bankAuthPub = bankAuthPubValue,
        bankEncPub = bankEncPubValue,

        ebicsUrl = subscriber.ebicsURL,
        hostId = subscriber.hostID,
        userId = subscriber.userID,
        partnerId = subscriber.partnerID,

        customerSignPriv = CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.bytes),
        customerAuthPriv = CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.bytes),
        customerEncPriv = CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.bytes),
        ebicsIniState = subscriber.ebicsIniState,
        ebicsHiaState = subscriber.ebicsHiaState
    )
}

/**
 * Retrieve Ebics subscriber details given a Transport
 * object and handling the default case (when this latter is null).
 */
fun getEbicsSubscriberDetails(userId: String, transportId: String): EbicsClientSubscriberDetails {
    val transport = NexusBankConnectionEntity.findById(transportId)
    if (transport == null) {
        throw NexusError(HttpStatusCode.NotFound, "transport not found")
    }
    val subscriber = EbicsSubscriberEntity.find { EbicsSubscribersTable.nexusBankConnection eq transport.id }.first()
    // transport exists and belongs to caller.
    return getEbicsSubscriberDetailsInternal(subscriber)
}

/**
 * Check if the transaction is already found in the database.
 */
private fun isDuplicate(acctSvcrRef: String): Boolean {
    // FIXME: make this generic depending on transaction identification scheme
    val ati = "AcctSvcrRef:$acctSvcrRef"
    return transaction {
        val res = RawBankTransactionEntity.find {
            RawBankTransactionsTable.accountTransactionId eq ati
        }.firstOrNull()
        res != null
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
        for (tx in transactions) {
            val acctSvcrRef = tx.accountServicerReference
            if (acctSvcrRef == null) {
                // FIXME(dold): Report this!
                logger.error("missing account servicer reference in transaction")
                continue
            }
            if (isDuplicate(acctSvcrRef)) {
                logger.info("Processing a duplicate, not storing it.")
                return@transaction
            }
            RawBankTransactionEntity.new {
                bankAccount = acct
                accountTransactionId = "AcctSvcrRef:$acctSvcrRef"
                amount = tx.amount
                currency = tx.currency
                transactionJson = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tx)
                creditDebitIndicator = tx.creditDebitIndicator.name
                status = tx.status.name
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
 * Fetch EBICS C5x and store it locally, but do not update bank accounts.
 */
suspend fun fetchEbicsC5x(
    historyType: String,
    client: HttpClient,
    bankConnectionId: String,
    start: String?, // dashed date YYYY-MM(01-12)-DD(01-31)
    end: String?, // dashed date YYYY-MM(01-12)-DD(01-31)
    subscriberDetails: EbicsClientSubscriberDetails
) {
    val orderParamsJson = EbicsStandardOrderParamsJson(
        EbicsDateRangeJson(start, end)
    )
    val response = doEbicsDownloadTransaction(
        client,
        subscriberDetails,
        historyType,
        orderParamsJson.toOrderParams()
    )
    when (historyType) {
        "C52" -> {}
        "C53" -> {}
        else -> {
            throw NexusError(HttpStatusCode.BadRequest, "history type '$historyType' not supported")
        }
    }
    when (response) {
        is EbicsDownloadSuccessResult -> {
            response.orderData.unzipWithLambda {
                logger.debug("Camt entry: ${it.second}")
                val camt53doc = XMLUtil.parseStringIntoDom(it.second)
                val msgId = camt53doc.pickStringWithRootNs("/*[1]/*[1]/root:GrpHdr/root:MsgId")
                logger.info("msg id $msgId")
                transaction {
                    val conn = NexusBankConnectionEntity.findById(bankConnectionId)
                    if (conn == null) {
                        throw NexusError(HttpStatusCode.InternalServerError, "bank connection missing")
                    }
                    val oldMsg = NexusBankMessageEntity.find { NexusBankMessagesTable.messageId eq msgId }.firstOrNull()
                    if (oldMsg == null) {
                        NexusBankMessageEntity.new {
                            this.bankConnection = conn
                            this.code = historyType
                            this.messageId = msgId
                            this.message = ExposedBlob(it.second.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
        }
        is EbicsDownloadBankErrorResult -> {
            throw NexusError(
                HttpStatusCode.BadGateway,
                response.returnCode.errorCode
            )
        }
    }
}

/**
 * Create a PAIN.001 XML document according to the input data.
 * Needs to be called within a transaction block.
 */
fun createPain001document(paymentData: PreparedPaymentEntity): String {
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
fun getPreparedPayment(uuid: String): PreparedPaymentEntity {
    return transaction {
        PreparedPaymentEntity.findById(uuid)
    } ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Payment '$uuid' not found"
    )
}

fun getNexusUser(id: String): NexusUserEntity {
    return transaction {
        NexusUserEntity.findById(id)
    } ?: throw NexusError(
        HttpStatusCode.NotFound,
        "User '$id' not found"
    )
}

/**
 * Insert one row in the database, and leaves it marked as non-submitted.
 * @param debtorAccountId the mnemonic id assigned by the bank to one bank
 * account of the subscriber that is creating the pain entity.  In this case,
 * it will be the account whose money will pay the wire transfer being defined
 * by this pain document.
 */
fun addPreparedPayment(paymentData: Pain001Data, debitorAccount: NexusBankAccountEntity): PreparedPaymentEntity {
    val randomId = Random().nextLong()
    return transaction {
        PreparedPaymentEntity.new(randomId.toString()) {
            subject = paymentData.subject
            sum = paymentData.sum
            debitorIban = debitorAccount.iban
            debitorBic = debitorAccount.bankCode
            debitorName = debitorAccount.accountHolder
            creditorName = paymentData.creditorName
            creditorBic = paymentData.creditorBic
            creditorIban = paymentData.creditorIban
            preparationDate = Instant.now().toEpochMilli()
            paymentId = randomId
            endToEndId = randomId
        }
    }
}

fun ensureNonNull(param: String?): String {
    return param ?: throw NexusError(
        HttpStatusCode.BadRequest, "Bad ID given"
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

/**
 * Test HTTP basic auth.  Throws error if password is wrong,
 * and makes sure that the user exists in the system.
 *
 * @param authorization the Authorization:-header line.
 * @return user id
 */
fun authenticateRequest(request: ApplicationRequest): NexusUserEntity {
    val authorization = request.headers["Authorization"]
    val headerLine = if (authorization == null) throw NexusError(
        HttpStatusCode.BadRequest, "Authentication:-header line not found"
    ) else authorization
    val (username, password) = extractUserAndPassword(headerLine)
    val user = NexusUserEntity.find {
        NexusUsersTable.id eq username
    }.firstOrNull()
    if (user == null) {
        throw NexusError(HttpStatusCode.Unauthorized, "Unknown user '$username'")
    }
    if (!CryptoUtil.checkpw(password, user.passwordHash)) {
        throw NexusError(HttpStatusCode.Forbidden, "Wrong password")
    }
    return user
}
