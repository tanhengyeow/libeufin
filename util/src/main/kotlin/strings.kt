package tech.libeufin.util
import java.util.*

fun ByteArray.toHexString() : String {
    return this.joinToString("") {
        java.lang.String.format("%02x", it)
    }
}

fun bytesToBase64(bytes: ByteArray): String {
    return Base64.getEncoder().encodeToString(bytes)
}

fun base64ToBytes(encoding: String): ByteArray {
    return Base64.getDecoder().decode(encoding)
}
