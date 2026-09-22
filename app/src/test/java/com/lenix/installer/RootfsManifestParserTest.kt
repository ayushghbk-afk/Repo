package com.lenix.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manifest schema validation (docs/ROOTFS_SYSTEM.md §3): everything the pipeline will
 * later spend bandwidth, storage and trust on is checked here, and a violation is a
 * readable [IllegalArgumentException] rather than a Jackson stacktrace.
 */
class RootfsManifestParserTest {

    private val parser = RootfsManifestParser()

    /** The document *without* a signature member; [validManifest] adds one. */
    private val manifestBody = """
        {
          "schemaVersion": 1,
          "id": "debian-bookworm-aarch64",
          "distro": "debian",
          "codename": "bookworm",
          "arch": "aarch64",
          "version": "1.0.0",
          "channel": "stable",
          "releasedAt": "2026-08-29T00:00:00Z",
          "compatibility": { "minAndroidSdk": 29, "minRamMb": 2048, "recommendedRamMb": 4096 },
          "desktop": { "default": "openbox", "flavors": ["openbox"] },
          "layers": [
            {
              "id": "base",
              "url": "https://example.invalid/base.tar.zst",
              "sizeBytes": 100,
              "uncompressedBytes": 1000,
              "sha256": "$DIGEST",
              "compression": "zstd"
            }
          ],
          "install": {
            "estimatedFreeGb": 3.0,
            "bootCommand": "/usr/bin/tini -s -- /usr/local/bin/pvm-entry"
          }
        }
    """.trimIndent()

    private fun manifestWith(signature: String?): String = if (signature == null) {
        manifestBody
    } else {
        manifestBody.substringBeforeLast('}') + ", \"signature\": \"" + signature + "\" }"
    }

    private val validManifest: String = manifestWith("ed25519:AAAA")

    @Test
    fun `parses a valid manifest and normalizes its digests`() {
        val withUppercaseDigest = validManifest.replace(DIGEST, DIGEST.uppercase())

        val manifest = parser.parse(withUppercaseDigest)

        assertEquals("debian", manifest.distro)
        assertEquals("aarch64", manifest.arch)
        assertEquals(1, manifest.layers.size)
        assertEquals("base", manifest.layers.first().id)
        assertEquals(DIGEST, manifest.layers.first().sha256)
        assertEquals(29, manifest.compatibility.minAndroidSdk)
        assertEquals(listOf("openbox"), manifest.desktop.flavors)
    }

    @Test
    fun `a missing or blank signature is a trust verdict, not a schema error`() {
        // "unsigned" has to surface as SIGNATURE_FAILED (its own UI copy), so the parser
        // deliberately accepts the document and leaves the verdict to the verifier;
        // RootfsManifestVerifierTest covers the rejection.
        assertEquals("", parser.parse(manifestWith(null)).signature)
        assertEquals("", parser.parse(manifestWith("")).signature)
        assertTrue(parser.parse(manifestWith("   ")).signature.isBlank())
    }

    @Test
    fun `rejects a layer without a usable checksum`() {
        listOf("", "abc123", "g".repeat(64)).forEach { digest ->
            val failure = assertThrows(
                "digest '$digest' must not be accepted",
                IllegalArgumentException::class.java,
            ) { parser.parse(validManifest.replace(DIGEST, digest)) }
            assertTrue(failure.message!!.contains("sha256"))
        }
    }

    @Test
    fun `rejects an http download url`() {
        val insecure = validManifest.replace("https://example.invalid", "http://example.invalid")

        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { parser.parse(insecure) }
                .message!!.contains("insecure"),
        )
    }

    @Test
    fun `rejects sizes that cannot describe a compressed archive`() {
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                parser.parse(validManifest.replace("\"sizeBytes\": 100", "\"sizeBytes\": 0"))
            }.message!!.contains("no size"),
        )
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                parser.parse(validManifest.replace("\"uncompressedBytes\": 1000", "\"uncompressedBytes\": 50"))
            }.message!!.contains("uncompressed bytes"),
        )
    }

    @Test
    fun `rejects duplicate layers, empty layer lists and unknown compressions`() {
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                parser.parse(validManifest.replace("\"layers\": [", "\"layers\": [{ \"id\": \"base\", \"url\": \"https://a/b\", \"sizeBytes\": 1, \"uncompressedBytes\": 2, \"sha256\": \"$DIGEST\" },"))
            }.message!!.contains("twice"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(validManifest.replace(Regex("\"layers\": \\[[^]]*]"), "\"layers\": []"))
        }
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                parser.parse(validManifest.replace("\"compression\": \"zstd\"", "\"compression\": \"rar\""))
            }.message!!.contains("unknown compression"),
        )
    }

    @Test
    fun `rejects a boot command that is blank and a schema this apk cannot read`() {
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                parser.parse(validManifest.replace("\"bootCommand\": \"/usr/bin/tini -s -- /usr/local/bin/pvm-entry\"", "\"bootCommand\": \"\""))
            }.message!!.contains("boot command"),
        )
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                parser.parse(validManifest.replace("\"schemaVersion\": 1", "\"schemaVersion\": 0"))
            }.message!!.contains("schema"),
        )
    }

    private companion object {
        const val DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
