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

data class EbicsStandardOrderParamsJson(
    val dateRange: EbicsDateRangeJson?
) {
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
    /**
     * ISO 8601 calendar dates: YEAR-MONTH(01-12)-DAY(1-31)
     */
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

data class BankAccount(
    var holder: String,
    var iban: String,
    var bic: String,
    var account: String
)

data class BankAccounts(
    var accounts: MutableList<BankAccount> = mutableListOf()
)

/** THE NEXUS USER */

/** SHOWS details about one user */
data class NexusUser(
    val userID: String,
    val transports: MutableList<Any> = mutableListOf()
)

/** Instructs the nexus to CREATE a new user */
data class User(
    val username: String,
    val password: String
)

/** Collection of all the nexus users existing in the system */
data class Users(
    val users: MutableList<NexusUser> = mutableListOf()
)

/************************************/

/** TRANSPORT TYPES */

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

/** Type representing the "test" transport.  Test transport
 * does not cooperate with the bank/sandbox in order to obtain
 * data about one user.  All the data is just mocked internally
 * at the NEXUS.
 */
class TestSubscriber()


/** PAYMENT INSTRUCTIONS TYPES */

/** This structure is used to INSTRUCT the nexus to prepare such payment.  */
data class Pain001Data(
    val creditorIban: String,
    val creditorBic: String,
    val creditorName: String,
    val debitorIban: String,
    val debitorBic: String,
    val debitorName: String?,
    val sum: Amount,
    val currency: String = "EUR",
    val subject: String
)

data class RawPayments(
    var payments: MutableList<RawPayment> = mutableListOf()
)