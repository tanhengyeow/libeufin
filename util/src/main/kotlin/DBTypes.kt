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

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import java.math.BigDecimal
import java.math.RoundingMode

const val SCALE_TWO = 2
const val NUMBER_MAX_DIGITS = 20
class BadAmount(badValue: Any?) : Exception("Value '${badValue}' is not a valid amount")

/**
 * Any number can become a Amount IF it does NOT need to be rounded to comply to the scale == 2.
 */
typealias Amount = BigDecimal

class AmountColumnType : ColumnType() {
    override fun sqlType(): String  = "DECIMAL(${NUMBER_MAX_DIGITS}, ${SCALE_TWO})"
        override fun valueFromDB(value: Any): Any {
            val valueFromDB = super.valueFromDB(value)
            try {
                return when (valueFromDB) {
                    is BigDecimal -> valueFromDB.setScale(SCALE_TWO, RoundingMode.UNNECESSARY)
                    is Double -> BigDecimal.valueOf(valueFromDB).setScale(SCALE_TWO, RoundingMode.UNNECESSARY)
                    is Float -> BigDecimal(java.lang.Float.toString(valueFromDB)).setScale(
                        SCALE_TWO,
                        RoundingMode.UNNECESSARY
                    )
                    is Int -> BigDecimal(valueFromDB)
                    is Long -> BigDecimal.valueOf(valueFromDB)
                    else -> valueFromDB
                }
            } catch (e: Exception) {
                e.printStackTrace()
                throw BadAmount(value)
            }
        }

        override fun valueToDB(value: Any?): Any? {
            try {
                (value as BigDecimal).setScale(SCALE_TWO, RoundingMode.UNNECESSARY)
            } catch (e: Exception) {
                e.printStackTrace()
                throw BadAmount(value)
            }

            if (value.compareTo(BigDecimal.ZERO) == 0) {
                throw BadAmount(value)
            }
            return super.valueToDB(value)
        }
}

/**
 * Make sure the number entered by upper layers does not need any rounding
 * to conform to scale == 2
 */
fun Table.amount(name: String): Column<Amount> {
    return registerColumn(name, AmountColumnType())
}