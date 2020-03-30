/*
 * This file is part of GNU Taler
 * (C) 2020 Taler Systems S.A.
 *
 * GNU Taler is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3, or (at your option) any later version.
 *
 * GNU Taler is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * GNU Taler; see the file COPYING.  If not, see <http://www.gnu.org/licenses/>
 */

package net.taler.wallet.crypto

import java.io.ByteArrayOutputStream

class EncodingException : Exception("Invalid encoding")


object Base32Crockford {

    private fun ByteArray.getIntAt(index: Int): Int {
        val x = this[index].toInt()
        return if (x >= 0) x else (x + 256)
    }

    private var encTable = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        val size = data.size
        var bitBuf = 0
        var numBits = 0
        var pos = 0
        while (pos < size || numBits > 0) {
            if (pos < size && numBits < 5) {
                val d = data.getIntAt(pos++)
                bitBuf = (bitBuf shl 8) or d
                numBits += 8
            }
            if (numBits < 5) {
                // zero-padding
                bitBuf = bitBuf shl (5 - numBits)
                numBits = 5
            }
            val v = bitBuf.ushr(numBits - 5) and 31
            sb.append(encTable[v])
            numBits -= 5
        }
        return sb.toString()
    }

    fun decode(encoded: String, out: ByteArrayOutputStream) {
        val size = encoded.length
        var bitpos = 0
        var bitbuf = 0
        var readPosition = 0

        while (readPosition < size || bitpos > 0) {
            //println("at position $readPosition with bitpos $bitpos")
            if (readPosition < size) {
                val v = getValue(encoded[readPosition++])
                bitbuf = (bitbuf shl 5) or v
                bitpos += 5
            }
            while (bitpos >= 8) {
                val d = (bitbuf ushr (bitpos - 8)) and 0xFF
                out.write(d)
                bitpos -= 8
            }
            if (readPosition == size && bitpos > 0) {
                bitbuf = (bitbuf shl (8 - bitpos)) and 0xFF
                bitpos = if (bitbuf == 0) 0 else 8
            }
        }
    }

    fun decode(encoded: String): ByteArray {
        val out = ByteArrayOutputStream()
        decode(encoded, out)
        return out.toByteArray()
    }

    private fun getValue(chr: Char): Int {
        var a = chr
        when (a) {
            'O', 'o' -> a = '0'
            'i', 'I', 'l', 'L' -> a = '1'
            'u', 'U' -> a = 'V'
        }
        if (a in '0'..'9')
            return a - '0'
        if (a in 'a'..'z')
            a = Character.toUpperCase(a)
        var dec = 0
        if (a in 'A'..'Z') {
            if ('I' < a) dec++
            if ('L' < a) dec++
            if ('O' < a) dec++
            if ('U' < a) dec++
            return a - 'A' + 10 - dec
        }
        throw EncodingException()
    }

    /**
     * Compute the length of the resulting string when encoding data of the given size
     * in bytes.
     *
     * @param dataSize size of the data to encode in bytes
     * @return size of the string that would result from encoding
     */
    @Suppress("unused")
    fun calculateEncodedStringLength(dataSize: Int): Int {
        return (dataSize * 8 + 4) / 5
    }

    /**
     * Compute the length of the resulting data in bytes when decoding a (valid) string of the
     * given size.
     *
     * @param stringSize size of the string to decode
     * @return size of the resulting data in bytes
     */
    @Suppress("unused")
    fun calculateDecodedDataLength(stringSize: Int): Int {
        return stringSize * 5 / 8
    }
}

