package tech.libeufin.nexus

import io.ktor.application.ApplicationCall
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.base64ToBytes
import javax.sql.rowset.serial.SerialBlob

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

fun expectInt(param: String): Int {
    return try {
        param.toInt()
    } catch (e: Exception) {
        throw NexusError(HttpStatusCode.BadRequest,"'$param' is not Int")
    }
}

fun expectLong(param: String): Long {
    return try {
        param.toLong()
    } catch (e: Exception) {
        throw NexusError(HttpStatusCode.BadRequest,"'$param' is not Long")
    }
}

fun expectLong(param: String?): Long? {
    if (param != null) {
        return expectLong(param)
    }
    return null
}

/* Needs a transaction{} block to be called */
fun expectAcctidTransaction(param: String?): EbicsAccountInfoEntity {
    if (param == null) {
        throw NexusError(HttpStatusCode.BadRequest, "Null Acctid given")
    }
    return EbicsAccountInfoEntity.findById(param) ?: throw NexusError(HttpStatusCode.NotFound, "Account: $param not found")
}

/**
 * This helper function parses a Authorization:-header line, decode the credentials
 * and returns a pair made of username and hashed (sha256) password.  The hashed value
 * will then be compared with the one kept into the database.
 */
fun extractUserAndHashedPassword(authorizationHeader: String): Pair<String, ByteArray> {
    val (username, password) = try {
        val split = authorizationHeader.split(" ")
        val valueUtf8 = String(base64ToBytes(split[1]), Charsets.UTF_8) // newline introduced here: BUG!
        valueUtf8.split(":")
    } catch (e: java.lang.Exception) {
        throw NexusError(
            HttpStatusCode.BadRequest, "invalid Authorization:-header received"
        )
    }
    return Pair(username, CryptoUtil.hashStringSHA256(password))
}

/**
 * Test HTTP basic auth.  Throws error if password is wrong
 *
 * @param authorization the Authorization:-header line.
 * @return subscriber id
 */
fun authenticateRequest(authorization: String?): String {
    val headerLine = if (authorization == null) throw NexusError(
        HttpStatusCode.BadRequest, "Authentication:-header line not found"
    ) else authorization
    val subscriber = transaction {
        val (user, pass) = extractUserAndHashedPassword(headerLine)
        EbicsSubscriberEntity.find {
            EbicsSubscribersTable.id eq user and (EbicsSubscribersTable.password eq SerialBlob(pass))
        }.firstOrNull()
    } ?: throw NexusError(HttpStatusCode.Forbidden, "Wrong password")
    return subscriber.id.value
}

fun parseDate(date: String): DateTime {
    return DateTime.parse(date, DateTimeFormat.forPattern("YYYY-MM-DD"))
}
