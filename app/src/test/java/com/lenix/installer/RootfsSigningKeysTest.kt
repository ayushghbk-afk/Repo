package com.lenix.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The key and signature wire formats of Phase 4 (ADR-017): a minisign public key file,
 * and a manifest signature that carries the signing key's serial so the app can tell
 * "wrong signature" from "key this build does not trust".
 */
class RootfsSigningKeysTest {

    private fun keyBlob(
        algorithm: String = "Ed",
        keyId: Long = 0x0102030405060708L,
        publicKey: ByteArray = ByteArray(32) { it.toByte() },
    ): String = Base64.getEncoder().encodeToString(
        algorithm.toByteArray(Charsets.ISO_8859_1) + keyIdBytes(keyId) + publicKey,
    )

    private fun keyIdBytes(keyId: Long): ByteArray = MinisignPublicKey.keyIdBytes(keyId)

    @Test
    fun `parses a minisign public key file with its comment`() {
        val text = "untrusted comment: minisign public key 0807060504030201\n" + keyBlob() + "\n"

        val key = MinisignPublicKey.parse(text)

        assertEquals(0x0102030405060708L, key.keyId)
        assertEquals("Ed", key.algorithm)
        assertEquals(32, key.rawPublicKey.size)
        assertEquals("minisign public key 0807060504030201", key.untrustedComment)
        // Re-serializing yields the same base64 body the key file carried.
        assertEquals(keyBlob(), key.encode())
    }

    @Test
    fun `accepts a bare base64 key, the form minisign -P takes`() {
        val key = MinisignPublicKey.parseKey(keyBlob(), null)

        assertEquals(0x0102030405060708L, key.keyId)
        // The serial is the 8-byte little-endian value printed big-endian, as minisign does.
        assertEquals("0102030405060708", key.keyIdHex)
    }

    @Test
    fun `rejects keys that are not ed25519, the wrong size, or not base64`() {
        assertThrows(IllegalArgumentException::class.java) {
            MinisignPublicKey.parseKey(keyBlob(algorithm = "tv"), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MinisignPublicKey.parseKey(Base64.getEncoder().encodeToString(ByteArray(41)), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MinisignPublicKey.parseKey("this is not base64 !!", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MinisignPublicKey.parseAll("untrusted comment: nothing but a comment\n")
        }
    }

    @Test
    fun `a key document can carry a whole rotation`() {
        val text = buildString {
            append("untrusted comment: old key\n").append(keyBlob(keyId = 1L)).append('\n')
            append("\n")
            append("untrusted comment: new key\n").append(keyBlob(keyId = 2L)).append('\n')
        }

        val ring = TrustedKeyRing.parse(text)

        assertEquals(2, ring.size)
        assertNotNull(ring.find(1L))
        assertNotNull(ring.find(2L))
        assertNull(ring.find(3L))
        assertEquals("0x0000000000000001, 0x0000000000000002", ring.describe())
    }

    @Test
    fun `an empty ring is not a way to skip verification`() {
        assertTrue(TrustedKeyRing.EMPTY.isEmpty)
        assertTrue(TrustedKeyRing.EMPTY.keys.isEmpty())
    }

    @Test
    fun `parses the ed25519 manifest signature field`() {
        val field = ManifestSignature(0x0807060504030201L, ByteArray(64) { (it % 7).toByte() }).toField()

        val signature = ManifestSignature.parse(field)

        assertTrue(field.startsWith("ed25519:"))
        assertEquals(0x0807060504030201L, signature.keyId)
        assertEquals(64, signature.signature.size)
        assertEquals("0807060504030201", signature.keyIdHex)
        // Encoding is stable, so a manifest can be re-signed without drift.
        assertEquals(field, signature.toField())
    }

    @Test
    fun `accepts a full minisign signature struct and checks its algorithm`() {
        val field = "ed25519:" + Base64.getEncoder().encodeToString(
            "Ed".toByteArray(Charsets.ISO_8859_1) +
                MinisignPublicKey.keyIdBytes(3L) + ByteArray(64) { (it % 5).toByte() },
        )

        assertEquals(3L, ManifestSignature.parse(field).keyId)

        val bogusAlg = "ed25519:" + Base64.getEncoder().encodeToString(
            "tv".toByteArray(Charsets.ISO_8859_1) +
                MinisignPublicKey.keyIdBytes(3L) + ByteArray(64),
        )
        assertThrows(IllegalArgumentException::class.java) { ManifestSignature.parse(bogusAlg) }
    }

    @Test
    fun `rejects unsigned, empty, malformed and wrong-length signatures`() {
        listOf("unsigned:phase-4", "", "  ", "ed25519:not-base64!!", "base64:" + "A".repeat(96)).forEach { field ->
            assertThrows(
                "signature '$field' must not parse",
                IllegalArgumentException::class.java,
            ) { ManifestSignature.parse(field) }
        }
        val tooShort = "ed25519:" + Base64.getEncoder().encodeToString(ByteArray(64))
        assertThrows(IllegalArgumentException::class.java) { ManifestSignature.parse(tooShort) }
    }
}
