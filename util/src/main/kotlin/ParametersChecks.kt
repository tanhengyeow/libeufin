package tech.libeufin.util

import io.ktor.application.ApplicationCall
import io.ktor.http.HttpStatusCode

fun expectInt(param: String): Int {
    return try {
        param.toInt()
    } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest,"'$param' is not Int")
    }
}

fun <T>expectNonNull(param: T?): T {
    return param ?: throw EbicsProtocolError(
        HttpStatusCode.BadRequest,
        "Non-null value expected."
    )
}

fun expectLong(param: String): Long {
    return try {
        param.toLong()
    } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest,"'$param' is not Long")
    }
}

fun expectLong(param: String?): Long? {
    if (param != null) {
        return expectLong(param)
    }
    return null
}


fun ApplicationCall.expectUrlParameter(name: String): String {
    return this.request.queryParameters[name]
        ?: throw EbicsProtocolError(HttpStatusCode.BadRequest, "Parameter '$name' not provided in URI")
}