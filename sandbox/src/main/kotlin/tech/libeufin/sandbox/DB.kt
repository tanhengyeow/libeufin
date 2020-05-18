/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.sandbox

import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection

/**
 * All the states to give a subscriber.
 */
enum class SubscriberState {
    /**
     * No keys at all given to the bank.
     */
    NEW,

    /**
     * Only INI electronic message was successfully sent.
     */
    PARTIALLY_INITIALIZED_INI,

    /**r
     * Only HIA electronic message was successfully sent.
     */
    PARTIALLY_INITIALIZED_HIA,

    /**
     * Both INI and HIA were electronically sent with success.
     */
    INITIALIZED,

    /**
     * All the keys accounted in INI and HIA have been confirmed
     * via physical mail.
     */
    READY
}

/**
 * All the states that one key can be assigned.
 */
enum class KeyState {

    /**
     * The key was never communicated.
     */
    MISSING,

    /**
     * The key has been electronically sent.
     */
    NEW,

    /**
     * The key has been confirmed (either via physical mail
     * or electronically -- e.g. with certificates)
     */
    RELEASED
}

/**
 * This table stores RSA public keys of subscribers.
 */
object EbicsSubscriberPublicKeysTable : IntIdTable() {
    val rsaPublicKey = blob("rsaPublicKey")
    val state = enumeration("state", KeyState::class)
}
class EbicsSubscriberPublicKeyEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsSubscriberPublicKeyEntity>(EbicsSubscriberPublicKeysTable)

    var rsaPublicKey by EbicsSubscriberPublicKeysTable.rsaPublicKey
    var state by EbicsSubscriberPublicKeysTable.state
}

/**
 * Ebics 'host'(s) that are served by one Sandbox instance.
 */
object EbicsHostsTable : IntIdTable() {
    val hostID = text("hostID")
    val ebicsVersion = text("ebicsVersion")
    val signaturePrivateKey = blob("signaturePrivateKey")
    val encryptionPrivateKey = blob("encryptionPrivateKey")
    val authenticationPrivateKey = blob("authenticationPrivateKey")
}
class EbicsHostEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsHostEntity>(EbicsHostsTable)
    var hostId by EbicsHostsTable.hostID
    var ebicsVersion by EbicsHostsTable.ebicsVersion
    var signaturePrivateKey by EbicsHostsTable.signaturePrivateKey
    var encryptionPrivateKey by EbicsHostsTable.encryptionPrivateKey
    var authenticationPrivateKey by EbicsHostsTable.authenticationPrivateKey
}

/**
 * Ebics Subscribers table.
 */
object EbicsSubscribersTable : IntIdTable() {
    val userId = text("userID")
    val partnerId = text("partnerID")
    val systemId = text("systemID").nullable()
    val hostId = text("hostID")
    val signatureKey = reference("signatureKey", EbicsSubscriberPublicKeysTable).nullable()
    val encryptionKey = reference("encryptionKey", EbicsSubscriberPublicKeysTable).nullable()
    val authenticationKey = reference("authorizationKey", EbicsSubscriberPublicKeysTable).nullable()
    val nextOrderID = integer("nextOrderID")
    val state = enumeration("state", SubscriberState::class)
}
class EbicsSubscriberEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsSubscriberEntity>(EbicsSubscribersTable)
    var userId by EbicsSubscribersTable.userId
    var partnerId by EbicsSubscribersTable.partnerId
    var systemId by EbicsSubscribersTable.systemId
    var hostId by EbicsSubscribersTable.hostId
    var signatureKey by EbicsSubscriberPublicKeyEntity optionalReferencedOn EbicsSubscribersTable.signatureKey
    var encryptionKey by EbicsSubscriberPublicKeyEntity optionalReferencedOn EbicsSubscribersTable.encryptionKey
    var authenticationKey by EbicsSubscriberPublicKeyEntity optionalReferencedOn EbicsSubscribersTable.authenticationKey
    var nextOrderID by EbicsSubscribersTable.nextOrderID
    var state by EbicsSubscribersTable.state
}

/**
 * Details of a download order.
 */
object EbicsDownloadTransactionsTable : IdTable<String>() {
    override val id = text("transactionID").entityId()
    val orderType = text("orderType")
    val host = reference("host", EbicsHostsTable)
    val subscriber = reference("subscriber", EbicsSubscribersTable)
    val encodedResponse = text("encodedResponse")
    val transactionKeyEnc = blob("transactionKeyEnc")
    val numSegments = integer("numSegments")
    val segmentSize = integer("segmentSize")
    val receiptReceived = bool("receiptReceived")
}
class EbicsDownloadTransactionEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, EbicsDownloadTransactionEntity>(EbicsDownloadTransactionsTable)
    var orderType by EbicsDownloadTransactionsTable.orderType
    var host by EbicsHostEntity referencedOn EbicsDownloadTransactionsTable.host
    var subscriber by EbicsSubscriberEntity referencedOn EbicsDownloadTransactionsTable.subscriber
    var encodedResponse by EbicsDownloadTransactionsTable.encodedResponse
    var numSegments by EbicsDownloadTransactionsTable.numSegments
    var transactionKeyEnc by EbicsDownloadTransactionsTable.transactionKeyEnc
    var segmentSize by EbicsDownloadTransactionsTable.segmentSize
    var receiptReceived by EbicsDownloadTransactionsTable.receiptReceived
}

/**
 * Details of a upload order.
 */
object EbicsUploadTransactionsTable : IdTable<String>() {
    override val id = text("transactionID").entityId()
    val orderType = text("orderType")
    val orderID = text("orderID")
    val host = reference("host", EbicsHostsTable)
    val subscriber = reference("subscriber", EbicsSubscribersTable)
    val numSegments = integer("numSegments")
    val lastSeenSegment = integer("lastSeenSegment")
    val transactionKeyEnc = blob("transactionKeyEnc")
}
class EbicsUploadTransactionEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, EbicsUploadTransactionEntity>(EbicsUploadTransactionsTable)
    var orderType by EbicsUploadTransactionsTable.orderType
    var orderID by EbicsUploadTransactionsTable.orderID
    var host by EbicsHostEntity referencedOn EbicsUploadTransactionsTable.host
    var subscriber by EbicsSubscriberEntity referencedOn EbicsUploadTransactionsTable.subscriber
    var numSegments by EbicsUploadTransactionsTable.numSegments
    var lastSeenSegment by EbicsUploadTransactionsTable.lastSeenSegment
    var transactionKeyEnc by EbicsUploadTransactionsTable.transactionKeyEnc
}

/**
 * FIXME: document this.
 */
object EbicsOrderSignaturesTable : IntIdTable() {
    val orderID = text("orderID")
    val orderType = text("orderType")
    val partnerID = text("partnerID")
    val userID = text("userID")
    val signatureAlgorithm = text("signatureAlgorithm")
    val signatureValue = blob("signatureValue")
}
class EbicsOrderSignatureEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsOrderSignatureEntity>(EbicsOrderSignaturesTable)
    var orderID by EbicsOrderSignaturesTable.orderID
    var orderType by EbicsOrderSignaturesTable.orderType
    var partnerID by EbicsOrderSignaturesTable.partnerID
    var userID by EbicsOrderSignaturesTable.userID
    var signatureAlgorithm by EbicsOrderSignaturesTable.signatureAlgorithm
    var signatureValue by EbicsOrderSignaturesTable.signatureValue
}

/**
 * FIXME: document this.
 */
object EbicsUploadTransactionChunksTable : IdTable<String>() {
    override val id =
        text("transactionID").entityId()
    val chunkIndex = integer("chunkIndex")
    val chunkContent = blob("chunkContent")
}
class EbicsUploadTransactionChunkEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, EbicsUploadTransactionChunkEntity>(EbicsUploadTransactionChunksTable)
    var chunkIndex by EbicsUploadTransactionChunksTable.chunkIndex
    var chunkContent by EbicsUploadTransactionChunksTable.chunkContent
}

/**
 * Table that keeps all the payments initiated by PAIN.001.
 */
object PaymentsTable : IntIdTable() {
    val creditorIban = text("creditorIban")
    val debitorIban = text("debitorIban")
    val subject = text("subject")
    val amount = text("amount")
    val date = long("date")
}
class PaymentEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PaymentEntity>(PaymentsTable)
    var creditorIban by PaymentsTable.creditorIban
    var debitorIban by PaymentsTable.debitorIban
    var subject by PaymentsTable.subject
    var amount by PaymentsTable.amount /** in the CURRENCY:X.Y format */
    var date by PaymentsTable.date /** Date when the payment was persisted in this system.  */
}

/**
 * Table that keeps information about which bank accounts (iban+bic+name)
 * are active in the system.
 */
object BankAccountsTable : IntIdTable() {
    val iban = text("iban")
    val bic = text("bic")
    val name = text("name")
    val label = text("label")
    val subscriber = reference("subscriber", EbicsSubscribersTable)
}
class BankAccountEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BankAccountEntity>(BankAccountsTable)
    var iban by BankAccountsTable.iban
    var bic by BankAccountsTable.bic
    var name by BankAccountsTable.name
    var label by BankAccountsTable.label
    var subscriber by EbicsSubscriberEntity referencedOn BankAccountsTable.subscriber
}

fun dbCreateTables() {
    Database.connect("jdbc:sqlite:libeufin-sandbox.sqlite3", "org.sqlite.JDBC")
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    transaction {
        addLogger(StdOutSqlLogger)
        SchemaUtils.create(
            EbicsSubscribersTable,
            EbicsHostsTable,
            EbicsDownloadTransactionsTable,
            EbicsUploadTransactionsTable,
            EbicsUploadTransactionChunksTable,
            EbicsOrderSignaturesTable,
            PaymentsTable,
            BankAccountsTable
        )
    }
}