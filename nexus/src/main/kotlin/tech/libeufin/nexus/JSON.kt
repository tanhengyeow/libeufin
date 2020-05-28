package tech.libeufin.nexus

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.databind.JsonNode
import tech.libeufin.util.*
import java.time.LocalDate
import java.time.LocalDateTime

data class EbicsBackupRequestJson(
    val passphrase: String
)

data class NexusErrorJson(
    val message: String
)

data class EbicsStandardOrderParamsJson(val dateRange: EbicsDateRangeJson?) {
    fun toOrderParams(): EbicsOrderParams {
        var dateRange: EbicsDateRange? = if (this.dateRange != null) {
            EbicsDateRange(
                LocalDate.parse(this.dateRange.start ?: "1970-01-31"),
                LocalDate.parse(this.dateRange.end ?: LocalDateTime.now().toDashedDate())
            )
        } else {
            null
        }
        return EbicsStandardOrderParams(dateRange)
    }
}

data class EbicsDateRangeJson(
    /** ISO 8601 calendar dates: YEAR-MONTH(01-12)-DAY(1-31) */
    val start: String?,
    val end: String?
)

data class EbicsPubKeyInfo(
    val authPub: String,
    val encPub: String,
    val sigPub: String
)

data class ProtocolAndVersionJson(
    val protocol: String,
    val version: String
)

data class EbicsHevResponseJson(
    val versions: List<ProtocolAndVersionJson>
)

data class EbicsErrorDetailJson(
    val type: String,
    val ebicsReturnCode: String
)

data class EbicsErrorJson(
    val error: EbicsErrorDetailJson
)

/** Instructs the nexus to CREATE a new Ebics subscriber.
 * Note that the nexus user to which the subscriber must be
 * associated is extracted from other HTTP details.
 *
 * This same structure can be user to SHOW one Ebics subscriber
 * existing at the nexus.
 */
data class EbicsSubscriber(
    val ebicsURL: String,
    val hostID: String,
    val partnerID: String,
    val userID: String,
    val systemID: String?
)

data class RawPayments(
    var payments: MutableList<RawPayment> = mutableListOf()
)

/*************************************************
 *  API types (used as requests/responses types) *
 *************************************************/
data class BankTransport(
    val transport: String,
    val backup: Any? = null,
    val data: Any?
)

data class BankConnectionInfo(
    val name: String,
    val type: String
)

data class BankConnectionsList(
    val bankConnections: List<BankConnectionInfo>
)

data class EbicsHostTestRequest(
    val ebicsBaseUrl: String,
    val ebicsHostId: String
)

/**
 * This object is used twice: as a response to the backup request,
 * and as a request to the backup restore.  Note: in the second case
 * the client must provide the passphrase.
 */
data class EbicsKeysBackupJson(
    // Always "ebics"
    val type: String,
    val userID: String,
    val partnerID: String,
    val hostID: String,
    val ebicsURL: String,
    val authBlob: String,
    val encBlob: String,
    val sigBlob: String
)


@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "source"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = CreateBankConnectionFromBackupRequestJson::class, name = "backup"),
    JsonSubTypes.Type(value = CreateBankConnectionFromNewRequestJson::class, name = "new")
)
abstract class CreateBankConnectionRequestJson(
    val name: String
)

@JsonTypeName("backup")
class CreateBankConnectionFromBackupRequestJson(
    name: String,
    val passphrase: String?,
    val data: JsonNode
) : CreateBankConnectionRequestJson(name)

@JsonTypeName("new")
class CreateBankConnectionFromNewRequestJson(
    name: String,
    val type: String,
    val data: JsonNode
) : CreateBankConnectionRequestJson(name)

data class EbicsNewTransport(
    val userID: String,
    val partnerID: String,
    val hostID: String,
    val ebicsURL: String,
    val systemID: String?
)

/** Response type of "GET /prepared-payments/{uuid}" */
data class PaymentStatus(
    val uuid: String,
    val submitted: Boolean,
    val creditorIban: String,
    val creditorBic: String,
    val creditorName: String,
    val amount: String,
    val subject: String,
    val submissionDate: String?,
    val preparationDate: String
)

/** Response type of "GET /collected-transactions" */
data class Transaction(
    val account: String,
    val counterpartIban: String,
    val counterpartBic: String,
    val counterpartName: String,
    val amount: String,
    val subject: String,
    val date: String
)

data class Transactions(
    val transactions: MutableList<Transaction> = mutableListOf()
)

/** Request type of "POST /collected-transactions" */
data class CollectedTransaction(
    val transport: String?,
    val start: String?,
    val end: String?
)

data class BankProtocolsResponse(
    val protocols: List<String>
)

/** Request type of "POST /prepared-payments" */
data class PreparedPaymentRequest(
    val iban: String,
    val bic: String,
    val name: String,
    val amount: String,
    val subject: String
)

/** Response type of "POST /prepared-payments" */
data class PreparedPaymentResponse(
    val uuid: String
)

/** Response type of "GET /user" */
data class UserResponse(
    val username: String,
    val superuser: Boolean
)

/** Request type of "POST /users" */
data class User(
    val username: String,
    val password: String
)

data class UserInfo(
    val username: String,
    val superuser: Boolean
)

data class UsersResponse(
    val users: List<UserInfo>
)

/** Response (list's element) type of "GET /bank-accounts" */
data class BankAccount(
    var holder: String,
    var iban: String,
    var bic: String,
    var account: String
)

/** Response type of "GET /bank-accounts" */
data class BankAccounts(
    var accounts: MutableList<BankAccount> = mutableListOf()
)

data class BankMessageList(
    val bankMessages: MutableList<BankMessageInfo> = mutableListOf()
)

data class BankMessageInfo(
    val messageId: String,
    val code: String,
    val length: Long
)

data class FacadeInfo(
    val name: String,
    val type: String,
    val creator: String,
    val bankAccountsRead: MutableList<String> = mutableListOf(),
    val bankAccountsWrite: MutableList<String> = mutableListOf(),
    val bankConnectionsRead: MutableList<String> = mutableListOf(),
    val bankConnectionsWrite: MutableList<String> = mutableListOf(),
    val config: TalerWireGatewayFacadeConfig /* To be abstracted to Any! */
)

data class TalerWireGatewayFacadeConfig(
    val bankAccount: String,
    val bankConnection: String,
    val reserveTransferLevel: MutableList<String> = mutableListOf(),
    val intervalIncremental: String
)

/**********************************************************************
 * Convenience types (ONLY used to gather data together in one place) *
 **********************************************************************/

data class Pain001Data(
    val creditorIban: String,
    val creditorBic: String,
    val creditorName: String,
    val sum: Amount,
    val currency: String,
    val subject: String
)


