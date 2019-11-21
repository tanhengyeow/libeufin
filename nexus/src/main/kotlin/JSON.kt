package tech.libeufin.nexus

import com.google.gson.annotations.JsonAdapter
import com.squareup.moshi.JsonClass



data class EbicsBackupRequest(
    val passphrase: String
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
    val systemID: String
)

/**
 * Contain the ID that identifies the new user in the Nexus system.
 */
data class EbicsSubscriberInfoResponse(
    val accountID: Number,
    val ebicsURL: String,
    val hostID: String,
    val partnerID: String,
    val userID: String,
    val systemID: String?
)

/**
 * Admin call that tells all the subscribers managed by Nexus.
 */
data class EbicsSubscribersResponse(
    val ebicsSubscribers: List<EbicsSubscriberInfoResponse>
)

/**
 * Error message.
 */
data class NexusError(
    val message: String
)