package com.lenix.installer

import com.lenix.vm.VmError
import com.lenix.vm.VmException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The checksum half of Phase 4: the digest is what turns "we downloaded something" into
 * "we downloaded the layer the signed manifest pins", so both the size and the hash of a
 * cached layer are re-checked before a single byte is trusted by the extractor.
 */
class RootfsVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val verifier = RootfsVerifier()

    private fun layer(bytes: ByteArray, id: String = "base") = RootfsManifest.Layer(
        id = id,
        url = "https://example.invalid/$id.tar.xz",
        sizeBytes = bytes.size.toLong(),
        uncompressedBytes = bytes.size.toLong() * 3,
        sha256 = sha256(bytes),
        compression = "xz",
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun file(content: ByteArray, name: String = "layer"): File =
        tmp.newFile(name).apply { writeBytes(content) }

    @Test
    fun `a matching layer verifies and reports its size`() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }

        assertEquals(bytes.size.toLong(), verifier.verifyLayer(file(bytes), layer(bytes)))
    }

    @Test
    fun `digests are compared case-insensitively and whitespace tolerant`() {
        val bytes = "hello".toByteArray()
        val upper = sha256(bytes).uppercase()
        val mixed = RootfsManifest.Layer(
            id = "base",
            url = "https://example.invalid/base.tar.xz",
            sizeBytes = bytes.size.toLong(),
            uncompressedBytes = bytes.size.toLong() * 2,
            sha256 = "  $upper\n",
        )

        assertEquals(bytes.size.toLong(), verifier.verifyLayer(file(bytes), mixed))
    }

    @Test
    fun `a wrong digest fails as CHECKSUM_FAILED`() {
        val bytes = "hello".toByteArray()
        // Same length as the bytes on disk, so the size gate lets it through and the hash is
        // what refuses it — the two failures say different things to the user, so the fixture
        // has to isolate one of them.
        val wrong = layer("hellp".toByteArray())

        val failure = assertThrows(VmException::class.java) {
            verifier.verifyLayer(file(bytes), wrong)
        }

        assertEquals(VmError.CHECKSUM_FAILED, failure.error)
        assertTrue(failure.message!!.contains("Expected"))
        assertTrue(failure.message!!.contains(sha256("hellp".toByteArray())))
    }

    @Test
    fun `a wrong size fails as corruption before hashing`() {
        val bytes = "hello".toByteArray()
        val padded = RootfsManifest.Layer(
            id = "base",
            url = "https://example.invalid/base.tar.xz",
            sizeBytes = bytes.size.toLong() + 1,
            uncompressedBytes = bytes.size.toLong() * 2,
            sha256 = sha256(bytes),
        )

        val failure = assertThrows(VmException::class.java) { verifier.verifyLayer(file(bytes), padded) }

        assertEquals(VmError.DOWNLOAD_CORRUPTED, failure.error)
        assertTrue(failure.message!!.contains("bytes on disk"))
    }

    @Test
    fun `a missing cache entry fails as corruption`() {
        val missing = File(tmp.newFolder(), "never-downloaded.layer")
        val bytes = "hello".toByteArray()

        val failure = assertThrows(VmException::class.java) { verifier.verifyLayer(missing, layer(bytes)) }

        assertEquals(VmError.DOWNLOAD_CORRUPTED, failure.error)
    }

    @Test
    fun `only a real sha256 digest is usable`() {
        assertTrue(RootfsVerifier.isUsableDigest(sha256("x".toByteArray())))
        assertTrue(RootfsVerifier.isUsableDigest(" " + "AB".repeat(32) + " "))
        assertTrue(!RootfsVerifier.isUsableDigest(""))
        assertTrue(!RootfsVerifier.isUsableDigest("abc123"))
        assertTrue(!RootfsVerifier.isUsableDigest("z".repeat(64)))
    }
}
