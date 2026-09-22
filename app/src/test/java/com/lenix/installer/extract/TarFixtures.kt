package com.lenix.installer.extract

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * Real tar archives for the extractor tests, built with the same library the extractor
 * reads with — fixtures must be genuine archives, not hand-written header bytes that
 * only happen to look like one.
 *
 * Every archive is described by a list of [Entry]s so a test can say exactly what the
 * untrusted layer tried to do (a symlink escape, a device node, a huge file).
 */
internal object TarFixtures {

    /** One tar member; `linkName` is used by symlink and hard-link entries. */
    internal data class Entry(
        val name: String,
        val bytes: ByteArray = ByteArray(0),
        val mode: Int = 0b110_100_100, // 0644
        val directory: Boolean = false,
        val symlinkTo: String? = null,
        val hardLinkTo: String? = null,
        val type: Byte = TarConstants.LF_NORMAL,
        val mtime: Long? = null,
    )

    /** Uncompressed `tar`. */
    fun tar(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            // commons-compress exposes only the setter here, so it is called directly.
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
            entries.forEach { entry -> tar.write(entry) }
            tar.finish()
        }
        return out.toByteArray()
    }

    fun tarGz(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz -> gz.write(tar(entries)) }
        return out.toByteArray()
    }

    fun tarXz(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        XZOutputStream(out, LZMA2Options()).use { xz ->
            xz.write(tar(entries))
        }
        return out.toByteArray()
    }

    /** Writes [bytes] as an archive into [file], so the extractor can stream from disk. */
    fun archiveInto(file: File, bytes: ByteArray): File {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    private fun TarArchiveOutputStream.write(entry: Entry) {
        val type = when {
            entry.directory -> TarConstants.LF_DIR
            entry.symlinkTo != null -> TarConstants.LF_SYMLINK
            entry.hardLinkTo != null -> TarConstants.LF_LINK
            else -> entry.type
        }
        val archiveEntry = TarArchiveEntry(entry.name, type).apply {
            mode = entry.mode
            if (type == TarConstants.LF_NORMAL || type == TarConstants.LF_OLDNORM) {
                size = entry.bytes.size.toLong()
            }
            entry.symlinkTo?.let { linkName = it }
            entry.hardLinkTo?.let { linkName = it }
            entry.mtime?.let { setModTime(it) }
        }
        putArchiveEntry(archiveEntry)
        if (type == TarConstants.LF_NORMAL && entry.bytes.isNotEmpty()) {
            write(entry.bytes)
        }
        closeArchiveEntry()
    }

    /** Reads a regular file back for content assertions. */
    fun readText(file: File): String = String(Files.readAllBytes(file.toPath()))
}
