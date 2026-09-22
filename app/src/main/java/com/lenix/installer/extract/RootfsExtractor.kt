package com.lenix.installer.extract

import com.lenix.vm.VmError
import com.lenix.vm.VmException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/** Live view of one archive entry being extracted, for the install progress UI. */
data class ExtractionProgress(
    /** The layer being expanded (`base`, `desktop-openbox`, …). */
    val layerId: String,
    /** The member most recently written, relative to the RootFS. */
    val currentEntry: String,
    /** Bytes materialized so far, across all entries of this layer. */
    val bytesDone: Long,
    /** The uncompressed size the manifest predicts for this layer (0 when unknown). */
    val bytesTotal: Long,
)

/** What one layer contributed to the RootFS — recorded in the instance provenance. */
data class ExtractionReport(
    val files: Int = 0,
    val directories: Int = 0,
    val symlinks: Int = 0,
    val hardLinks: Int = 0,
    /** Device nodes, FIFOs and sockets: Android cannot host them, PRoot binds them. */
    val skippedSpecial: Int = 0,
    val bytesWritten: Long = 0,
) {
    /** Entries actually materialized in the guest filesystem. */
    val entries: Int get() = files + directories + symlinks + hardLinks
}

/**
 * Streaming RootFS extractor (docs/ROOTFS_SYSTEM.md §2 step [4], Phase 5).
 *
 * Expands a verified layer archive into the staging directory member by member and never
 * buffers it: a Debian rootfs is tens of MB compressed and well over a GB unpacked, so the
 * only sane shape is `decompress → tar stream → write`. Pure JVM on purpose — the native
 * `libpvmnative.so` extractor (docs/NATIVE_BINARIES.md H4) replaces the *decompressor* in
 * Phase 6 without changing this class's contract.
 *
 * Trust and safety rules, enforced here because archive content stays untrusted data even
 * after the checksum gate:
 *  * **containment** — every member name is normalized (no `..`, no empty segments,
 *    leading `./` and `/` stripped) and the parent directory is canonicalized, so a
 *    symlinked ancestor cannot redirect a write outside the staging root (the tar-slip /
 *    CVE-2019-9256 family);
 *  * **no setuid** — only the owner `rwx` bits are applied, so a setuid binary inside the
 *    guest never becomes a host privilege boundary (PRoot fakes root anyway);
 *  * **size caps** — a layer may not expand past [expansionCap] (the manifest's
 *    `uncompressedBytes` × [maxExpansionFactor] plus slack) or past [maxEntries] members,
 *    which bounds decompression bombs;
 *  * **space guard** — free space is re-checked while extracting via [freeBytes], so a
 *    nearly full device fails as [VmError.INSUFFICIENT_STORAGE] instead of corrupting the
 *    staging tree with write errors;
 *  * **special files are skipped** — character/block devices, FIFOs and sockets are counted
 *    in [ExtractionReport.skippedSpecial]; `/dev` is bind-mounted from the host at launch;
 *  * **wrapper directories are lifted** — a layer whose members all sit under one leading
 *    top-level directory (proot-distro tars the build dir by name) has that wrapper moved
 *    aside so the guest filesystem lands at the root, otherwise the instance boots with
 *    nothing at `/` (no `/bin/sh`, no `/etc`);
 *  * **cancellation is honored** — [onProgress] runs in the collector's coroutine, so a
 *    cancelled install stops at the next member and the caller discards the staging
 *    directory. Extraction restarts from scratch by design; the resumable half of an
 *    install is the download (ADR-015).
 *
 * Symlinks are created verbatim (absolute ones like `/bin → /usr/bin` included): they are
 * guest data, this writer never follows them, and PRoot resolves them inside the RootFS at
 * runtime. Hard links are re-created inside the staging root, and a dangling one is an
 * error rather than a silently broken guest. Layers are extracted in manifest order, so a
 * desktop layer simply overlays the base one.
 */
class RootfsExtractor(
    /** Free bytes on the volume being written; [Long.MAX_VALUE] means "unknown". */
    private val freeBytes: (File) -> Long = { Long.MAX_VALUE },
    private val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    private val maxExpansionFactor: Double = MAX_EXPANSION_FACTOR,
    private val expansionSlackBytes: Long = EXPANSION_SLACK_BYTES,
    private val capWithoutManifestHint: Long = CAP_WITHOUT_MANIFEST_HINT,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    /**
     * Extracts [archive] into [into], which must already exist (the installer stages into
     * `instances/<id>/.tmp/rootfs/`).
     *
     * @param expectedUncompressedBytes the manifest's estimate for this layer; it drives
     *   both the progress numbers and the expansion cap.
     * @throws VmException with [VmError.ROOTFS_EXTRACTION_FAILED] for a corrupt, escaping
     *   or over-large archive, [VmError.INSUFFICIENT_STORAGE] when the device runs out of
     *   room, and [VmError.UNSUPPORTED_COMPRESSION] for a format this build cannot read.
     */
    suspend fun extract(
        archive: File,
        compression: LayerCompression,
        into: File,
        layerId: String = archive.name,
        expectedUncompressedBytes: Long = 0L,
        onProgress: suspend (ExtractionProgress) -> Unit = { },
    ): ExtractionReport {
        if (!compression.supportedByAppExtractor) {
            throw VmException(
                VmError.UNSUPPORTED_COMPRESSION,
                "Layer $layerId is a ${compression.canonicalName}-compressed archive, which " +
                    "this build cannot read.",
            )
        }
        if (!archive.isFile) {
            throw VmException(
                VmError.DOWNLOAD_CORRUPTED,
                "Layer $layerId is not in the cache to extract (${archive.name}).",
            )
        }
        val root = into.canonicalFile
        if (!root.isDirectory) {
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "The staging directory $into does not exist.",
            )
        }
        val session = Session(layerId, root, expansionCap(expectedUncompressedBytes), expectedUncompressedBytes)
        guardFreeSpace(root, session)

        try {
            openDecompressing(archive, compression).buffered().use { source ->
                TarArchiveInputStream(source).use { tar ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = tar.nextEntry ?: break
                        session.entries++
                        if (session.entries > maxEntries) {
                            throw VmException(
                                VmError.ROOTFS_EXTRACTION_FAILED,
                                "Layer $layerId declares more than $maxEntries entries; " +
                                    "refusing to keep extracting.",
                            )
                        }
                        if (session.entries and FREE_SPACE_CHECK_MASK == 0) guardFreeSpace(root, session)
                        writeEntry(tar, entry, session)
                        if (session.dirty) {
                            session.dirty = false
                            onProgress(session.progress())
                        }
                    }
                }
            }
        } catch (e: VmException) {
            throw e
        } catch (e: IOException) {
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "Layer $layerId is not a readable ${compression.canonicalName} tar archive " +
                    "(failed at '${session.lastEntry}'): ${e.message}",
                e,
            )
        }

        // A RootFS archive may wrap the whole filesystem in a single top-level directory
        // (e.g. proot-distro tars the build directory by name), which would otherwise leave
        // the guest sitting one level deep with nothing at the root. Realize the filesystem
        // at the root so the instance actually boots.
        unwrapSingleTopLevelDirectory(root)

        if (session.entries == 0) {
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "Layer $layerId is empty — a RootFS archive must contain entries.",
            )
        }
        val report = session.report()
        onProgress(session.progress().copy(bytesTotal = maxOf(session.expectedBytes, report.bytesWritten)))
        return report
    }

    /**
     * The ceiling for total extracted bytes of one layer: the manifest estimate scaled by
     * [maxExpansionFactor] plus [expansionSlackBytes] (the v0.1 estimates are deliberately
     * conservative), or a flat cap when a manifest gives no hint at all.
     */
    internal fun expansionCap(expectedUncompressedBytes: Long): Long {
        if (expectedUncompressedBytes <= 0L) return capWithoutManifestHint
        val scaled = expectedUncompressedBytes * maxExpansionFactor
        if (scaled.isNaN() || scaled <= 0L) return capWithoutManifestHint
        return if (scaled > capWithoutManifestHint.toDouble()) {
            scaled.toLong()
        } else {
            scaled.toLong() + expansionSlackBytes
        }
    }

    private fun openDecompressing(archive: File, compression: LayerCompression): InputStream {
        val raw = FileInputStream(archive)
        return when (compression) {
            LayerCompression.NONE -> raw
            LayerCompression.GZIP -> GZIPInputStream(raw, STREAM_BUFFER_BYTES)
            LayerCompression.XZ -> XZInputStream(raw, STREAM_BUFFER_BYTES)
            LayerCompression.ZSTD -> throw VmException(
                VmError.UNSUPPORTED_COMPRESSION,
                "zstd layers need the native Lenix extractor (Phase 6).",
            )
        }
    }

    private fun writeEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry, session: Session) {
        val relative = normalizeEntryName(entry.name, session.layerId)
        session.lastEntry = relative
        if (relative.isEmpty()) return // the archive's own root (`./`) — already exists

        val target = containedTarget(relative, session)
        when {
            entry.isDirectory -> {
                if (!target.mkdirs() && !target.isDirectory) {
                    throw VmException(
                        VmError.ROOTFS_EXTRACTION_FAILED,
                        "Could not create ${session.displayOf(target)} (from '${entry.name}').",
                    )
                }
                applyMode(target, entry.mode, directory = true)
                stampTime(target, entry)
                session.directories++
                session.dirty = true
            }

            entry.isSymbolicLink -> createSymbolicLink(entry, target, relative, session)
            entry.isLink -> createHardLink(entry, target, relative, session)

            // `TarArchiveEntry.isFile()` is defined as "not a directory and not a link", so
            // it claims character devices, block devices and FIFOs too. They must be
            // identified first or an app-uid filesystem would gain fake /dev entries.
            entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO ->
                skipSpecial(session)

            entry.isFile -> {
                deleteIfLink(target, session)
                writeFile(tar, target, entry, session)
                session.files++
                session.dirty = true
            }

            else -> skipSpecial(session)
        }
    }

    /**
     * Counts a member this platform cannot or should not materialize — device nodes, FIFOs,
     * sockets, GNU dump directories. The guest gets `/dev` bind-mounted from the host at
     * launch, so skipping is correct, but it is also *reported*: an install that skipped
     * thousands of entries looks different from one that skipped three.
     */
    private fun skipSpecial(session: Session) {
        session.skippedSpecial++
        session.dirty = true
    }

    private fun createSymbolicLink(
        entry: TarArchiveEntry,
        target: File,
        relative: String,
        session: Session,
    ) {
        val linkTarget = entry.linkName ?: ""
        if (linkTarget.isBlank()) {
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "Symlink '${entry.name}' in layer ${session.layerId} has no target.",
            )
        }
        deleteIfLink(target, session)
        try {
            target.parentFile?.mkdirs()
            Files.createSymbolicLink(
                target.toPath(),
                target.toPath().fileSystem.getPath(linkTarget),
            )
        } catch (e: Exception) {
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "This device's storage cannot hold the symlink '$relative' → '$linkTarget' " +
                    "from layer ${session.layerId}: ${e.message}",
                e,
            )
        }
        session.symlinks++
        session.dirty = true
    }

    private fun createHardLink(
        entry: TarArchiveEntry,
        target: File,
        relative: String,
        session: Session,
    ) {
        val linkRelative = normalizeEntryName(entry.linkName ?: "", session.layerId)
        val existing = if (linkRelative.isEmpty()) target else containedTarget(linkRelative, session)
        if (!existing.isFile) {
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "Hard link '$relative' in layer ${session.layerId} points at " +
                    "'$linkRelative', which the archive never wrote.",
            )
        }
        deleteIfLink(target, session)
        try {
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            Files.createLink(target.toPath(), existing.toPath())
        } catch (e: Exception) {
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "Could not re-create the hard link '$relative' from layer " +
                    "${session.layerId}: ${e.message}",
                e,
            )
        }
        session.hardLinks++
        session.dirty = true
    }

    private fun writeFile(
        tar: TarArchiveInputStream,
        target: File,
        entry: TarArchiveEntry,
        session: Session,
    ) {
        val declared = entry.size
        try {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                var entryBytes = 0L
                while (true) {
                    val read = tar.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    entryBytes += read
                    if (entryBytes >= PROGRESS_BYTES_INTERVAL) {
                        session.bytesWritten += entryBytes
                        entryBytes = 0L
                        session.dirty = true
                        session.requireBelowCap(entry.name, target)
                    }
                }
                session.bytesWritten += entryBytes
                session.requireBelowCap(entry.name, target)
            }
        } catch (e: VmException) {
            throw e
        } catch (e: IOException) {
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "Could not write '${entry.name}' from layer ${session.layerId}: ${e.message}",
                e,
            )
        }
        if (declared >= 0L && target.length() != declared) {
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "'${entry.name}' from layer ${session.layerId} is ${target.length()} bytes on " +
                    "disk; the tar header says $declared (truncated archive?).",
            )
        }
        applyMode(target, entry.mode, directory = false)
        stampTime(target, entry)
    }

    /**
     * Maps an archive member name to a path relative to the RootFS root, rejecting
     * anything that could escape it. Empty result means "the root directory itself".
     */
    private fun normalizeEntryName(name: String, layerId: String): String {
        if (name.isBlank()) return ""
        val segments = ArrayList<String>(4)
        for (segment in name.replace('\\', '/').split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> throw VmException(
                    VmError.ROOTFS_EXTRACTION_FAILED,
                    escapeMessage(layerId, name),
                )
                else -> {
                    if (!isValidPathComponent(segment)) {
                        throw VmException(
                            VmError.ROOTFS_EXTRACTION_FAILED,
                            escapeMessage(layerId, name),
                        )
                    }
                    segments.add(segment)
                }
            }
        }
        return segments.joinToString("/")
    }

    private fun escapeMessage(layerId: String, name: String) =
        "Layer $layerId contains the member '$name', which cannot be placed inside the " +
            "RootFS (escaping path or unusable file name); the archive is rejected."

    /** Names the underlying filesystem cannot hold, checked before touching storage. */
    private fun isValidPathComponent(segment: String): Boolean {
        if (segment.isEmpty() || segment == ".." || segment == ".") return false
        if (segment.length > MAX_NAME_BYTES) return false
        return segment.all { it.code >= 0x20 && it.code != 0x7F }
    }

    /**
     * Resolves [relative] under the staging root and proves the *existing* part of the
     * path stays inside it. Canonicalizing the parent (not the leaf) is what catches the
     * "directory replaced by a symlink" attack: an entry like `etc` → `/` written earlier
     * in the same archive would otherwise make `etc/passwd` write to the real `/etc`.
     */
    private fun containedTarget(relative: String, session: Session): File {
        val target = File(session.root, relative)
        val parent = target.parentFile
        val canonicalParent = (parent ?: session.root).canonicalFile
        val root = session.root.path
        val inside = canonicalParent.path == root ||
            canonicalParent.path.startsWith(root + File.separator)
        if (!inside) {
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "Layer ${session.layerId} would write '${relative}' outside the RootFS " +
                    "(resolved to ${canonicalParent.path}); the archive is rejected.",
            )
        }
        return target
    }

    /**
     * Never write *through* a symlink that the archive left behind: drop the link and
     * create the real entry instead. Same for a dangling hard link target.
     */
    private fun deleteIfLink(target: File, session: Session) {
        if (!Files.isSymbolicLink(target.toPath())) return
        if (!target.delete()) {
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "Could not remove the existing symlink ${session.displayOf(target)} before " +
                    "writing the layer's real entry.",
            )
        }
    }

    /**
     * Applies the archive's permissions, normalized: the owner bits (shifted to apply to
     * the app uid that owns everything here) and nothing else — group and other end up with
     * no access at all, and setuid, setgid and the sticky bit are dropped. Directories are
     * always kept writable and traversable so extraction and cleanup can continue.
     */
    private fun applyMode(target: File, mode: Int, directory: Boolean) {
        // Only the low 9 bits are permissions; tar archives also carry the file-type bits
        // (S_IFREG, S_IFCHR, …) in the same field, and reading the owner nibble without
        // masking them turns a plain 0644 file into an executable one.
        val ownerBits = (mode and PERMISSION_BITS) shr OWNER_SHIFT
        val readable = ownerBits and 4 != 0 || directory
        val writable = ownerBits and 2 != 0 || directory
        val executable = ownerBits and 1 != 0 || directory
        // Clear everyone first, then grant the owner: `File.set*(true, true)` only *adds*
        // bits, so without this the archive's group/other permissions would survive into
        // the RootFS (a 0644 member would stay group- and world-readable).
        target.setReadable(false, false)
        target.setWritable(false, false)
        target.setExecutable(false, false)
        target.setReadable(readable, true)
        target.setWritable(writable, true)
        target.setExecutable(executable, true)
    }

    private fun stampTime(target: File, entry: TarArchiveEntry) {
        val time = runCatching { entry.lastModifiedDate.time }.getOrNull() ?: return
        if (time > 0L) target.setLastModified(time)
    }

    /**
     * Lifts a single top-level wrapper directory up to the RootFS root.
     *
     * Some upstream archives — notably termux/proot-distro's, which is built with
     * `tar -C $WORKDIR debian-aarch64` — store the entire filesystem under one leading
     * directory named after the build tree. Extracted verbatim that leaves every member
     * one level deep (`debian-aarch64/bin/sh`, …) and nothing at the filesystem root, so
     * the instance cannot boot (no `/bin/sh`, no `/etc`, …).
     *
     * The wrapper is only hoisted when it genuinely *looks like a rootfs*: the archive has
     * exactly one top-level directory (a real RootFS root has several — `/bin`, `/usr`,
     * `/etc`, …), and that directory contains at least [ROOTFS_WRAPPER_MIN_MARKERS] of the
     * standard top-level rootfs directories. A single `etc/` holding a file or two is a
     * legitimate rootfs entry, not a wrapper, so it is never touched.
     */
    private fun unwrapSingleTopLevelDirectory(root: File) {
        val children = root.listFiles() ?: return
        if (children.size != 1) return
        val wrapper = children[0]
        // A symlink to a directory is not a wrapper: `isDirectory` would follow it and we
        // must never hoist the contents of something the guest resolves by link.
        if (!wrapper.isDirectory || Files.isSymbolicLink(wrapper.toPath())) return
        val nested = wrapper.listFiles() ?: return
        val markerCount = nested.count {
            it.isDirectory && it.name in ROOTFS_TOP_LEVEL_DIRS
        }
        if (markerCount < ROOTFS_WRAPPER_MIN_MARKERS) return
        for (child in nested) {
            val dest = File(root, child.name)
            if (dest.exists()) {
                throw VmException(
                    VmError.ROOTFS_EXTRACTION_FAILED,
                    "Could not unwrap the layer's top-level directory: $dest already exists.",
                )
            }
            try {
                Files.move(child.toPath(), dest.toPath())
            } catch (e: IOException) {
                throw VmException(
                    VmError.ROOTFS_EXTRACTION_FAILED,
                    "Could not lift '${child.name}' out of the layer's top-level directory: " +
                        "${e.message}",
                    e,
                )
            }
        }
        if (!wrapper.delete()) {
            // The wrapper is empty now; if it cannot be removed the install still succeeds,
            // leaving a stray directory is cosmetic, not fatal.
            wrapper.deleteRecursively()
        }
    }

    private fun guardFreeSpace(root: File, session: Session) {
        val remaining = freeBytes(root)
        if (remaining in 0 until minFreeBytes) {
            throw VmException(
                VmError.INSUFFICIENT_STORAGE,
                "Free space dropped to $remaining bytes while extracting layer " +
                    "${session.layerId}; at least $minFreeBytes must stay free.",
            )
        }
    }

    /** Per-archive extraction state: counters, the cap, and the last emitted progress. */
    private class Session(
        val layerId: String,
        val root: File,
        val maxBytes: Long,
        val expectedBytes: Long,
    ) {
        var entries = 0
        var files = 0
        var directories = 0
        var symlinks = 0
        var hardLinks = 0
        var skippedSpecial = 0
        var bytesWritten = 0L
        var lastEntry: String = ""
        var dirty = false

        fun progress() = ExtractionProgress(
            layerId = layerId,
            currentEntry = lastEntry,
            bytesDone = bytesWritten,
            bytesTotal = expectedBytes,
        )

        fun report() = ExtractionReport(
            files = files,
            directories = directories,
            symlinks = symlinks,
            hardLinks = hardLinks,
            skippedSpecial = skippedSpecial,
            bytesWritten = bytesWritten,
        )

        fun displayOf(file: File): String =
            file.absolutePath.removePrefix(root.absolutePath).trimStart('/')

        fun requireBelowCap(entryName: String, target: File) {
            if (bytesWritten <= maxBytes) return
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "Layer $layerId expanded past $maxBytes bytes at '$entryName' " +
                    "(${displayOf(target)} on disk); the archive does not match the manifest.",
            )
        }
    }

    companion object {
        /** Read/write buffer for the decompressed stream. */
        const val STREAM_BUFFER_BYTES = 64 * 1024

        /** Progress is emitted at most every this many extracted bytes. */
        const val PROGRESS_BYTES_INTERVAL = 1L shl 20

        /** Free space is re-probed every 32nd entry. */
        const val FREE_SPACE_CHECK_MASK = 31

        /** Below this, extraction stops with [VmError.INSUFFICIENT_STORAGE]. */
        const val DEFAULT_MIN_FREE_BYTES = 32L shl 20

        /** A layer may not exceed this multiple of the manifest's uncompressed size. */
        const val MAX_EXPANSION_FACTOR = 4.0

        /** Slack on top of that multiple, since v0.1 sizes are estimates. */
        const val EXPANSION_SLACK_BYTES = 64L shl 20

        /** Absolute ceiling when a manifest gives no size hint, and the cap's own floor. */
        const val CAP_WITHOUT_MANIFEST_HINT = 32L shl 30

        /** Entries per layer, a guard against pathological archives. */
        const val DEFAULT_MAX_ENTRIES = 2_000_000

        /**
         * A single top-level directory is only treated as a wrapping rootfs when it holds at
         * least this many of the standard [ROOTFS_TOP_LEVEL_DIRS]. A lone `etc/`, `usr/` or
         * `data/` carrying ordinary files is a real rootfs directory, not a wrapper.
         */
        const val ROOTFS_WRAPPER_MIN_MARKERS = 2

        /**
         * The standard directories a Debian-ish RootFS places at its root. A wrapper
         * directory (proot-distro's `debian-aarch64/`) contains several of these; a plain
         * top-level directory in a normally-laid-out archive is one of these itself.
         */
        private val ROOTFS_TOP_LEVEL_DIRS = setOf(
            "bin", "sbin", "usr", "lib", "lib64", "etc", "var", "home", "root", "opt",
            "dev", "proc", "sys", "run", "tmp", "boot", "mnt", "media", "srv",
        )

        /** Linux file names are 255 bytes; longer is a crafted archive. */
        const val MAX_NAME_BYTES = 255

        /** The tar mode field's owner bits start here. */
        private const val OWNER_SHIFT = 6
        private const val OWNER_BITS = 7

        /** The permission bits of a tar mode field; everything above it is file type/setuid. */
        private const val PERMISSION_BITS = 0b111_111_111
    }
}
