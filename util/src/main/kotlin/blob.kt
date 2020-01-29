package tech.libeufin.util

import java.sql.Blob

fun Blob.toByteArray(): ByteArray {
    return this.binaryStream.readAllBytes()
}