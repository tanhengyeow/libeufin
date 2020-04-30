package tech.libeufin.util

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

fun DateTime.toZonedString(): String {
    val dateFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    val instant = java.time.Instant.ofEpochMilli(this.millis)
    val zoned = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
    return dateFormatter.format(zoned)
}

fun DateTime.toDashedDate(): String {
    return this.toString("y-MM-d")
}

fun parseDashedDate(date: String): DateTime {
    logger.debug("Parsing date: $date")
    return DateTime.parse(date, DateTimeFormat.forPattern("y-M-d"))
}
