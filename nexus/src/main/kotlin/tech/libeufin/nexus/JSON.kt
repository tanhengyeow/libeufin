package tech.libeufin.nexus

import tech.libeufin.util.Amount
import tech.libeufin.util.EbicsDateRange
import tech.libeufin.util.EbicsOrderParams
import tech.libeufin.util.EbicsStandardOrderParams
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

/**
 * This object is POSTed by clients _after_ having created
 * a EBICS subscriber at the sandbox.
 */
data class EbicsSubscriberInfoRequestJson(
    val ebicsURL: String,
    val hostID: String,
    val partnerID: String,
    val userID: String,
    val systemID: String? = null,
    val password: String? = null
)

/**
 * Contain the ID that identifies the new user in the Nexus system.
 */
data class EbicsSubscriberInfoResponseJson(
    val accountID: String,
    val ebicsURL: String,
    val hostID: String,
    val partnerID: String,
    val userID: String,
    val systemID: String? = null
)

data class Pain001Data(
    val creditorIban: String,
    val creditorBic: String,
    val creditorName: String,
    val sum: Amount,
    val subject: String
)

/**
 * Admin call that tells all the subscribers managed by Nexus.
 */
data class EbicsSubscribersResponseJson(
    val ebicsSubscribers: MutableList<EbicsSubscriberInfoResponseJson> = mutableListOf()
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

data class EbicsAccountInfoElement(
    var accountHolderName: String? = null,
    var iban: String,
    var bankCode: String,
    var accountId: String
)

data class EbicsAccountsInfoResponse(
    var accounts: MutableList<EbicsAccountInfoElement> = mutableListOf()
)

data class PaymentInfoElement(
    val debtorAccount: String,
    val creditorIban: String,
    val creditorBic: String,
    val creditorName: String,
    val subject: String,
    val sum: Amount,
    val submitted: Boolean
)

data class PaymentsInfo(
    var payments: MutableList<PaymentInfoElement> = mutableListOf()
)