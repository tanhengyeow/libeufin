package tech.libeufin.util

fun ByteArray.toHexString() : String {
    return this.joinToString("") {
        java.lang.String.format("%02x", it)
    }
}
