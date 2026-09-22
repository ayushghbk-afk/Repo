package com.lenix.installer.extract

import com.lenix.vm.VmError
import com.lenix.vm.VmException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which archive formats a manifest may name, and what this build can actually read
 * (Phase 5, ADR-018). The distinction matters: "not a format we know" is a broken
 * manifest, "known but unreadable" is a build gap the user can wait out.
 */
class LayerCompressionTest {

    @Test
    fun `manifest spellings map onto one format`() {
        mapOf(
            "xz" to LayerCompression.XZ,
            "XZ" to LayerCompression.XZ,
            "lzma2" to LayerCompression.XZ,
            "gz" to LayerCompression.GZIP,
            "gzip" to LayerCompression.GZIP,
            "tgz" to LayerCompression.GZIP,
            "none" to LayerCompression.NONE,
            "tar" to LayerCompression.NONE,
            "plain" to LayerCompression.NONE,
        ).forEach { (declared, expected) ->
            assertEquals(
                "compression '$declared'",
                expected,
                LayerCompression.resolve(declared, "https://example.invalid/layer.tar", "base"),
            )
        }
    }

    @Test
    fun `a missing compression falls back to the archive suffix`() {
        listOf(
            "https://x/y/base.tar.xz" to LayerCompression.XZ,
            "https://x/y/base.tar.gz" to LayerCompression.GZIP,
            "https://x/y/base.tgz" to LayerCompression.GZIP,
            "https://x/y/base.tar" to LayerCompression.NONE,
            "https://x/y/base.tar.zst" to LayerCompression.ZSTD,
        ).forEach { (url, expected) ->
            assertEquals(url, expected, LayerCompression.detectFromName(url))
        }
    }

    @Test
    fun `zstd is known but unreadable until the native engine lands`() {
        // Known means the manifest may name it; unreadable means resolve() refuses to hand
        // it to the extractor, which is a different failure from a typo in the manifest.
        listOf("zstd", "zst", "ZSTD").forEach { declared ->
            assertTrue("'$declared' is a known format", LayerCompression.isKnown(declared))
            val refused = assertThrows(VmException::class.java) {
                LayerCompression.resolve(declared, "https://example.invalid/base.tar.zst", "base")
            }
            assertEquals(VmError.UNSUPPORTED_COMPRESSION, refused.error)
        }
        assertTrue(!LayerCompression.ZSTD.supportedByAppExtractor)
        assertEquals(LayerCompression.ZSTD, LayerCompression.detectFromName("https://x/base.tar.zst"))

        val failure = assertThrows(VmException::class.java) {
            LayerCompression.resolve("zstd", "https://example.invalid/base.tar.zst", "base")
        }

        assertEquals(VmError.UNSUPPORTED_COMPRESSION, failure.error)
        assertTrue(failure.message!!.contains("Phase 6"))
    }

    @Test
    fun `unknown or missing formats are manifest corruption`() {
        listOf("", null).forEach { declared ->
            assertEquals(
                VmError.DOWNLOAD_CORRUPTED,
                assertThrows(VmException::class.java) {
                    LayerCompression.resolve(declared, "https://example.invalid/blob", "base")
                }.error,
            )
        }
        assertEquals(
            VmError.DOWNLOAD_CORRUPTED,
            assertThrows(VmException::class.java) {
                LayerCompression.resolve("rar", "https://example.invalid/blob", "base")
            }.error,
        )
        assertTrue(!LayerCompression.isKnown("rar"))
        assertTrue(!LayerCompression.isKnown(null))
    }
}
