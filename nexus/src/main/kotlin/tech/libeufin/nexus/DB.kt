package tech.libeufin.nexus

import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.util.EbicsInitState
import tech.libeufin.util.amount
import java.sql.Connection

const val ID_MAX_LENGTH = 50

/**
 * This table holds the values that exchange gave to issue a payment,
 * plus a reference to the prepared pain.001 version of.  Note that
 * whether a pain.001 document was sent or not to the bank is indicated
 * in the PAIN-table.
 */
object TalerRequestedPayments : LongIdTable() {
    val preparedPayment = reference("payment", PreparedPaymentsTable)
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

    var preparedPayment by PreparedPaymentEntity referencedOn TalerRequestedPayments.preparedPayment
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
object TalerIncomingPayments : LongIdTable() {
    val payment = reference("payment", RawBankTransactionsTable)
    val valid = bool("valid")

    // avoid refunding twice!
    val refunded = bool("refunded").default(false)
}

class TalerIncomingPaymentEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TalerIncomingPaymentEntity>(TalerIncomingPayments)
    var payment by RawBankTransactionEntity referencedOn TalerIncomingPayments.payment
    var valid by TalerIncomingPayments.valid
    var refunded by TalerIncomingPayments.refunded
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
    val unstructuredRemittanceInformation = text("unstructuredRemittanceInformation")
    val transactionType = text("transactionType") /* DBIT or CRDT */
    val currency = text("currency")
    val amount = text("amount")
    val counterpartIban = text("counterpartIban")
    val counterpartBic = text("counterpartBic")
    val counterpartName = text("counterpartName")
    val bookingDate = long("bookingDate")
    val status = text("status") // BOOK or other.
    val uid = text("uid") // AcctSvcrRef code, given by the bank.
    val bankAccount = reference("bankAccount", NexusBankAccountsTable)
}

class RawBankTransactionEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<RawBankTransactionEntity>(RawBankTransactionsTable)
    var unstructuredRemittanceInformation by RawBankTransactionsTable.unstructuredRemittanceInformation
    var transactionType by RawBankTransactionsTable.transactionType
    var currency by RawBankTransactionsTable.currency
    var amount by RawBankTransactionsTable.amount
    var counterpartIban by RawBankTransactionsTable.counterpartIban
    var counterpartBic by RawBankTransactionsTable.counterpartBic
    var counterpartName by RawBankTransactionsTable.counterpartName
    var bookingDate by RawBankTransactionsTable.bookingDate
    var status by RawBankTransactionsTable.status
    var uid by RawBankTransactionsTable.uid
    var bankAccount by NexusBankAccountEntity referencedOn RawBankTransactionsTable.bankAccount
}

/**
 * Represents a prepared payment.
 */
object PreparedPaymentsTable : IdTable<String>() {
    /** the UUID representing this payment in the system */
    override val id = varchar("id", ID_MAX_LENGTH).entityId()
    val paymentId = long("paymentId")
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
    /* Indicates whether the PAIN message was sent to the bank. */
    val submitted = bool("submitted").default(false)
    /* Indicates whether the bank didn't perform the payment: note that
     * this state can be reached when the payment gets listed in a CRZ
     * response OR when the payment doesn't show up in a C52/C53 response */
    val invalid = bool("invalid").default(false)
}

class PreparedPaymentEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, PreparedPaymentEntity>(PreparedPaymentsTable)
    var paymentId by PreparedPaymentsTable.paymentId
    var preparationDate by PreparedPaymentsTable.preparationDate
    var submissionDate by PreparedPaymentsTable.submissionDate
    var sum by PreparedPaymentsTable.sum
    var currency by PreparedPaymentsTable.currency
    var debitorIban by PreparedPaymentsTable.debitorIban
    var debitorBic by PreparedPaymentsTable.debitorBic
    var debitorName by PreparedPaymentsTable.debitorName
    var endToEndId by PreparedPaymentsTable.endToEndId
    var subject by PreparedPaymentsTable.subject
    var creditorIban by PreparedPaymentsTable.creditorIban
    var creditorBic by PreparedPaymentsTable.creditorBic
    var creditorName by PreparedPaymentsTable.creditorName
    var submitted by PreparedPaymentsTable.submitted
    var invalid by PreparedPaymentsTable.invalid
}

/**
 * This table holds triples of <iban, bic, holder name>.
 */
object NexusBankAccountsTable : IdTable<String>() {
    override val id = varchar("id", ID_MAX_LENGTH).entityId()
    val accountHolder = text("accountHolder")
    val iban = text("iban")
    val bankCode = text("bankCode")
    val defaultBankConnection = reference("defaultBankConnection", NexusBankConnectionsTable).nullable()
    // Highest bank message ID that this bank account is aware of.
    val highestSeenBankMessageId = integer("")
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
    override val id = varchar("id", ID_MAX_LENGTH).entityId()
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
    val highestSeenMsgID = long("highestSeenMessageID").default(0)
}

class FacadeEntity(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, FacadeEntity>(FacadesTable)
    var type by FacadesTable.type
    var creator by NexusUserEntity referencedOn FacadesTable.creator
}

object TalerFacadeStatesTable : IntIdTable() {
    val bankAccount = text("bankAccount")
    val bankConnection = text("bankConnection")
    /* "statement", "report", "notification" */
    val reserveTransferLevel = text("reserveTransferLevel")
    val intervalIncrement = text("intervalIncrement")
    val facade = reference("facade", FacadesTable)
    val highestSeenMsgID = long("highestSeenMsgID").default(0)
}

class TalerFacadeStateEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TalerFacadeStateEntity>(TalerFacadeStatesTable)
    var bankAccount by TalerFacadeStatesTable.bankAccount
    var bankConnection by TalerFacadeStatesTable.bankConnection
    /* "statement", "report", "notification" */
    var reserveTransferLevel by TalerFacadeStatesTable.reserveTransferLevel
    var intervalIncrement by TalerFacadeStatesTable.intervalIncrement
    var facade by FacadeEntity referencedOn TalerFacadeStatesTable.facade
    var highestSeenMsgID by TalerFacadeStatesTable.highestSeenMsgID
}

fun dbCreateTables(dbName: String) {
    Database.connect("jdbc:sqlite:${dbName}", "org.sqlite.JDBC")
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
    transaction {
        addLogger(StdOutSqlLogger)
        SchemaUtils.create(
            NexusUsersTable,
            PreparedPaymentsTable,
            EbicsSubscribersTable,
            NexusBankAccountsTable,
            RawBankTransactionsTable,
            TalerIncomingPayments,
            TalerRequestedPayments,
            NexusBankConnectionsTable,
            NexusBankMessagesTable,
            FacadesTable,
            TalerFacadeStatesTable
        )
    }
}