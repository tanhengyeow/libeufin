package tech.libeufin.util

import io.ktor.http.HttpStatusCode
import tech.libeufin.util.EbicsProtocolError
import java.net.URI

/**
 * Helper data structures.
 */
data class Payto(
    val name: String,
    val iban: String,
    val bic: String = "NOTGIVEN"
)

fun parsePayto(paytoLine: String): Payto {
    val javaParsedUri = try {
        URI(paytoLine)
    } catch (e: java.lang.Exception) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'${paytoLine}' is not a valid URI")
    }
    if (javaParsedUri.scheme != "payto") {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'${paytoLine}' is not payto")
    }
    val iban = javaParsedUri.path.split("/").last()
    val queryStringAsList = javaParsedUri.query.split("&")
    // admit only ONE parameter: receiver-name.
    if (queryStringAsList.size != 1) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'${paytoLine}' has unsupported query string")
    }
    val splitParameter = queryStringAsList.first().split("=")
    if (splitParameter.first() != "receiver-name" && splitParameter.first() != "sender-name") {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'${paytoLine}' has unsupported query string")
    }
    val receiverName = splitParameter.last()
    return Payto(iban = iban, name = receiverName)
}