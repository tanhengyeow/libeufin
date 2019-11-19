package tech.libeufin.nexus

import javax.crypto.SecretKey
import org.w3c.dom.Document
import javax.xml.bind.JAXBElement


/**
 * This class is a mere container that keeps data found
 * in the database and that is further needed to sign / verify
 * / make messages.  And not all the values are needed all
 * the time.
 */
data class EbicsContainer<T>(

    // needed to verify responses
    val bankAuthPubBlob: ByteArray? = null,

    val bankEncPubBlob: ByteArray? = null,

    // needed to send the message
    val ebicsUrl: String? = null,

    // needed to craft further messages
    val hostId: String? = null,

    // needed to encrypt order data during all the phases
    val plainTransactionKey: SecretKey? = null,

    // needed to decrypt data coming from the bank
    val customerEncPrivBlob: ByteArray? = null,

    // needed to sign documents
    val customerAuthPrivBlob: ByteArray? = null,

    val jaxb: T? = null
)