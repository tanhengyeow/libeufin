package tech.libeufin.nexus

data class EbicsBackupRequest(
    val passphrase: String
)

data class NexusError(
    val message: String
)

data class EbicsStandardOrderParams(
    val dateRange: EbicsDateRange?
)

data class EbicsDateRange(
    /**
     * ISO 8601 calendar dates: YEAR-MONTH(01-12)-DAY(1-31)
     */
    val start: String,
    val end: String
)

/**
 * This object is used twice: as a response to the backup request,
 * and as a request to the backup restore.  Note: in the second case
 * the client must provide the passphrase.
 */
data class EbicsKeysBackup(
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
data class EbicsSubscriberInfoRequest(
    val ebicsURL: String,
    val hostID: String,
    val partnerID: String,
    val userID: String,
    val systemID: String? = null
)

/**
 * Contain the ID that identifies the new user in the Nexus system.
 */
data class EbicsSubscriberInfoResponse(
    val accountID: String,
    val ebicsURL: String,
    val hostID: String,
    val partnerID: String,
    val userID: String,
    val systemID: String? = null
)

/**
 * Admin call that tells all the subscribers managed by Nexus.
 */
data class EbicsSubscribersResponse(
    val ebicsSubscribers: MutableList<EbicsSubscriberInfoResponse> = mutableListOf()
)

data class ProtocolAndVersion(
    val protocol: String,
    val version: String,
    val host: String
)

data class EbicsHevResponse(
    val versions: List<ProtocolAndVersion>
)