package com.lenix.installer

import com.lenix.installer.extract.LayerCompression
import com.lenix.util.Digests
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The APK's own trust chain, checked on every CI run (docs/ROOTFS_SYSTEM.md §1+§7).
 *
 * Phase 4 only means something if the manifest that ships in `assets/` verifies against
 * the key that ships next to it: a re-pinned layer without re-signing, an edited digest,
 * a rotated key without a new signature, or the two canonicalizers (Kotlin and
 * `scripts/canonical-json.py`) drifting apart all fail here instead of breaking every
 * install on every device.
 */
class BundledRootfsManifestTrustTest {

    private val manifestFile: File by lazy { repoFile(MANIFEST_ASSET) }
    private val keyFile: File by lazy { repoFile(KEYS_ASSET) }

    @Test
    fun `the bundled manifest verifies against the bundled key`() {
        val manifestJson = manifestFile.readText()
        val ring = TrustedKeyRing.parse(keyFile.readText())

        val verified = RootfsManifestVerifier(keyRing = ring).verify(manifestJson)

        assertEquals("debian-bookworm-aarch64", verified.manifest.id)
        assertEquals("aarch64", verified.manifest.arch)
        assertEquals(1, verified.manifest.layers.size)
        assertEquals(64, verified.payloadSha256.length)
        assertNotNull(verified.signer)
    }

    @Test
    fun `the pinned layer is the upstream digest, over https, in a readable format`() {
        val manifest = RootfsManifestVerifier(keyRing = TrustedKeyRing.parse(keyFile.readText()))
            .verify(manifestFile.readText())
            .manifest
        val layer = manifest.layers.single()

        assertTrue(
            "the v0.1 layer is pinned to termux/proot-distro's release host",
            layer.url.startsWith("https://github.com/termux/proot-distro/releases/download/"),
        )
        assertEquals(
            "Debian bookworm arm64 rootfs digest published by proot-distro v4.7.0",
            "4baa32280cc70b67e2c650777c1d974349f0cdf23afaabc305ad3bc6182b8df8",
            layer.sha256,
        )
        // The app must be able to extract what it downloads, pre-native-engine.
        val compression = LayerCompression.resolve(layer.compression, layer.url, layer.id)
        assertTrue(compression.supportedByAppExtractor)
    }

    @Test
    fun `editing one byte of the shipped manifest breaks its signature`() {
        val tampered = manifestFile.readText()
            .replace("\"sizeBytes\": 42894344", "\"sizeBytes\": 42894345")
        assertTrue(tampered != manifestFile.readText())

        val failure = assertThrows(VmException::class.java) {
            RootfsManifestVerifier(keyRing = TrustedKeyRing.parse(keyFile.readText()))
                .verify(tampered)
        }
        assertEquals(VmError.SIGNATURE_FAILED, failure.error)
    }

    /**
     * Guards the Kotlin/Python canonicalizer pair with a fixed digest: whoever changes
     * either implementation has to change this constant, and CI explains why that means
     * re-signing every published manifest.
     */
    @Test
    fun `the canonical payload of the bundled manifest is byte-stable`() {
        val payload = RootfsManifestCanonicalizer.canonicalBytes(manifestFile.readText())

        assertEquals(EXPECTED_PAYLOAD_SHA256, Digests.sha256Hex(payload))
    }

    /** Gradle runs module unit tests with the module directory as the working dir. */
    private fun repoFile(assetPath: String): File {
        val candidates = listOf(
            File("src/main/assets/$assetPath"),
            File("app/src/main/assets/$assetPath"),
            File(System.getProperty("lenix.moduleDir") ?: ".").let { File(it, "src/main/assets/$assetPath") },
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw IllegalStateException("cannot find the bundled asset $assetPath in ${File(".").absolutePath}")
    }

    private companion object {
        const val MANIFEST_ASSET = "rootfs/debian-bookworm-aarch64.json"
        const val KEYS_ASSET = RootfsCatalog.SIGNING_KEYS_ASSET

        /** `python3 scripts/canonical-json.py <manifest> | sha256sum`. */
        const val EXPECTED_PAYLOAD_SHA256 =
            "9a1b65e14fc1b5519b3558dfe2a7959ed7ba738b16e6b500724c6a9611326c52"
    }
}
