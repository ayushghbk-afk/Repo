package com.lenix.data.download

import com.lenix.util.Digests
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import java.io.File

/**
 * Content-addressed cache for RootFS layers (`filesDir/cache/layers/`), shared by
 * every instance and every install retry (docs/ROOTFS_SYSTEM.md §1, ADR-015).
 *
 * Files are named by their `sha256` so a layer is downloaded at most once no
 * matter how many instances reference it:
 *  - `<sha256>.layer`       completed, verified layer
 *  - `<sha256>.layer.part`  in-flight partial download (the resume point)
 *  - `<sha256>.layer.etag`  ETag captured from the download, for `If-Range`
 *                          validation when resuming after process death
 *
 * The hash doubles as a filename, so it is validated hard: a malformed `sha256`
 * from a manifest can never escape the cache directory.
 */
class LayerCache(
    private val dir: File,
) {
    /** Completed, verified layer file for [sha256] (may not exist yet). */
    fun cachedFile(sha256: String): File {
        requireValidSha256(sha256)
        return File(ensureDir(), "$sha256$FINAL_SUFFIX")
    }

    /** Partial download for [sha256] — the byte-exact resume point. */
    fun partFile(sha256: String): File {
        requireValidSha256(sha256)
        return File(ensureDir(), "$sha256$PART_SUFFIX")
    }

    /** ETag sidecar used to validate that a `.part` still matches the remote bytes. */
    fun etagFile(sha256: String): File {
        requireValidSha256(sha256)
        return File(ensureDir(), "$sha256$ETAG_SUFFIX")
    }

    /**
     * Deletes every `.part`/`.etag` pair. Called when the user explicitly cancels
     * an install: completed layers stay cached (they are immutable and verified),
     * but the in-flight partial is discarded per docs/ROOTFS_SYSTEM.md §2.
     */
    fun discardPartials() {
        if (!dir.exists()) return
        dir.listFiles { file -> file.name.endsWith(PART_SUFFIX) || file.name.endsWith(ETAG_SUFFIX) }
            .orEmpty()
            .forEach { it.delete() }
    }

    /** Bytes occupied by completed layers; for the storage UI and later LRU eviction. */
    fun cachedBytes(): Long {
        if (!dir.exists()) return 0L
        return dir.listFiles { file -> file.name.endsWith(FINAL_SUFFIX) }
            .orEmpty()
            .sumOf { it.length() }
    }

    private fun ensureDir(): File {
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun requireValidSha256(sha256: String) {
        if (!Digests.isSha256Hex(sha256)) {
            throw VmException(
                VmError.DOWNLOAD_CORRUPTED,
                "Layer digest '$sha256' is not a valid sha256 hex string.",
            )
        }
    }

    companion object {
        const val LAYER_DIR = "cache/layers"
        const val FINAL_SUFFIX = ".layer"
        const val PART_SUFFIX = ".layer.part"
        const val ETAG_SUFFIX = ".layer.etag"
    }
}
