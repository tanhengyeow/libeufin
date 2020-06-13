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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.IOUtils
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel

fun List<ByteArray>.zip(): ByteArray {
    val baos = ByteArrayOutputStream()
    val asf = ArchiveStreamFactory().createArchiveOutputStream(
        ArchiveStreamFactory.ZIP,
        baos
    )
    for (fileIndex in this.indices) {
        val zae = ZipArchiveEntry("File $fileIndex")
        asf.putArchiveEntry(zae)
        val bais = ByteArrayInputStream(this[fileIndex])
        IOUtils.copy(bais, asf)
        bais.close()
        asf.closeArchiveEntry()
    }
    asf.finish()
    baos.close()
    return baos.toByteArray()
}

fun ByteArray.prettyPrintUnzip(): String {
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

fun ByteArray.unzipWithLambda(process: (Pair<String, String>) -> Unit) {
    val mem = SeekableInMemoryByteChannel(this)
    val zipFile = ZipFile(mem)
    zipFile.getEntriesInPhysicalOrder().iterator().forEach {
        process(
            Pair(it.name, zipFile.getInputStream(it).readAllBytes().toString(Charsets.UTF_8))
        )
    }
}