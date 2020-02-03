/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.util

import java.lang.IllegalArgumentException
import java.security.SecureRandom
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream

/**
 * Helpers for dealing with order compression, encryption, decryption, chunking and re-assembly.
 */
object EbicsOrderUtil {

    // Decompression only, no XML involved.
    fun decodeOrderData(encodedOrderData: ByteArray): ByteArray {
        return InflaterInputStream(encodedOrderData.inputStream()).use {
            it.readAllBytes()
        }
    }

    inline fun <reified T> decodeOrderDataXml(encodedOrderData: ByteArray): T {
        return InflaterInputStream(encodedOrderData.inputStream()).use {
            val bytes = it.readAllBytes()
            println("decoded order data bytes ${bytes.toString(Charsets.UTF_8)}")
            XMLUtil.convertStringToJaxb<T>(bytes.toString(Charsets.UTF_8)).value
        }
    }

    inline fun <reified T> encodeOrderDataXml(obj: T): ByteArray {
        val bytes = XMLUtil.convertJaxbToString(obj).toByteArray()
        return DeflaterInputStream(bytes.inputStream()).use {
            it.readAllBytes()
        }
    }

    fun generateTransactionId(): String {
        val rng = SecureRandom()
        val res = ByteArray(16)
        rng.nextBytes(res)
        return res.toHexString().toUpperCase()
    }

    /**
     * Calculate the resulting size of base64-encoding data of the given length,
     * including padding.
     */
    fun calculateBase64EncodedLength(dataLength: Int): Int {
        val blocks = (dataLength + 3 - 1) / 3
        return blocks * 4
    }

    fun checkOrderIDOverflow(n: Int): Boolean {
        if (n <= 0)
            throw IllegalArgumentException()
        val base = 10 + 26
        return n >= base * base
    }

    private fun getDigitChar(x: Int): Char {
        if (x < 10) {
            return '0' + x
        }
        return 'A' + (x - 10)
    }

    fun computeOrderIDFromNumber(n: Int): String {
        if (n <= 0)
            throw IllegalArgumentException()
        if (checkOrderIDOverflow(n))
            throw IllegalArgumentException()
        var ni = n
        val base = 10 + 26
        val x1 = ni % base
        ni = ni / base
        val x2 = ni % base
        val c1 = getDigitChar(x1)
        val c2 = getDigitChar(x2)
        return String(charArrayOf('O', 'R', c2, c1))
    }
}
