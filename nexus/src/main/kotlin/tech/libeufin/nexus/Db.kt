package tech.libeufin.nexus

import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.EbicsSubscribersTable.entityId
import tech.libeufin.nexus.EbicsSubscribersTable.primaryKey
import java.sql.Connection

const val ID_MAX_LENGTH = 50


//object EbicsRawBankTransactionsTable : IdTable<Long>() {
//    override val id = EbicsSubscribersTable.long("id").entityId().primaryKey()
//
//    val nexusSubscriber = reference("subscriber", EbicsSubscribersTable)
//
//    /**
//     * How did we learn about this transaction?  C52 / C53 / C54
//     */
//    val sourceType = text("sourceType")
//
//    val sourceFileName = text("sourceFileName")
//
//
//
//    /**
//     * "Subject" of the SEPA transaction
//     */
//    val unstructuredRemittanceInformation = text("unstructuredRemittanceInformation")
//
//    /**
//     * Is it a credit or debit transaction?
//     */
//    val transactionType = text("transactionType")
//
//    val currency = text("currency")
//
//    val amount = text("amount")
//
//    val creditorIban = text("creditorIban")
//
//    val debitorIban = text("creditorIban")
//}
//
//
///**
// * This table gets populated by the HTD request.
// *
// * It stores which subscriber has access to which bank accounts via EBICS.
// *
// * When making a payment, we need to refer to one of these accounts
// */
//object EbicsBankAccountsTable {
//
//}

object EbicsAccountsInfoTable : IntIdTable() {
    val accountId = text("accountId")
    val subscriber = reference("subscriber", EbicsSubscribersTable)
    val accountHolder = text("accountHolder").nullable()
    val iban = text("iban")
    val bankCode = text("bankCode")
}

class EbicsAccountInfoEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EbicsAccountInfoEntity>(EbicsAccountsInfoTable)
    var accountId by EbicsAccountsInfoTable.accountId
    var subscriber by EbicsSubscriberEntity referencedOn EbicsAccountsInfoTable.subscriber
    var accountHolder by EbicsAccountsInfoTable.accountHolder
    var iban by EbicsAccountsInfoTable.iban
    var bankCode by EbicsAccountsInfoTable.bankCode
}

object EbicsSubscribersTable : IdTable<String>() {
    override val id = varchar("id", ID_MAX_LENGTH).entityId().primaryKey()
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
}

class EbicsSubscriberEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, EbicsSubscriberEntity>(EbicsSubscribersTable)
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
}

fun dbCreateTables() {
    Database.connect("jdbc:sqlite:libeufin-nexus.sqlite3", "org.sqlite.JDBC")
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    transaction {
        addLogger(StdOutSqlLogger)
         SchemaUtils.create(
            EbicsSubscribersTable,
            EbicsAccountsInfoTable
         )
    }
}