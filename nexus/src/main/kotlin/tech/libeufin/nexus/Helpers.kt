package tech.libeufin.nexus

import io.ktor.application.ApplicationCall
import io.ktor.http.HttpStatusCode

/**
 * Inserts spaces every 2 characters, and a newline after 8 pairs.
 */
fun chunkString(input: String): String {
    val ret = StringBuilder()
    var columns = 0
    for (i in input.indices) {
        if ((i + 1).rem(2) == 0) {
            if (columns == 15) {
                ret.append(input[i] + "\n")
                columns = 0
                continue
            }
            ret.append(input[i] + " ")
            columns++
            continue
        }
        ret.append(input[i])
    }
    return ret.toString().toUpperCase()
}

fun expectId(param: String?): String {
    return param ?: throw NexusError(HttpStatusCode.BadRequest, "Bad ID given")
}

/* Needs a transaction{} block to be called */
fun expectIdTransaction(param: String?): EbicsSubscriberEntity {
    if (param == null) {
        throw NexusError(HttpStatusCode.BadRequest, "Null Id given")
    }
    return EbicsSubscriberEntity.findById(param) ?: throw NexusError(HttpStatusCode.NotFound, "Subscriber: $param not found")
}

fun ApplicationCall.expectUrlParameter(name: String): String {
    return this.request.queryParameters[name]
        ?: throw NexusError(HttpStatusCode.BadRequest, "Parameter '$name' not provided in URI")
}

/* Needs a transaction{} block to be called */
fun expectAcctidTransaction(param: String?): EbicsAccountInfoEntity {
    if (param == null) {
        throw NexusError(HttpStatusCode.BadRequest, "Null Acctid given")
    }
    return EbicsAccountInfoEntity.findById(param) ?: throw NexusError(HttpStatusCode.NotFound, "Account: $param not found")
}