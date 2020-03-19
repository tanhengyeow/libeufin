package tech.libeufin.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.IOUtils
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel


fun ByteArray.zip(): ByteArray {

    val baos = ByteArrayOutputStream()
    val asf = ArchiveStreamFactory().createArchiveOutputStream(ArchiveStreamFactory.ZIP, baos)
    val zae = ZipArchiveEntry("File 1")
    asf.putArchiveEntry(zae) // link Zip archive to output stream.

    val bais = ByteArrayInputStream(this)
    IOUtils.copy(bais, asf)
    bais.close()
    asf.closeArchiveEntry()
    asf.finish()
    baos.close()
    return baos.toByteArray()
}

fun ByteArray.unzip(): String {
    val mem = SeekableInMemoryByteChannel(this)
    val zipFile = ZipFile(mem)
    val s = java.lang.StringBuilder()
    zipFile.getEntriesInPhysicalOrder().iterator().forEach { entry ->
        s.append("<=== File ${entry.name} ===>\n")
        s.append(zipFile.getInputStream(entry).readAllBytes().toString(Charsets.UTF_8))
        s.append("\n")
    }
    return s.toString()
}