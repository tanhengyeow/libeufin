package tech.libeufin.tech.libeufin

import org.jetbrains.exposed.dao.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

const val CUSTOMER_NAME_MAX_LENGTH = 20
const val SUBSCRIBER_ID_MAX_LENGTH = 10
const val PUBLIC_KEY_MAX_LENGTH = 256 // FIXME review this value!
const val PRIV_KEY_MAX_LENGTH = 512 // FIXME review this value!

/**
 * All the states to give a subscriber.
 */
enum class SubscriberStates {
    /**
     * No keys at all given to the bank.
     */
    NEW,

    /**
     * Only INI electronic message was succesfully sent.
     */
    PARTIALLY_INITIALIZED_INI,

    /**
     * Only HIA electronic message was succesfully sent.
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
enum class KeyStates {

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
 * This table information *not* related to EBICS, for all
 * its customers.
 */
object Customer: IntIdTable() {
    // Customer ID is the default 'id' field provided by the constructor.
    val name = varchar("name", CUSTOMER_NAME_MAX_LENGTH)
    val ebicsUserId = reference("ebicsUserId", EbicsUsers)
}

/**
 * The following three tables define IDs that make a EBCIS
 * 'subscriber' exist.  Each EBICS subscriber is the tuple:
 *
 * - UserID, the human who is performing a EBICS task.
 * - PartnerID, the legal entity that signed a formal agreement with the financial institution.
 * - SystemID, (optional) the machine that is handling the EBICS task on behalf of the UserID.
 */

/**
 * Table for UserID.
 */
object EbicsUsers: IntIdTable() {
    // For simplicity, this entity is implemented by the
    // 'id' field provided by the table constructor by default.
}

/**
 * Table for UserID.
 */
object EbicsPartners: IntIdTable() {
    // For simplicity, this entity is implemented by the
    // 'id' field provided by the table constructor by default.
}

/**
 * Table for UserID.
 */
object EbicsSystems: IntIdTable() {
    // For simplicity, this entity is implemented by the
    // 'id' field provided by the table constructor by default.
}

/**
 * Subscribers table.  This table associates users with partners
 * and systems.  Each value can appear multiple times in the same column.
 */
object EbicsSubscribers: IntIdTable() {
    val userId = reference("UserId", EbicsUsers)
    val partnerId = reference("PartnerId", EbicsPartners)
    val systemId = reference("SystemId", EbicsSystems)
}


/**
 * This table maps customers with EBICS subscribers.
 */
object CustomerSubscriberMap: IntIdTable(){
    val customerId = reference("customerId", Customer)
    val subscriberId = reference("subscriberId", Subscriber)
}

/**
 * This table defines a EBICS subscriber.
 */
object Subscriber: IntIdTable(){
    // is EBICS 'subscriber' ID?
    val subscriberId: Column<String> = varchar(
        "subscriberId",
        SUBSCRIBER_ID_MAX_LENGTH).primaryKey()

    val state = customEnumeration(
        "state",
        "ENUM('NEW', 'PARTIALLI_INITIALIZED_INI', 'PARTIALLY_INITIALIZED_HIA', 'INITIALIZED', 'READY')",
        {SubscriberStates.values()[it as Int]},
        {it.name}
    )

    val signatureKey = reference("signatureKey", EBICSPublicKEy)
    val encryptionKey = reference("encryptionKey", EBICSPublicKEy)
    val authorizationKey = reference("authorizationKey", EBICSPublicKEy)
}

/**
 * This table stores RSA public keys.
 */
object EBICSPublicKEy: IntIdTable(){
    val pub = binary("pub", PUBLIC_KEY_MAX_LENGTH)
    val state = customEnumeration(
        "state",
        "ENUM('MISSING', 'NEW', 'RELEASED')",
        {KeyStates.values()[it as Int]},
        {it.name})
}

/**
 * This table stores RSA private keys.
 */
object EBICSPrivateKEy: IntIdTable(){
    val pub = binary("priv", PRIV_KEY_MAX_LENGTH)
}

fun db() {
    Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver")

    transaction {
        addLogger(StdOutSqlLogger)
    }
}