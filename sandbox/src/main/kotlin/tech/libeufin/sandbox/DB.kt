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
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Blob

const val CUSTOMER_NAME_MAX_LENGTH = 20
const val EBICS_HOST_ID_MAX_LENGTH = 10
const val EBICS_USER_ID_MAX_LENGTH = 10
const val EBICS_PARTNER_ID_MAX_LENGTH = 10
const val EBICS_SYSTEM_ID_MAX_LENGTH = 10
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
 * This table information *not* related to EBICS, for all
 * its customers.
 */
object BankCustomers: IntIdTable() {
    // Customer ID is the default 'id' field provided by the constructor.
    val name = varchar("name", CUSTOMER_NAME_MAX_LENGTH).primaryKey()
    val ebicsSubscriber = reference("ebicsSubscriber", EbicsSubscribers)
}

class BankCustomer(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BankCustomer>(BankCustomers)

    var name by BankCustomers.name
    var ebicsSubscriber by EbicsSubscriber referencedOn BankCustomers.ebicsSubscriber
}


/**
 * This table stores RSA public keys of subscribers.
 */
object EbicsPublicKeys : IntIdTable() {
    val rsaPublicKey = blob("rsaPublicKey")
    val state = enumeration("state", KeyState::class)
}


/**
 * Definition of a row in the [EbicsPublicKey] table
 */
class EbicsPublicKey(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsPublicKey>(EbicsPublicKeys)
    var rsaPublicKey by EbicsPublicKeys.rsaPublicKey
    var state by EbicsPublicKeys.state
}


object EbicsHosts : IntIdTable() {
    val hostID = text("hostID")
    val ebicsVersion = text("ebicsVersion")
    val signaturePrivateKey = blob("signaturePrivateKey")
    val encryptionPrivateKey = blob("encryptionPrivateKey")
    val authenticationPrivateKey = blob("authenticationPrivateKey")
}


class EbicsHost(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsHost>(EbicsHosts)
    var hostId by EbicsHosts.hostID
    var ebicsVersion by EbicsHosts.ebicsVersion
    var signaturePrivateKey by EbicsHosts.signaturePrivateKey
    var encryptionPrivateKey by EbicsHosts.encryptionPrivateKey
    var authenticationPrivateKey by EbicsHosts.authenticationPrivateKey
}

/**
 * Subscribers table.  This table associates users with partners
 * and systems.  Each value can appear multiple times in the same column.
 */
object EbicsSubscribers: IntIdTable() {
    val userId = text("userID")
    val partnerId = text("partnerID")
    val systemId = text("systemID").nullable()

    val signatureKey = reference("signatureKey", EbicsPublicKeys).nullable()
    val encryptionKey = reference("encryptionKey", EbicsPublicKeys).nullable()
    val authenticationKey = reference("authorizationKey", EbicsPublicKeys).nullable()

    val state = enumeration("state", SubscriberState::class)
}

class EbicsSubscriber(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsSubscriber>(EbicsSubscribers)

    var userId by EbicsSubscribers.userId
    var partnerId by EbicsSubscribers.partnerId
    var systemId by EbicsSubscribers.systemId

    var signatureKey by EbicsPublicKey optionalReferencedOn EbicsSubscribers.signatureKey
    var encryptionKey by EbicsPublicKey optionalReferencedOn EbicsSubscribers.encryptionKey
    var authenticationKey by EbicsPublicKey optionalReferencedOn EbicsSubscribers.authenticationKey

    var state by EbicsSubscribers.state
}


fun dbCreateTables() {
    Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

    transaction {
        // addLogger(StdOutSqlLogger)

        SchemaUtils.create(
            BankCustomers,
            EbicsSubscribers,
            EbicsHosts
        )
    }
}
