package tech.libeufin.nexus

import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey

/**
 * This class is a mere container that keeps data found
 * in the database and that is further needed to sign / verify
 * / make messages.  And not all the values are needed all
 * the time.
 */
data class EbicsContainer(
    val partnerId: String,
    val userId: String,
    var bankAuthPub: RSAPublicKey?,
    var bankEncPub: RSAPublicKey?,
    // needed to send the message
    val ebicsUrl: String,
    // needed to craft further messages
    val hostId: String,
    // needed to decrypt data coming from the bank
    val customerEncPriv: RSAPrivateCrtKey,
    // needed to sign documents
    val customerAuthPriv: RSAPrivateCrtKey,
    val customerSignPriv: RSAPrivateCrtKey
)