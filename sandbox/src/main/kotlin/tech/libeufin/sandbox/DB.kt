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

import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.lang.ArithmeticException
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.sql.Blob
import java.sql.Connection


const val CUSTOMER_NAME_MAX_LENGTH = 20
const val EBICS_HOST_ID_MAX_LENGTH = 10
const val EBICS_USER_ID_MAX_LENGTH = 10
const val EBICS_PARTNER_ID_MAX_LENGTH = 10
const val EBICS_SYSTEM_ID_MAX_LENGTH = 10
const val MAX_ID_LENGTH = 21 // enough to contain IBANs
const val MAX_SUBJECT_LENGTH = 140 // okay?
const val NUMBER_MAX_DIGITS = 20
const val SCALE_TWO = 2

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

fun Blob.toByteArray(): ByteArray {
    return this.binaryStream.readAllBytes()
}

/**
 * Any number can become a Amount IF it does NOT need to be rounded to comply to the scale == 2.
 */
typealias Amount = BigDecimal

open class IntIdTableWithAmount : IntIdTable() {

    class AmountColumnType : ColumnType() {
        override fun sqlType(): String  = "DECIMAL(${NUMBER_MAX_DIGITS}, ${SCALE_TWO})"

        override fun valueFromDB(value: Any): Any {

            val valueFromDB = super.valueFromDB(value)

            try {
                return when (valueFromDB) {
                    is BigDecimal -> valueFromDB.setScale(SCALE_TWO, RoundingMode.UNNECESSARY)
                    is Double -> BigDecimal.valueOf(valueFromDB).setScale(SCALE_TWO, RoundingMode.UNNECESSARY)
                    is Float -> BigDecimal(java.lang.Float.toString(valueFromDB)).setScale(
                        SCALE_TWO,
                        RoundingMode.UNNECESSARY
                    )
                    is Int -> BigDecimal(valueFromDB)
                    is Long -> BigDecimal.valueOf(valueFromDB)
                    else -> valueFromDB
                }
            } catch (e: Exception) {
                e.printStackTrace()
                throw BadAmount(value)
            }
        }

        override fun valueToDB(value: Any?): Any? {
            try {
                (value as BigDecimal).setScale(SCALE_TWO, RoundingMode.UNNECESSARY)
            } catch (e: Exception) {
                e.printStackTrace()
                throw BadAmount(value)
            }

            if ((value as BigDecimal).compareTo(BigDecimal.ZERO) == 0) {
                LOGGER.error("Cannot have transactions of ZERO amount")
                throw BadAmount(value)
            }
            return super.valueToDB(value)
        }
    }

    /**
     * Make sure the number entered by upper layers does not need any rounding
     * to conform to scale == 2
     */
    fun amount(name: String): Column<Amount> {
        return registerColumn(name, AmountColumnType())
    }
}


object BankTransactionsTable : IntIdTableWithAmount() {

    /* Using varchar to store the IBAN - or possibly other formats
     * - from the counterpart.  */
    val counterpart = varchar("counterpart", MAX_ID_LENGTH)
    val amount = amount("amount")
    val subject = varchar("subject", MAX_SUBJECT_LENGTH)
    val date = date("date")
    val localCustomer = reference("localCustomer", BankCustomersTable)
}

class BankTransactionEntity(id: EntityID<Int>) : IntEntity(id) {

    companion object : IntEntityClass<BankTransactionEntity>(BankTransactionsTable)

    /* the id of the local customer involved in this transaction,
    * either as the credit or the debit part; makes lookups easier */
    var localCustomer by BankCustomerEntity referencedOn BankTransactionsTable.localCustomer

    /* keeping as strings, as to allow hosting IBANs and/or other
    * unobvious formats.  */
    var counterpart by BankTransactionsTable.counterpart

    var subject by BankTransactionsTable.subject
    var date by BankTransactionsTable.date
    var amount by BankTransactionsTable.amount
}


/**
 * This table information *not* related to EBICS, for all
 * its customers.
 */
object BankCustomersTable : IntIdTable() {
    // Customer ID is the default 'id' field provided by the constructor.
    val name = varchar("name", CUSTOMER_NAME_MAX_LENGTH).primaryKey()
}

class BankCustomerEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BankCustomerEntity>(BankCustomersTable)
    var name by BankCustomersTable.name
}


/**
 * This table stores RSA public keys of subscribers.
 */
object EbicsSubscriberPublicKeysTable : IntIdTable() {
    val rsaPublicKey = blob("rsaPublicKey")
    val state = enumeration("state", KeyState::class)
}

/**
 * Definition of a row in the [EbicsSubscriberPublicKeyEntity] table
 */
class EbicsSubscriberPublicKeyEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsSubscriberPublicKeyEntity>(EbicsSubscriberPublicKeysTable)

    var rsaPublicKey by EbicsSubscriberPublicKeysTable.rsaPublicKey
    var state by EbicsSubscriberPublicKeysTable.state
}


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
 * Subscribers table.  This table associates users with partners
 * and systems.  Each value can appear multiple times in the same column.
 */
object EbicsSubscribersTable : IntIdTable() {
    val userId = text("userID")
    val partnerId = text("partnerID")
    val systemId = text("systemID").nullable()

    val signatureKey = reference("signatureKey", EbicsSubscriberPublicKeysTable).nullable()
    val encryptionKey = reference("encryptionKey", EbicsSubscriberPublicKeysTable).nullable()
    val authenticationKey = reference("authorizationKey", EbicsSubscriberPublicKeysTable).nullable()

    val nextOrderID = integer("nextOrderID")

    val state = enumeration("state", SubscriberState::class)
    val bankCustomer = reference("bankCustomer", BankCustomersTable)
}


class EbicsSubscriberEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsSubscriberEntity>(EbicsSubscribersTable)

    var userId by EbicsSubscribersTable.userId
    var partnerId by EbicsSubscribersTable.partnerId
    var systemId by EbicsSubscribersTable.systemId

    var signatureKey by EbicsSubscriberPublicKeyEntity optionalReferencedOn EbicsSubscribersTable.signatureKey
    var encryptionKey by EbicsSubscriberPublicKeyEntity optionalReferencedOn EbicsSubscribersTable.encryptionKey
    var authenticationKey by EbicsSubscriberPublicKeyEntity optionalReferencedOn EbicsSubscribersTable.authenticationKey

    var nextOrderID by EbicsSubscribersTable.nextOrderID
    var state by EbicsSubscribersTable.state
    var bankCustomer by BankCustomerEntity referencedOn EbicsSubscribersTable.bankCustomer
}


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


object EbicsUploadTransactionChunksTable : IdTable<String>() {
    override val id =
        text("transactionID").entityId()
    val chunkIndex = integer("chunkIndex")
    val chunkContent = blob("chunkContent")
}


class EbicsUploadTransactionChunkEntity(id : EntityID<String>): Entity<String>(id) {
    companion object : EntityClass<String, EbicsUploadTransactionChunkEntity>(EbicsUploadTransactionChunksTable)

    var chunkIndex by EbicsUploadTransactionChunksTable.chunkIndex
    var chunkContent by EbicsUploadTransactionChunksTable.chunkContent
}


fun dbCreateTables() {
    // Database.connect("jdbc:sqlite:libeufin-sandbox.sqlite3", "org.sqlite.JDBC")
    Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

    transaction {

        SchemaUtils.create(
            BankTransactionsTable,
            BankCustomersTable,
            EbicsSubscribersTable,
            EbicsHostsTable,
            EbicsDownloadTransactionsTable,
            EbicsUploadTransactionsTable,
            EbicsUploadTransactionChunksTable,
            EbicsOrderSignaturesTable
        )
    }
}