package tech.libeufin.nexus

import com.google.gson.annotations.JsonAdapter
import com.squareup.moshi.JsonClass
import org.joda.time.DateTime


data class EbicsBackupRequest(
    val passphrase: String
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
    val authBlob: ByteArray,
    val encBlob: ByteArray,
    val sigBlob: ByteArray,
    val passphrase: String? = null
)

/**
 * This object is POSTed by clients _after_ having created
 * a EBICS subscriber at the sandbox.
 */
@JsonClass(generateAdapter = true) // USED?
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
    val accountID: Int,
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