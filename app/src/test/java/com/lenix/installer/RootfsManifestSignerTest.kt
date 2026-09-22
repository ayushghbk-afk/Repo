package com.lenix.installer

import com.lenix.util.Digests
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signing side of the trust chain (ADR-017). This is the code a release pipeline — or
 * `scripts/sign-rootfs-manifest.sh`, which does the same thing with openssl — runs; the
 * app only ever verifies. Testing both halves against each other with *real* Ed25519 keys
 * is what makes "signature verification" more than a flag day.
 */
class RootfsManifestSignerTest {

    private val generated = RootfsManifestSigner.generateKeyPair()
    private val privateKey = generated.first
    private val publicKey = generated.second

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private val manifest = """
        {
          "schemaVersion": 1,
          "id": "debian-bookworm-aarch64",
          "distro": "debian",
          "codename": "bookworm",
          "arch": "aarch64",
          "version": "0.1.0",
          "channel": "stable",
          "releasedAt": "2026-08-30T00:00:00Z",
          "layers": [
            { "id": "base", "url": "https://example.invalid/base.tar.xz", "sizeBytes": 10,
              "uncompressedBytes": 40, "sha256": "${"0".repeat(64)}", "compression": "xz" }
          ],
          "install": { "estimatedFreeGb": 0.3, "bootCommand": "/bin/bash" },
          "signature": "unsigned:placeholder"
        }
    """.trimIndent()

    @Test
    fun `a signature the signer produced verifies against the same key`() {
        val signed = RootfsManifestSigner.sign(manifest, privateKey, publicKey.keyId)

        val verified = RootfsManifestVerifier(keyRing = TrustedKeyRing(listOf(publicKey))).verify(signed)

        assertEquals("debian-bookworm-aarch64", verified.manifest.id)
        assertNotEquals("unsigned:placeholder", Regex("\"signature\": \"([^\"]*)\"").find(signed)!!.groupValues[1])
    }

    @Test
    fun `signing only rewrites the signature member`() {
        val signed = RootfsManifestSigner.sign(manifest, privateKey, publicKey.keyId)

        // Everything before the member — and the document's shape — is untouched, and the
        // payload the signature covers is unchanged by definition.
        assertEquals(manifest.substringBefore("\"signature\""), signed.substringBefore("\"signature\""))
        assertEquals(manifest.lines().size, signed.lines().size)
        assertEquals(
            RootfsManifestCanonicalizer.canonicalText(manifest),
            RootfsManifestCanonicalizer.canonicalText(signed),
        )
    }

    @Test
    fun `the key id is derived from the public key the way the scripts derive it`() {
        val rawPublic = publicKey.rawPublicKey

        assertEquals(publicKey.keyId, RootfsManifestSigner.keyIdOf(rawPublic))
        assertArrayEquals(
            "the key id is the first 8 bytes of sha256(raw public key)",
            hexToBytes(Digests.sha256Hex(rawPublic).take(16)),
            MinisignPublicKey.keyIdBytes(publicKey.keyId),
        )
        assertEquals("%016X".format(publicKey.keyId), publicKey.keyIdHex)
    }

    @Test
    fun `a signature field cannot be smuggled into the payload it signs`() {
        val signed = RootfsManifestSigner.sign(manifest, privateKey, publicKey.keyId)
        val field = Regex("\"signature\": \"([^\"]*)\"").find(signed)!!.groupValues[1]

        // The canonical payload never contains the signature member…
        assertTrue(!RootfsManifestCanonicalizer.canonicalText(signed).contains("signature"))
        // …so re-signing the same document is stable.
        assertEquals(
            field,
            RootfsManifestSigner.signatureField(signed, privateKey, publicKey.keyId),
        )
    }

    @Test
    fun `a manifest without a signature member cannot be signed in place`() {
        val noMember = manifest.replace(Regex(",\\s*\"signature\": \"unsigned:placeholder\""), "")

        assertThrows(IllegalArgumentException::class.java) {
            RootfsManifestSigner.sign(noMember, privateKey, publicKey.keyId)
        }
    }
}
