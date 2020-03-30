package tech.libeufin.util
import org.apache.commons.codec.binary.Base32
import java.math.BigInteger
import java.util.*

fun ByteArray.toHexString() : String {
    return this.joinToString("") {
        java.lang.String.format("%02X", it)
    }
}

private fun toDigit(hexChar: Char): Int {
    val digit = Character.digit(hexChar, 16)
    require(digit != -1) { "Invalid Hexadecimal Character: $hexChar" }
    return digit
}

private fun hexToByte(hexString: String): Byte {
    val firstDigit: Int = toDigit(hexString[0])
    val secondDigit: Int = toDigit(hexString[1])
    return ((firstDigit shl 4) + secondDigit).toByte()
}

fun decodeHexString(hexString: String): ByteArray {
    val hs = hexString.replace(" ", "").replace("\n", "")
    require(hs.length % 2 != 1) { "Invalid hexadecimal String supplied." }
    val bytes = ByteArray(hs.length / 2)
    var i = 0
    while (i < hs.length) {
        bytes[i / 2] = hexToByte(hs.substring(i, i + 2))
        i += 2
    }
    return bytes
}

fun bytesToBase64(bytes: ByteArray): String {
    return Base64.getEncoder().encodeToString(bytes)
}

fun base64ToBytes(encoding: String): ByteArray {
    return Base64.getDecoder().decode(encoding)
}

fun BigInteger.toUnsignedHexString(): String {
    val signedValue = this.toByteArray()
    require(this.signum() > 0) { "number must be positive" }
    val start = if (signedValue[0] == 0.toByte()) { 1 } else { 0 }
    val bytes = Arrays.copyOfRange(signedValue, start, signedValue.size)
    return bytes.toHexString()
}