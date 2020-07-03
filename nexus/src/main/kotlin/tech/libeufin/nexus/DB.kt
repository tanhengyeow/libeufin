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
import org.jetbrains.exposed.sql.*
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
    val preparedPayment = reference("payment", PaymentInitiationsTable)
    val requestUId = text("request_uid")
    val amount = text("amount")
    val exchangeBaseUrl = text("exchange_base_url")
    val wtid = text("wtid")
    val creditAccount = text("credit_account")
}

class TalerRequestedPaymentEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TalerRequestedPaymentEntity>(TalerRequestedPayments)

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
    val payment = reference("payment", NexusBankTransactionsTable)
    val reservePublicKey = text("reservePublicKey")
    val timestampMs = long("timestampMs")
    val incomingPaytoUri = text("incomingPaytoUri")
}

class TalerIncomingPaymentEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TalerIncomingPaymentEntity>(TalerIncomingPayments)

    var payment by NexusBankTransactionEntity referencedOn TalerIncomingPayments.payment
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
object NexusBankTransactionsTable : LongIdTable() {
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
    val status = enumerationByName("status", 16, EntryStatus::class)

    /**
     * Another, later transaction that updates the status of the current transaction.
     */
    val updatedBy = optReference("updatedBy", NexusBankTransactionsTable)

    /**
     * Full details of the transaction in JSON format.
     */
    val transactionJson = text("transactionJson")
}

class NexusBankTransactionEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<NexusBankTransactionEntity>(NexusBankTransactionsTable)

    var currency by NexusBankTransactionsTable.currency
    var amount by NexusBankTransactionsTable.amount
    var status by NexusBankTransactionsTable.status
    var creditDebitIndicator by NexusBankTransactionsTable.creditDebitIndicator
    var bankAccount by NexusBankAccountEntity referencedOn NexusBankTransactionsTable.bankAccount
    var transactionJson by NexusBankTransactionsTable.transactionJson
    var accountTransactionId by NexusBankTransactionsTable.accountTransactionId
    val updatedBy by NexusBankTransactionEntity optionalReferencedOn NexusBankTransactionsTable.updatedBy
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
    val endToEndId = text("endToEndId")
    val messageId = text("messageId")
    val paymentInformationId = text("paymentInformationId")
    val instructionId = text("instructionId")
    val subject = text("subject")
    val creditorIban = text("creditorIban")
    val creditorBic = text("creditorBic").nullable()
    val creditorName = text("creditorName")
    val submitted = bool("submitted").default(false)

    /**
     * Points at the raw transaction witnessing that this
     * initiated payment was successfully performed.
     */
    val confirmationTransaction = reference("rawConfirmation", NexusBankTransactionsTable).nullable()
}

class PaymentInitiationEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<PaymentInitiationEntity>(PaymentInitiationsTable)

    var bankAccount by NexusBankAccountEntity referencedOn PaymentInitiationsTable.bankAccount
    var preparationDate by PaymentInitiationsTable.preparationDate
    var submissionDate by PaymentInitiationsTable.submissionDate
    var sum by PaymentInitiationsTable.sum
    var currency by PaymentInitiationsTable.currency
    var endToEndId by PaymentInitiationsTable.endToEndId
    var subject by PaymentInitiationsTable.subject
    var creditorIban by PaymentInitiationsTable.creditorIban
    var creditorBic by PaymentInitiationsTable.creditorBic
    var creditorName by PaymentInitiationsTable.creditorName
    var submitted by PaymentInitiationsTable.submitted
    var paymentInformationId by PaymentInitiationsTable.paymentInformationId
    var messageId by PaymentInitiationsTable.messageId
    var instructionId by PaymentInitiationsTable.instructionId
    var confirmationTransaction by NexusBankTransactionEntity optionalReferencedOn PaymentInitiationsTable.confirmationTransaction
}

/**
 * This table contains the bank accounts that are offered by the bank.
 * The bank account label (as assigned by the bank) is the primary key.
 */
object OfferedBankAccountsTable : Table() {
    val offeredAccountId = text("offeredAccountId")
    val bankConnection = reference("bankConnection", NexusBankConnectionsTable)
    val iban = text("iban")
    val bankCode = text("bankCode")
    val accountHolder = text("holderName")
    // column below gets defined only WHEN the user imports the bank account.
    val imported = reference("imported", NexusBankAccountsTable).nullable()

    override val primaryKey = PrimaryKey(offeredAccountId, bankConnection)
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

    val lastStatementCreationTimestamp = long("lastStatementCreationTimestamp").nullable()

    val lastReportCreationTimestamp = long("lastReportCreationTimestamp").nullable()

    val lastNotificationCreationTimestamp = long("lastNotificationCreationTimestamp").nullable()

    // Highest bank message ID that this bank account is aware of.
    val highestSeenBankMessageId = integer("highestSeenBankMessageId")

    val pain001Counter = long("pain001counter").default(1)
}

class NexusBankAccountEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, NexusBankAccountEntity>(NexusBankAccountsTable)
    var accountHolder by NexusBankAccountsTable.accountHolder
    var iban by NexusBankAccountsTable.iban
    var bankCode by NexusBankAccountsTable.bankCode
    var defaultBankConnection by NexusBankConnectionEntity optionalReferencedOn NexusBankAccountsTable.defaultBankConnection
    var highestSeenBankMessageId by NexusBankAccountsTable.highestSeenBankMessageId
    var pain001Counter by NexusBankAccountsTable.pain001Counter
    var lastStatementCreationTimestamp by NexusBankAccountsTable.lastStatementCreationTimestamp
    var lastReportCreationTimestamp by NexusBankAccountsTable.lastReportCreationTimestamp
    var lastNotificationCreationTimestamp by NexusBankAccountsTable.lastNotificationCreationTimestamp
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
}

object NexusScheduledTasksTable : IntIdTable() {
    val resourceType = text("resourceType")
    val resourceId = text("resourceId")
    val taskName = text("taskName")
    val taskType = text("taskType")
    val taskCronspec = text("taskCronspec")
    val taskParams = text("taskParams")
    val nextScheduledExecutionSec = long("nextScheduledExecutionSec").nullable()
    val prevScheduledExecutionSec = long("lastScheduledExecutionSec").nullable()
}

class NexusScheduledTaskEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<NexusScheduledTaskEntity>(NexusScheduledTasksTable)

    var resourceType by NexusScheduledTasksTable.resourceType
    var resourceId by NexusScheduledTasksTable.resourceId
    var taskName by NexusScheduledTasksTable.taskName
    var taskType by NexusScheduledTasksTable.taskType
    var taskCronspec by NexusScheduledTasksTable.taskCronspec
    var taskParams by NexusScheduledTasksTable.taskParams
    var nextScheduledExecutionSec by NexusScheduledTasksTable.nextScheduledExecutionSec
    var prevScheduledExecutionSec by NexusScheduledTasksTable.prevScheduledExecutionSec
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
            NexusBankTransactionsTable,
            TalerIncomingPayments,
            TalerRequestedPayments,
            NexusBankConnectionsTable,
            NexusBankMessagesTable,
            FacadesTable,
            TalerFacadeStateTable,
            NexusScheduledTasksTable,
            OfferedBankAccountsTable
        )
    }
}
