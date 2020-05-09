package tech.libeufin.nexus

import tech.libeufin.util.*
import java.lang.NullPointerException
import java.time.LocalDate

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
                LocalDate.parse(this.dateRange.start),
                LocalDate.parse(this.dateRange.end)
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

/**
 * This object is used twice: as a response to the backup request,
 * and as a request to the backup restore.  Note: in the second case
 * the client must provide the passphrase.
 */
data class EbicsKeysBackupJson(
    val userID: String,
    val partnerID: String,
    val hostID: String,
    val ebicsURL: String,
    val authBlob: String,
    val encBlob: String,
    val sigBlob: String,
    val passphrase: String? = null
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
    val systemID: String? = null
)

data class RawPayments(
    var payments: MutableList<RawPayment> = mutableListOf()
)

/*************************************************
 *  API types (used as requests/responses types) *
 *************************************************/

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

/** Request type of "POST /prepared-payments/submit" */
data class SubmitPayment(
    val uuid: String,
    val transport: String?
)

/** Request type of "POST /collected-transactions" */
data class CollectedTransaction(
    val transport: String?,
    val start: String?,
    val end: String?
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


/**********************************************************************
 * Convenience types (ONLY used to gather data together in one place) *
 **********************************************************************/

data class Pain001Data(
    val creditorIban: String,
    val creditorBic: String,
    val creditorName: String,
    val debitorIban: String,
    val debitorBic: String,
    val debitorName: String,
    val sum: Amount,
    val currency: String,
    val subject: String
)



















