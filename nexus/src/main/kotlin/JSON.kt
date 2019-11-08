package tech.libeufin.nexus

/**
 * This object is POSTed by clients _after_ having created
 * a EBICS subscriber at the sandbox.
 */
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