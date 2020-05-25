package tech.libeufin.util

import java.time.*
import java.time.format.DateTimeFormatter

fun LocalDateTime.toZonedString(): String {
    val dateFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    return dateFormatter.format(this.atZone(ZoneId.systemDefault()))
}

fun LocalDateTime.toDashedDate(): String {
    val dtf = DateTimeFormatter.ISO_LOCAL_DATE
    return dtf.format(this)
}

fun parseDashedDate(date: String): LocalDateTime {
    val dtf = DateTimeFormatter.ISO_LOCAL_DATE
    return LocalDateTime.from(LocalDate.parse(date, dtf))
}

fun importDateFromMillis(millis: Long): LocalDateTime {
    return LocalDateTime.ofInstant(
        Instant.ofEpochMilli(millis),
        ZoneId.systemDefault()
    )
}

fun LocalDateTime.millis(): Long {
    val instant = Instant.from(this)
    return instant.toEpochMilli()
}