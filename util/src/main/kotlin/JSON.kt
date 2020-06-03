package tech.libeufin.util

/**
 * (Very) generic information about one payment.  Can be
 * derived from a CAMT response, or from a prepared PAIN
 * document.
 */
data class RawPayment(
    val creditorIban: String,
    val creditorBic: String,
    val creditorName: String,
    val debitorIban: String,
    val debitorBic: String,
    val debitorName: String,
    val amount: String,
    val subject: String,
    val date: String?
)