package tech.libeufin.nexus

import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import tech.libeufin.nexus.EbicsSubscribersTable.entityId
import tech.libeufin.nexus.EbicsSubscribersTable.primaryKey
import tech.libeufin.util.IntIdTableWithAmount
import java.sql.Connection

const val ID_MAX_LENGTH = 50

object TalerIncomingPayments: LongIdTable() {
    val payment = reference("payment", EbicsRawBankTransactionsTable)
    val valid = bool("valid")
    // avoid refunding twice!
    val processed = bool("refunded").default(false)
}

class TalerIncomingPaymentEntry(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TalerIncomingPaymentEntry>(TalerIncomingPayments)
    var payment by EbicsRawBankTransactionEntry referencedOn TalerIncomingPayments.payment
    var valid by TalerIncomingPayments.valid
}

object EbicsRawBankTransactionsTable : LongIdTable() {
    val nexusSubscriber = reference("subscriber", EbicsSubscribersTable)
    // How did we learn about this transaction?  C52 / C53 / C54
    val sourceType = text("sourceType")
    // Name of the ZIP entry
    val sourceFileName = text("sourceFileName")
    // "Subject" of the SEPA transaction
    val unstructuredRemittanceInformation = text("unstructuredRemittanceInformation")
    // Debit or credit
    val transactionType = text("transactionType")
    val currency = text("currency")
    val amount = text("amount")
    val creditorIban = text("creditorIban")
    val creditorName = text("creditorBic")
    val debitorIban = text("debitorIban")
    val debitorName = text("debitorName")
    val counterpartBic = text("counterpartBic")
    val bookingDate = text("bookingDate")
}

class EbicsRawBankTransactionEntry(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<EbicsRawBankTransactionEntry>(EbicsRawBankTransactionsTable)
    var sourceType by EbicsRawBankTransactionsTable.sourceType // C52 or C53 or C54?
    var sourceFileName by EbicsRawBankTransactionsTable.sourceFileName
    var unstructuredRemittanceInformation by EbicsRawBankTransactionsTable.unstructuredRemittanceInformation
    var transactionType by EbicsRawBankTransactionsTable.transactionType
    var currency by EbicsRawBankTransactionsTable.currency
    var amount by EbicsRawBankTransactionsTable.amount
    var debitorIban by EbicsRawBankTransactionsTable.debitorIban
    var debitorName by EbicsRawBankTransactionsTable.debitorName
    var creditorName by EbicsRawBankTransactionsTable.creditorName
    var creditorIban by EbicsRawBankTransactionsTable.creditorIban
    var counterpartBic by EbicsRawBankTransactionsTable.counterpartBic
    var bookingDate by EbicsRawBankTransactionsTable.bookingDate
    var nexusSubscriber by EbicsSubscriberEntity referencedOn EbicsRawBankTransactionsTable.nexusSubscriber
}

object Pain001Table : IntIdTableWithAmount() {
    val msgId = long("msgId").uniqueIndex().autoIncrement()
    val paymentId = long("paymentId")
    val fileDate = long("fileDate")
    val sum = amount("sum")
    val debtorAccount = text("debtorAccount")
    val endToEndId = long("EndToEndId")
    val subject = text("subject")
    val creditorIban = text("creditorIban")
    val creditorBic = text("creditorBic")
    val creditorName = text("creditorName")

    /* Indicates whether the PAIN message was sent to the bank. */
    val submitted = bool("submitted").default(false)

    /* Indicates whether the bank didn't perform the payment: note that
    * this state can be reached when the payment gets listed in a CRZ
    * response OR when the payment doesn's show up in a C52/C53 response
    */
    val invalid = bool("invalid").default(false)
}

class Pain001Entity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Pain001Entity>(Pain001Table)
    var msgId by Pain001Table.msgId
    var paymentId by Pain001Table.paymentId
    var date by Pain001Table.fileDate
    var sum by Pain001Table.sum
    var debtorAccount by Pain001Table.debtorAccount
    var endToEndId by Pain001Table.endToEndId
    var subject by Pain001Table.subject
    var creditorIban by Pain001Table.creditorIban
    var creditorBic by Pain001Table.creditorBic
    var creditorName by Pain001Table.creditorName
    var submitted by Pain001Table.submitted
    var invalid by Pain001Table.invalid
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
             Pain001Table,
             EbicsSubscribersTable,
             EbicsAccountsInfoTable,
             EbicsRawBankTransactionsTable,
             TalerIncomingPayments
         )
    }
}