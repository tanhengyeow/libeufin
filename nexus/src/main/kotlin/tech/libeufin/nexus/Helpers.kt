package tech.libeufin.nexus

import io.ktor.http.HttpStatusCode
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel

/**
 * Inserts spaces every 2 characters, and a newline after 8 pairs.
 */
fun chunkString(input: String): String {
    val ret = StringBuilder()
    var columns = 0
    for (i in input.indices) {
        if ((i + 1).rem(2) == 0) {
            if (columns == 15) {
                ret.append(input[i] + "\n")
                columns = 0
                continue
            }
            ret.append(input[i] + " ")
            columns++
            continue
        }
        ret.append(input[i])
    }
    return ret.toString().toUpperCase()
}

fun expectId(param: String?): String {
    return param ?: throw NexusError(HttpStatusCode.BadRequest, "Bad ID given")
}

fun unzipOrderData(od: ByteArray): String {
    val mem = SeekableInMemoryByteChannel(od)
    val zipFile = ZipFile(mem)
    val s = java.lang.StringBuilder()
    zipFile.getEntriesInPhysicalOrder().iterator().forEach { entry ->
        s.append("<=== File ${entry.name} ===>\n")
        s.append(zipFile.getInputStream(entry).readAllBytes().toString(Charsets.UTF_8))
        s.append("\n")
    }
    return s.toString()
}
