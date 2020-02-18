package tech.libeufin.nexus

import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.EbicsSubscribersTable.entityId
import tech.libeufin.nexus.EbicsSubscribersTable.primaryKey
import tech.libeufin.util.IntIdTableWithAmount
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

object Pain001Table : IntIdTableWithAmount() {
    val msgId = integer("msgId").uniqueIndex().autoIncrement()
    val paymentId = integer("paymentId").uniqueIndex().autoIncrement() // id for this system
    val date = date("fileDate").date()
    val sum = amount("sum")
    val debtorAccount = text("debtorAccount")
    val endToEndId = integer("EndToEndId").uniqueIndex().autoIncrement() // id for this and the creditor system
    val subject = text("subject")
    val creditorIban = text("creditorIban")
    val creditorBic = text("creditorBic")
    val creditorName = text("creditorName")
    val submitted = bool("submitted").default(false) // indicates whether the PAIN message was sent to the bank.
}

class Pain001Entity(id: EntityID<Int>) : IntEntity(id) {

    companion object : IntEntityClass<Pain001Entity>(Pain001Table)

    var msgId by Pain001Table.msgId
    var paymentId by Pain001Table.paymentId
    var date by Pain001Table.date
    var sum by Pain001Table.sum
    var debtorAccount by Pain001Table.debtorAccount
    var endToEndId by Pain001Table.endToEndId
    var subject by Pain001Table.subject
    var creditorIban by Pain001Table.creditorIban
    var creditorBic by Pain001Table.creditorBic
    var creditorName by Pain001Table.creditorName
    var submitted by Pain001Table.submitted
}


object EbicsAccountsInfoTable : IdTable<String>() {
    override val id = varchar("id", ID_MAX_LENGTH).entityId().primaryKey()
    val subscriber = reference("subscriber", EbicsSubscribersTable)
    val accountHolder = text("accountHolder").nullable()
    val iban = text("iban")
    val bankCode = text("bankCode")
}

class EbicsAccountInfoEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, EbicsAccountInfoEntity>(EbicsAccountsInfoTable)
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