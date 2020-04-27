package tech.libeufin.nexus

import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.EbicsSubscribersTable.entityId
import tech.libeufin.nexus.EbicsSubscribersTable.nullable
import tech.libeufin.nexus.EbicsSubscribersTable.primaryKey
import tech.libeufin.util.IntIdTableWithAmount
import java.sql.Connection

const val ID_MAX_LENGTH = 50

/**
 * This table holds the values that exchange gave to issue a payment,
 * plus a reference to the prepared pain.001 version of.  Note that
 * whether a pain.001 document was sent or not to the bank is indicated
 * in the PAIN-table.
 */
object TalerRequestedPayments: LongIdTable() {
    val preparedPayment = reference("payment", Pain001Table)
    val requestUId = text("request_uid")
    val amount = text("amount")
    val exchangeBaseUrl = text("exchange_base_url")
    val wtid = text("wtid")
    val creditAccount = text("credit_account")
    /**
     * This column gets a value only after the bank acknowledges the payment via
     * a camt.05x entry.  The "crunch" logic is responsible for assigning such value.
     */
    val rawConfirmed = reference("raw_confirmed", RawBankTransactionsTable).nullable()
}

class TalerRequestedPaymentEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TalerRequestedPaymentEntity>(TalerRequestedPayments)
    var preparedPayment by Pain001Entity referencedOn TalerRequestedPayments.preparedPayment
    var requestUId by TalerRequestedPayments.requestUId
    var amount by TalerRequestedPayments.amount
    var exchangeBaseUrl by TalerRequestedPayments.exchangeBaseUrl
    var wtid by TalerRequestedPayments.wtid
    var creditAccount by TalerRequestedPayments.creditAccount
    var rawConfirmed by RawBankTransactionEntity optionalReferencedOn TalerRequestedPayments.rawConfirmed
}

/**
 * This is the table of the incoming payments.  Entries are merely "pointers" to the
 * entries from the raw payments table.  Fixme: name should end with "-table".
 */
object TalerIncomingPayments: LongIdTable() {
    val payment = reference("payment", RawBankTransactionsTable)
    val valid = bool("valid")
    // avoid refunding twice!
    val refunded = bool("refunded").default(false)
}

fun LongEntityClass<*>.getLast(): Long {
    return this.all().maxBy { it.id }?.id?.value ?: -1
}

class TalerIncomingPaymentEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TalerIncomingPaymentEntity>(TalerIncomingPayments) {
        override fun new(init: TalerIncomingPaymentEntity.() -> Unit): TalerIncomingPaymentEntity {
            val newRow = super.new(init)
            /**
             * In case the exchange asks for all the values strictly lesser than MAX_VALUE,
             * it would lose the row whose id == MAX_VALUE.  So the check below makes this
             * situation impossible by disallowing MAX_VALUE as a id value.
             */
            if (newRow.id.value == Long.MAX_VALUE) {
                throw NexusError(
                    HttpStatusCode.InsufficientStorage, "Cannot store rows anymore"
                )
            }
            return newRow
        }
    }
    var payment by RawBankTransactionEntity referencedOn TalerIncomingPayments.payment
    var valid by TalerIncomingPayments.valid
    var refunded by TalerIncomingPayments.refunded
}

/**
 * This table contains history "elements" as returned by the bank from a
 * CAMT message.
 */
object RawBankTransactionsTable : LongIdTable() {
    val nexusSubscriber = reference("subscriber", EbicsSubscribersTable)
    val sourceFileName = text("sourceFileName") /* ZIP entry's name */
    val unstructuredRemittanceInformation = text("unstructuredRemittanceInformation")
    val transactionType = text("transactionType") /* DBIT or CRDT */
    val currency = text("currency")
    val amount = text("amount")
    val creditorIban = text("creditorIban")
    val creditorName = text("creditorBic")
    val debitorIban = text("debitorIban")
    val debitorName = text("debitorName")
    val counterpartBic = text("counterpartBic")
    val bookingDate = long("bookingDate")
    val status = text("status") // BOOK or other.
}

class RawBankTransactionEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<RawBankTransactionEntity>(RawBankTransactionsTable)
    var sourceFileName by RawBankTransactionsTable.sourceFileName
    var unstructuredRemittanceInformation by RawBankTransactionsTable.unstructuredRemittanceInformation
    var transactionType by RawBankTransactionsTable.transactionType
    var currency by RawBankTransactionsTable.currency
    var amount by RawBankTransactionsTable.amount
    var debitorIban by RawBankTransactionsTable.debitorIban
    var debitorName by RawBankTransactionsTable.debitorName
    var creditorName by RawBankTransactionsTable.creditorName
    var creditorIban by RawBankTransactionsTable.creditorIban
    var counterpartBic by RawBankTransactionsTable.counterpartBic
    var bookingDate by RawBankTransactionsTable.bookingDate
    var nexusSubscriber by EbicsSubscriberEntity referencedOn RawBankTransactionsTable.nexusSubscriber
    var status by RawBankTransactionsTable.status
}

/**
 * NOTE: every column in this table corresponds to a particular
 * value described in the pain.001 official documentation; therefore
 * this table is not really suitable to hold custom data (like Taler-related,
 * for example)
 */
object Pain001Table : IntIdTableWithAmount() {
    val msgId = long("msgId").uniqueIndex().autoIncrement()
    val paymentId = long("paymentId")
    val fileDate = long("fileDate")
    val sum = amount("sum")
    val currency = varchar("currency", length = 3).default("EUR")
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
    * response OR when the payment doesn't show up in a C52/C53 response
    */
    val invalid = bool("invalid").default(false)
}

class Pain001Entity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Pain001Entity>(Pain001Table)
    var msgId by Pain001Table.msgId
    var paymentId by Pain001Table.paymentId
    var date by Pain001Table.fileDate
    var sum by Pain001Table.sum
    var currency by Pain001Table.currency
    var debtorAccount by Pain001Table.debtorAccount
    var endToEndId by Pain001Table.endToEndId
    var subject by Pain001Table.subject
    var creditorIban by Pain001Table.creditorIban
    var creditorBic by Pain001Table.creditorBic
    var creditorName by Pain001Table.creditorName
    var submitted by Pain001Table.submitted
    var invalid by Pain001Table.invalid
}

/**
 * This table holds triples of <iban, bic, holder name>.
 */
object BankAccountsTable : IdTable<String>() {
    override val id = varchar("id", ID_MAX_LENGTH).entityId().primaryKey()
    val accountHolder = text("accountHolder").nullable()
    val iban = text("iban")
    val bankCode = text("bankCode") 
}

class BankAccountEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, BankAccountEntity>(BankAccountsTable)
    var accountHolder by BankAccountsTable.accountHolder
    var iban by BankAccountsTable.iban
    var bankCode by BankAccountsTable.bankCode
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
}

class EbicsSubscriberEntity(id: EntityID<Int>) : Entity<Int>(id) {
    companion object : EntityClass<Int, EbicsSubscriberEntity>(EbicsSubscribersTable)
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

object NexusUsersTable : IdTable<String>() {
    override val id = varchar("id", ID_MAX_LENGTH).entityId().primaryKey()
    val ebicsSubscriber = reference("ebicsSubscriber", EbicsSubscribersTable)
    val password = EbicsSubscribersTable.blob("password").nullable()
}

class NexusUserEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, NexusUserEntity>(NexusUsersTable)
    var ebicsSubscriber by EbicsSubscriberEntity referencedOn  NexusUsersTable.ebicsSubscriber
    var password by NexusUsersTable.password
}

object UserToBankAccountsTable : IntIdTable() {
    val nexusUser = reference("nexusUser", NexusUsersTable)
    val bankAccount = reference("bankAccount", BankAccountsTable)
}

class UserToBankAccountEntity(id: EntityID<Int>): IntEntity(id) {
    companion object : IntEntityClass<UserToBankAccountEntity>(UserToBankAccountsTable)
    var nexusUser by NexusUserEntity referencedOn UserToBankAccountsTable.nexusUser
    var bankAccount by BankAccountEntity referencedOn EbicsToBankAccountsTable.bankAccount
}

object EbicsToBankAccountsTable : IntIdTable() {
    val ebicsSubscriber = reference("ebicsSubscriber", EbicsSubscribersTable)
    val bankAccount = reference("bankAccount", BankAccountsTable)
}

class EbicsToBankAccountEntity(id: EntityID<Int>): IntEntity(id) {
    companion object : IntEntityClass<EbicsToBankAccountEntity>(EbicsToBankAccountsTable)
    var ebicsSubscriber by EbicsSubscriberEntity referencedOn EbicsToBankAccountsTable.ebicsSubscriber
    var bankAccount by BankAccountEntity referencedOn EbicsToBankAccountsTable.bankAccount
}

fun dbCreateTables() {
    Database.connect("jdbc:sqlite:libeufin-nexus.sqlite3", "org.sqlite.JDBC")
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    transaction {
        addLogger(StdOutSqlLogger)
         SchemaUtils.create(
             Pain001Table,
             EbicsSubscribersTable,
             BankAccountsTable,
             RawBankTransactionsTable,
             TalerIncomingPayments,
             TalerRequestedPayments
         )
    }
}