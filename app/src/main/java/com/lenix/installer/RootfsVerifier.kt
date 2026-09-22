package com.lenix.installer

import com.lenix.util.Digests
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import java.io.File

/**
 * Verifies RootFS bytes against the signed manifest before they are trusted.
 *
 * Two independent gates, both required (docs/ROOTFS_SYSTEM.md §2 steps [2] and [3]):
 *  * the downloader checks the digest of the bytes it streamed before a `.part` is
 *    promoted into the content-addressed cache;
 *  * the installer re-hashes every cached layer it is about to extract, because a cache
 *    file can be edited, truncated or evicted between those two moments (or be written
 *    by a *different* build with a different manifest).
 *
 * The manifest itself is checked before any of this, by [RootfsManifestVerifier]; the
 * layer digests are only as trustworthy as that signature.
 */
class RootfsVerifier {

    /**
     * Confirms [file] really is the layer the manifest describes: exact size, exact
     * SHA-256.
     *
     * @return the verified byte count.
     * @throws VmException with [VmError.DOWNLOAD_CORRUPTED] for a missing or wrong-sized
     *   file and [VmError.CHECKSUM_FAILED] when the bytes hash to something else.
     */
    fun verifyLayer(file: File, layer: RootfsManifest.Layer): Long {
        if (!file.isFile) {
            throw VmException(
                VmError.DOWNLOAD_CORRUPTED,
                "Layer ${layer.id} is missing from the cache (${file.name}).",
            )
        }
        val actual = file.length()
        if (actual != layer.sizeBytes) {
            throw VmException(
                VmError.DOWNLOAD_CORRUPTED,
                "Layer ${layer.id} is $actual bytes on disk; the signed manifest says " +
                    "${layer.sizeBytes}.",
            )
        }
        verifyFile(file, layer.sha256)
        return actual
    }

    /**
     * Confirms [file] hashes to [expectedSha256] (case-insensitive, whitespace tolerant).
     *
     * @throws VmException with [VmError.CHECKSUM_FAILED] on any mismatch.
     */
    fun verifyFile(file: File, expectedSha256: String) {
        val actualHex = sha256Hex(file)
        val expected = expectedSha256.trim()
        if (!actualHex.equals(expected, ignoreCase = true)) {
            throw VmException(
                VmError.CHECKSUM_FAILED,
                "Expected $expected but computed $actualHex for ${file.name}.",
            )
        }
    }

    /** The hex digest a manifest pins — validated here so a bad manifest fails cleanly. */
    fun sha256Hex(file: File): String {
        if (!file.isFile) {
            throw VmException(VmError.DOWNLOAD_CORRUPTED, "No such file to verify: ${file.name}.")
        }
        return Digests.sha256Hex(file)
    }

    companion object {
        /** True when [value] can be pinned as a layer digest at all. */
        fun isUsableDigest(value: String): Boolean = Digests.isSha256Hex(value.trim())
    }
}
