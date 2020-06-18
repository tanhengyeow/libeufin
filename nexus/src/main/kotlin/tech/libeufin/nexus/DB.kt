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

import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.util.EbicsInitState
import tech.libeufin.util.amount
import java.sql.Connection

/**
 * This table holds the values that exchange gave to issue a payment,
 * plus a reference to the prepared pain.001 version of.  Note that
 * whether a pain.001 document was sent or not to the bank is indicated
 * in the PAIN-table.
 */
object TalerRequestedPayments : LongIdTable() {
    // this id gets assigned when the bank confirms the
    // corresponding outgoing payment.  It is "abstract"
    // in the sense that a "early" prepared payment might
    // get a "high" id because the bank confirmed it "late".
    val abstractId = long("abstractId").nullable()
    val preparedPayment = reference("payment", PaymentInitiationsTable)
    val requestUId = text("request_uid")
    val amount = text("amount")
    val exchangeBaseUrl = text("exchange_base_url")
    val wtid = text("wtid")
    val creditAccount = text("credit_account")
}

class TalerRequestedPaymentEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TalerRequestedPaymentEntity>(TalerRequestedPayments)
    var abstractId by TalerRequestedPayments.abstractId
    var preparedPayment by PaymentInitiationEntity referencedOn TalerRequestedPayments.preparedPayment
    var requestUId by TalerRequestedPayments.requestUId
    var amount by TalerRequestedPayments.amount
    var exchangeBaseUrl by TalerRequestedPayments.exchangeBaseUrl
    var wtid by TalerRequestedPayments.wtid
    var creditAccount by TalerRequestedPayments.creditAccount
}

/**
 * This is the table of the incoming payments.  Entries are merely "pointers" to the
 * entries from the raw payments table.  Fixme: name should end with "-table".
 */
object TalerIncomingPayments : LongIdTable() {
    val payment = reference("payment", RawBankTransactionsTable)
    val reservePublicKey = text("reservePublicKey")
    val timestampMs = long("timestampMs")
    val incomingPaytoUri = text("incomingPaytoUri")
}

class TalerIncomingPaymentEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TalerIncomingPaymentEntity>(TalerIncomingPayments)

    var payment by RawBankTransactionEntity referencedOn TalerIncomingPayments.payment
    var reservePublicKey by TalerIncomingPayments.reservePublicKey
    var timestampMs by TalerIncomingPayments.timestampMs
    var incomingPaytoUri by TalerIncomingPayments.incomingPaytoUri
}

/**
 * Table that stores all messages we receive from the bank.
 */
object NexusBankMessagesTable : IntIdTable() {
    val bankConnection = reference("bankConnection", NexusBankConnectionsTable)

    // Unique identifier for the message within the bank connection
    val messageId = text("messageId")
    val code = text("code")
    val message = blob("message")
}

class NexusBankMessageEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<NexusBankMessageEntity>(NexusBankMessagesTable)

    var bankConnection by NexusBankConnectionEntity referencedOn NexusBankMessagesTable.bankConnection
    var messageId by NexusBankMessagesTable.messageId
    var code by NexusBankMessagesTable.code
    var message by NexusBankMessagesTable.message
}

/**
 * This table contains history "elements" as returned by the bank from a
 * CAMT message.
 */
object RawBankTransactionsTable : LongIdTable() {
    /**
     * Identifier for the transaction that is unique among all transactions of the account.
     * The scheme for this identifier is the accounts transaction identification scheme.
     *
     * Note that this is *not* a unique ID per account, as the same underlying
     * transaction can show up multiple times with a different status.
     */
    val accountTransactionId = text("accountTransactionId")

    /**
     * Bank account that this transaction happened on.
     */
    val bankAccount = reference("bankAccount", NexusBankAccountsTable)

    /**
     * Direction of the amount.
     */
    val creditDebitIndicator = text("creditDebitIndicator")

    /**
     * Currency of the amount.
     */
    val currency = text("currency")
    val amount = text("amount")

    /**
     * Booked / pending / informational.
     */
    val status = enumerationByName("status", 16, TransactionStatus::class)

    /**
     * Another, later transaction that updates the status of the current transaction.
     */
    val updatedBy = optReference("updatedBy", RawBankTransactionsTable)

    /**
     * Full details of the transaction in JSON format.
     */
    val transactionJson = text("transactionJson")
}

class RawBankTransactionEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<RawBankTransactionEntity>(RawBankTransactionsTable)
    var currency by RawBankTransactionsTable.currency
    var amount by RawBankTransactionsTable.amount
    var status by RawBankTransactionsTable.status
    var creditDebitIndicator by RawBankTransactionsTable.creditDebitIndicator
    var bankAccount by NexusBankAccountEntity referencedOn RawBankTransactionsTable.bankAccount
    var transactionJson by RawBankTransactionsTable.transactionJson
    var accountTransactionId by RawBankTransactionsTable.accountTransactionId
    val updatedBy by RawBankTransactionEntity optionalReferencedOn RawBankTransactionsTable.updatedBy
}

/**
 * Represents a prepared payment.
 */
object PaymentInitiationsTable : LongIdTable() {
    /**
     * Bank account that wants to initiate the payment.
     */
    val bankAccount = reference("bankAccount", NexusBankAccountsTable)
    val preparationDate = long("preparationDate")
    val submissionDate = long("submissionDate").nullable()
    val sum = amount("sum")
    val currency = varchar("currency", length = 3).default("EUR")
    val endToEndId = long("EndToEndId")
    val subject = text("subject")
    val creditorIban = text("creditorIban")
    val creditorBic = text("creditorBic")
    val creditorName = text("creditorName")
    val debitorIban = text("debitorIban")
    val debitorBic = text("debitorBic")
    val debitorName = text("debitorName").nullable()
    val submitted = bool("submitted").default(false)

    /**
     * Points at the raw transaction witnessing that this
     * initiated payment was successfully performed.
     */
    val rawConfirmation = reference("rawConfirmation", RawBankTransactionsTable).nullable()
}

class PaymentInitiationEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<PaymentInitiationEntity>(PaymentInitiationsTable)

    var bankAccount by NexusBankAccountEntity referencedOn PaymentInitiationsTable.bankAccount
    var preparationDate by PaymentInitiationsTable.preparationDate
    var submissionDate by PaymentInitiationsTable.submissionDate
    var sum by PaymentInitiationsTable.sum
    var currency by PaymentInitiationsTable.currency
    var debitorIban by PaymentInitiationsTable.debitorIban
    var debitorBic by PaymentInitiationsTable.debitorBic
    var debitorName by PaymentInitiationsTable.debitorName
    var endToEndId by PaymentInitiationsTable.endToEndId
    var subject by PaymentInitiationsTable.subject
    var creditorIban by PaymentInitiationsTable.creditorIban
    var creditorBic by PaymentInitiationsTable.creditorBic
    var creditorName by PaymentInitiationsTable.creditorName
    var submitted by PaymentInitiationsTable.submitted
    var rawConfirmation by RawBankTransactionEntity optionalReferencedOn PaymentInitiationsTable.rawConfirmation
}

/**
 * This table holds triples of <iban, bic, holder name>.
 * FIXME(dold):  Allow other account and bank identifications than IBAN and BIC
 */
object NexusBankAccountsTable : IdTable<String>() {
    override val id = text("id").entityId()
    val accountHolder = text("accountHolder")
    val iban = text("iban")
    val bankCode = text("bankCode")
    val defaultBankConnection = reference("defaultBankConnection", NexusBankConnectionsTable).nullable()

    // Highest bank message ID that this bank account is aware of.
    val highestSeenBankMessageId = integer("highestSeenBankMessageId")
}

class NexusBankAccountEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, NexusBankAccountEntity>(NexusBankAccountsTable)

    var accountHolder by NexusBankAccountsTable.accountHolder
    var iban by NexusBankAccountsTable.iban
    var bankCode by NexusBankAccountsTable.bankCode
    var defaultBankConnection by NexusBankConnectionEntity optionalReferencedOn NexusBankAccountsTable.defaultBankConnection
    var highestSeenBankMessageId by NexusBankAccountsTable.highestSeenBankMessageId
}

object EbicsSubscribersTable : IntIdTable() {
    val ebicsURL = text("ebicsURL")
    val hostID = text("hostID")
    val partnerID = text("partnerID")
    val userID = text("userID")
    val systemID = text("systemID").nullable()
    val signaturePrivateKey = blob("signaturePrivateKey")
    val encryptionPrivateKey = blob("encryptionPrivateKey")
    val authenticationPrivateKey = blob("authenticationPrivateKey")
    val bankEncryptionPublicKey = blob("bankEncryptionPublicKey").nullable()
    val bankAuthenticationPublicKey = blob("bankAuthenticationPublicKey").nullable()
    val nexusBankConnection = reference("nexusBankConnection", NexusBankConnectionsTable)
    val ebicsIniState = enumerationByName("ebicsIniState", 16, EbicsInitState::class)
    val ebicsHiaState = enumerationByName("ebicsHiaState", 16, EbicsInitState::class)
}

class EbicsSubscriberEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsSubscriberEntity>(EbicsSubscribersTable)

    var ebicsURL by EbicsSubscribersTable.ebicsURL
    var hostID by EbicsSubscribersTable.hostID
    var partnerID by EbicsSubscribersTable.partnerID
    var userID by EbicsSubscribersTable.userID
    var systemID by EbicsSubscribersTable.systemID
    var signaturePrivateKey by EbicsSubscribersTable.signaturePrivateKey
    var encryptionPrivateKey by EbicsSubscribersTable.encryptionPrivateKey
    var authenticationPrivateKey by EbicsSubscribersTable.authenticationPrivateKey
    var bankEncryptionPublicKey by EbicsSubscribersTable.bankEncryptionPublicKey
    var bankAuthenticationPublicKey by EbicsSubscribersTable.bankAuthenticationPublicKey
    var nexusBankConnection by NexusBankConnectionEntity referencedOn EbicsSubscribersTable.nexusBankConnection
    var ebicsIniState by EbicsSubscribersTable.ebicsIniState
    var ebicsHiaState by EbicsSubscribersTable.ebicsHiaState
}

object NexusUsersTable : IdTable<String>() {
    override val id = text("id").entityId()
    val passwordHash = text("password")
    val superuser = bool("superuser")
}

class NexusUserEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, NexusUserEntity>(NexusUsersTable)

    var passwordHash by NexusUsersTable.passwordHash
    var superuser by NexusUsersTable.superuser
}

object NexusBankConnectionsTable : IdTable<String>() {
    override val id = NexusBankConnectionsTable.text("id").entityId()
    val type = text("type")
    val owner = reference("user", NexusUsersTable)
}

class NexusBankConnectionEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, NexusBankConnectionEntity>(NexusBankConnectionsTable)

    var type by NexusBankConnectionsTable.type
    var owner by NexusUserEntity referencedOn NexusBankConnectionsTable.owner
}

object FacadesTable : IdTable<String>() {
    override val id = FacadesTable.text("id").entityId()
    val type = text("type")
    val creator = reference("creator", NexusUsersTable)
}

class FacadeEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, FacadeEntity>(FacadesTable)

    var type by FacadesTable.type
    var creator by NexusUserEntity referencedOn FacadesTable.creator
}

object TalerFacadeStateTable : IntIdTable() {
    val bankAccount = text("bankAccount")
    val bankConnection = text("bankConnection")

    /* "statement", "report", "notification" */
    val reserveTransferLevel = text("reserveTransferLevel")
    val intervalIncrement = text("intervalIncrement")
    val facade = reference("facade", FacadesTable)
    // highest ID seen in the raw transactions table.
    val highestSeenMsgID = long("highestSeenMsgID").default(0)
    val highestOutgoingAbstractID = long("highestOutgoingAbstractID").default(0)
}

class TalerFacadeStateEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TalerFacadeStateEntity>(TalerFacadeStateTable)

    var bankAccount by TalerFacadeStateTable.bankAccount
    var bankConnection by TalerFacadeStateTable.bankConnection

    /* "statement", "report", "notification" */
    var reserveTransferLevel by TalerFacadeStateTable.reserveTransferLevel
    var intervalIncrement by TalerFacadeStateTable.intervalIncrement
    var facade by FacadeEntity referencedOn TalerFacadeStateTable.facade
    var highestSeenMsgID by TalerFacadeStateTable.highestSeenMsgID
    var highestOutgoingAbstractID by TalerFacadeStateTable.highestOutgoingAbstractID
}

fun dbCreateTables(dbName: String) {
    Database.connect("jdbc:sqlite:${dbName}", "org.sqlite.JDBC")
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    transaction {
        addLogger(StdOutSqlLogger)
        SchemaUtils.create(
            NexusUsersTable,
            PaymentInitiationsTable,
            EbicsSubscribersTable,
            NexusBankAccountsTable,
            RawBankTransactionsTable,
            TalerIncomingPayments,
            TalerRequestedPayments,
            NexusBankConnectionsTable,
            NexusBankMessagesTable,
            FacadesTable,
            TalerFacadeStateTable
        )
    }
}
