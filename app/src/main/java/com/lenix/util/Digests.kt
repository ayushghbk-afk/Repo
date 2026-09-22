package com.lenix.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Streaming SHA-256 helpers shared by the layer cache, the downloader, the verifier
 * and the manifest signer.
 *
 * One implementation on purpose: the digest that names a cache file, the digest a
 * download is checked against and the digest a manifest pins must be computed the
 * same way or the whole trust chain is a lie.
 */
object Digests {

    /** Length of a lowercase/uppercase hex-encoded SHA-256 digest. */
    const val SHA256_HEX_LENGTH = 64

    private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")

    /** True when [value] is exactly a hex-encoded SHA-256 digest (any case). */
    fun isSha256Hex(value: String): Boolean = SHA256_REGEX.matches(value)

    /**
     * Strips surrounding whitespace and case differences, so a digest can be compared
     * safely with [equals] and used as a file name.
     *
     * @throws IllegalArgumentException when [value] is not a SHA-256 digest.
     */
    fun normalizeSha256(value: String): String {
        val trimmed = value.trim()
        require(isSha256Hex(trimmed)) { "'$trimmed' is not a sha256 hex string." }
        return trimmed.lowercase()
    }

    /** SHA-256 of [file], streamed — RootFS layers are far too big to buffer. */
    fun sha256Hex(file: File): String =
        file.inputStream().buffered(STREAM_BUFFER_BYTES).use { sha256Hex(it) }

    /** SHA-256 of everything left in [input], streamed. */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

    /** Raw SHA-256 of [bytes] — also how minisign key ids are derived. */
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance(ALGORITHM).digest(bytes)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val ALGORITHM = "SHA-256"
    private const val STREAM_BUFFER_BYTES = 64 * 1024
}
