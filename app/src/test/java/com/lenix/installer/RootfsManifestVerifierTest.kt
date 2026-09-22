package com.lenix.installer

import com.lenix.vm.VmError
import com.lenix.vm.VmException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.PrivateKey
import java.util.Base64

/**
 * The Phase 4 trust gate: a manifest is only believed when an embedded key signed
 * *this* content. Everything here is real Ed25519 — no fake verifier is injected, so the
 * test fails if the crypto or the payload format ever degenerates.
 */
class RootfsManifestVerifierTest {

    private val generated = RootfsManifestSigner.generateKeyPair()
    private val privateKey: PrivateKey = generated.first
    private val publicKey = generated.second
    private val ring = TrustedKeyRing(listOf(publicKey))

    private fun sign(manifestJson: String): String =
        RootfsManifestSigner.sign(manifestJson, privateKey, publicKey.keyId)

    private fun verifier(keys: TrustedKeyRing = ring) = RootfsManifestVerifier(keyRing = keys)

    @Test
    fun `a manifest signed by a trusted key is accepted`() {
        val json = sign(manifestJson())

        val verified = verifier().verify(json)

        assertEquals("debian-bookworm-aarch64", verified.manifest.id)
        assertEquals(publicKey.keyIdHex, verified.keyIdHex)
        assertEquals(publicKey.untrustedComment, verified.signer)
        assertEquals(64, verified.payloadSha256.length)
    }

    @Test
    fun `changing a pinned layer digest invalidates the signature`() {
        val signed = sign(manifestJson())
        // Swap the digest the app would otherwise trust with one of the attacker's
        // choosing — the signature covers it, so this must not verify.
        val tampered = signed.replace("0".repeat(64), "f".repeat(64))
        assertTrue(tampered != signed)

        val failure = assertThrows(VmException::class.java) { verifier().verify(tampered) }

        assertEquals(VmError.SIGNATURE_FAILED, failure.error)
    }

    @Test
    fun `changing the download url invalidates the signature`() {
        val signed = sign(manifestJson())
        val hijacked = signed.replace(
            "https://example.invalid/base.tar.xz",
            "https://evil.example.com/base.tar.xz",
        )
        assertTrue(hijacked != signed)

        assertEquals(
            VmError.SIGNATURE_FAILED,
            assertThrows(VmException::class.java) { verifier().verify(hijacked) }.error,
        )
    }

    @Test
    fun `a manifest signed by an unknown key is rejected with both key ids`() {
        val other = RootfsManifestSigner.generateKeyPair()
        val signedByStranger = RootfsManifestSigner.sign(manifestJson(), other.first, other.second.keyId)

        val failure = assertThrows(VmException::class.java) { verifier().verify(signedByStranger) }

        assertEquals(VmError.SIGNATURE_FAILED, failure.error)
        assertTrue(failure.message!!.contains(other.second.keyIdHex))
        assertTrue(failure.message!!.contains(publicKey.keyIdHex))
    }

    @Test
    fun `an unsigned or placeholder manifest is never trusted`() {
        listOf("unsigned:phase-4", "", "ed25519:" + "A".repeat(88)).forEach { field ->
            val failure = assertThrows(
                "manifest with signature '$field' must be rejected",
                VmException::class.java,
            ) { verifier().verify(manifestJson(signature = field)) }
            assertEquals(VmError.SIGNATURE_FAILED, failure.error)
        }
    }

    @Test
    fun `an empty key ring rejects everything instead of skipping verification`() {
        val signed = sign(manifestJson())

        val failure = assertThrows(VmException::class.java) {
            verifier(TrustedKeyRing.EMPTY).verify(signed)
        }

        assertEquals(VmError.SIGNATURE_FAILED, failure.error)
        assertTrue(failure.message!!.contains("no trusted RootFS signing keys"))
    }

    @Test
    fun `a schema-invalid manifest fails as corruption, not as a bad signature`() {
        val broken = manifestJson(sizeBytes = 0)

        val failure = assertThrows(VmException::class.java) { verifier().verify(sign(broken)) }

        assertEquals(VmError.DOWNLOAD_CORRUPTED, failure.error)
    }

    @Test
    fun `the signature survives re-formatting of the same content`() {
        val signed = sign(manifestJson())
        val reformatted = signed.replace(",", ",\n\n  ").replace("\":", "\" :")

        assertEquals(
            RootfsManifestCanonicalizer.canonicalText(signed),
            RootfsManifestCanonicalizer.canonicalText(reformatted),
        )
        assertEquals("debian-bookworm-aarch64", verifier().verify(reformatted).manifest.id)
    }

    @Test
    fun `a signature copied from another manifest does not verify`() {
        val signatureOfOther = RootfsManifestSigner
            .signatureField(manifestJson(id = "other-build"), privateKey, publicKey.keyId)
        val transplanted = manifestJson(signature = signatureOfOther)

        assertEquals(
            VmError.SIGNATURE_FAILED,
            assertThrows(VmException::class.java) { verifier().verify(transplanted) }.error,
        )
    }

    @Test
    fun `a minisign-style signature struct verifies the same`() {
        val manifest = manifestJson()
        val signature = RootfsManifestSigner.signatureFor(manifest, privateKey, publicKey.keyId)
        // What `minisign -S -l -m <canonical payload>` writes as line 2 of the .minisig:
        // "Ed" || key-id || signature, base64'd — accepted verbatim.
        val structField = "ed25519:" + Base64.getEncoder().encodeToString(
            "Ed".toByteArray(Charsets.ISO_8859_1) +
                MinisignPublicKey.keyIdBytes(publicKey.keyId) + signature.signature,
        )

        assertEquals(
            signature.signature.toList(),
            ManifestSignature.parse(structField).signature.toList(),
        )
        assertTrue(verifier().verify(manifest.replace(
            Regex("\"signature\"\\s*:\\s*\"[^\"]*\""),
            "\"signature\": \"$structField\"",
        )).payloadSha256.isNotEmpty())
    }

    private companion object {
        fun manifestJson(
            id: String = "debian-bookworm-aarch64",
            url: String = "https://example.invalid/base.tar.xz",
            sha256: String = "0".repeat(64),
            sizeBytes: Long = 42_894_344,
            uncompressedBytes: Long = 150_000_000,
            arch: String = "aarch64",
            signature: String = "unsigned:placeholder",
        ): String = """
            {
              "schemaVersion": 1,
              "id": "$id",
              "distro": "debian",
              "codename": "bookworm",
              "arch": "$arch",
              "version": "0.1.0",
              "channel": "stable",
              "releasedAt": "2026-08-30T00:00:00Z",
              "layers": [
                {
                  "id": "base",
                  "url": "$url",
                  "sizeBytes": $sizeBytes,
                  "uncompressedBytes": $uncompressedBytes,
                  "sha256": "$sha256",
                  "compression": "$compressionDefault"
                }
              ],
              "install": { "estimatedFreeGb": 0.3, "bootCommand": "/bin/bash" },
              "signature": "$signature"
            }
        """.trimIndent()

        private const val compressionDefault = "xz"
    }
}
