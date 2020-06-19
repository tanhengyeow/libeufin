/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.util

import java.time.*
import java.time.format.DateTimeFormatter

fun LocalDateTime.toZonedString(): String {
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(this.atZone(ZoneId.systemDefault()))
}

fun LocalDateTime.toDashedDate(): String {
    return DateTimeFormatter.ISO_DATE.format(this)
}

fun parseDashedDate(date: String): LocalDateTime {
    val dtf = DateTimeFormatter.ISO_DATE
    val asDate = LocalDate.parse(date, dtf)
    return asDate.atStartOfDay()
}

fun importDateFromMillis(millis: Long): LocalDateTime {
    return LocalDateTime.ofInstant(
        Instant.ofEpochMilli(millis),
        ZoneId.systemDefault()
    )
}

fun LocalDateTime.millis(): Long {
    val instant = Instant.from(this.atZone(ZoneId.systemDefault()))
    return instant.toEpochMilli()
}