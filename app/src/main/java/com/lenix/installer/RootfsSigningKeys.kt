package com.lenix.installer

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * An Ed25519 signing key the app trusts to sign RootFS manifests.
 *
 * Keys are stored in minisign's own public-key format, so a release key can be created
 * and rotated with `minisign -G` (or `scripts/gen-rootfs-signing-key.sh`, which derives
 * the key id from the public key instead of picking one at random) and the resulting
 * `minisign.pub` file dropped into `assets/rootfs/keys/` unchanged:
 *
 * ```
 * untrusted comment: minisign public key 560F5D527386EB84
 * RWSE64ZzUl0PVknHBbZNyoVJXJemHXkAjRDPvrxrCGw2F5fugSDCLG51
 * ```
 *
 * The base64 body is `algorithm(2) || keyId(8) || publicKey(32)`. Only the `Ed`/`ED`
 * algorithms (Ed25519) are accepted; `tv` (Argon2-wrapped, signify-style encrypted keys)
 * is not a public key and is rejected.
 */
class MinisignPublicKey(
    /** minisign's key serial, decoded little-endian — how key ids are printed. */
    val keyId: Long,
    /** The two raw algorithm bytes from the key file (`Ed` or `ED`). */
    val algorithm: String,
    /** The 32-byte Ed25519 public key. */
    val rawPublicKey: ByteArray,
    val untrustedComment: String? = null,
) {
    /** The key id as minisign prints it: 16 uppercase hex digits of the LE serial. */
    val keyIdHex: String get() = keyIdToHex(keyId)

    /** `algorithm || keyId || publicKey`, i.e. the key file's base64 body. */
    fun encode(): String = Base64.getEncoder().encodeToString(
        algorithm.toByteArray(Charsets.ISO_8859_1) + keyIdBytes(keyId) + rawPublicKey,
    )

    private val verifiedKey: PublicKey by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ed25519PublicKey(rawPublicKey)
    }

    /**
     * Verifies an Ed25519 signature over [message].
     *
     * @throws GeneralSecurityException when the platform has no Ed25519 provider (a
     *   stripped JCA on some custom ROMs) — [RootfsManifestVerifier] turns that into
     *   [com.lenix.vm.VmError.SIGNATURE_FAILED] instead of letting it escape.
     */
    fun verify(message: ByteArray, signature: ByteArray): Boolean {
        val verifier = Signature.getInstance(ALGORITHM_NAME)
        verifier.initVerify(verifiedKey)
        verifier.update(message)
        return verifier.verify(signature)
    }

    companion object {
        const val COMMENT_PREFIX = "untrusted comment:"
        const val KEY_BYTES = 42
        const val PUBLIC_KEY_BYTES = 32
        const val KEY_ID_BYTES = 8
        const val ALGORITHM_NAME = "Ed25519"

        /** The DER `SubjectPublicKeyInfo` prefix that wraps a raw Ed25519 key. */
        private val PUBLIC_KEY_INFO_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )

        private val SUPPORTED_ALGORITHMS = setOf("Ed", "ED")

        /** Parses a key given as `base64(algorithm || keyId || publicKey)`. */
        fun parseKey(base64Body: String, untrustedComment: String? = null): MinisignPublicKey {
            val body = decodeBase64(base64Body, "public key")
            require(body.size == KEY_BYTES) {
                "A minisign public key is $KEY_BYTES bytes; this one is ${body.size}."
            }
            val algorithm = String(body, 0, 2, Charsets.ISO_8859_1)
            require(algorithm in SUPPORTED_ALGORITHMS) {
                "Unsupported minisign key algorithm '$algorithm'; only Ed25519 (\"Ed\") is trusted."
            }
            return MinisignPublicKey(
                keyId = littleEndianLong(body, 2),
                algorithm = algorithm,
                rawPublicKey = body.copyOfRange(2 + KEY_ID_BYTES, KEY_BYTES),
                untrustedComment = untrustedComment,
            )
        }

        /**
         * Parses a whole minisign public key file — an optional
         * `untrusted comment:` line followed by the base64 key — and accepts several
         * keys concatenated in one document, which is how a rotation is shipped.
         */
        fun parse(text: String): MinisignPublicKey = parseAll(text).singleOrNull()
            ?: throw IllegalArgumentException(
                if (text.isBlank()) "No minisign public key found." else "Expected exactly one public key.",
            )

        /** Every key in [text], in file order; blank input and comments alone fail. */
        fun parseAll(text: String): List<MinisignPublicKey> {
            val keys = ArrayList<MinisignPublicKey>()
            var comment: String? = null
            for (rawLine in text.lines()) {
                val line = rawLine.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith(COMMENT_PREFIX) ->
                        comment = line.substring(COMMENT_PREFIX.length).trim()
                    line.startsWith("trusted comment:") -> Unit
                    else -> {
                        keys += parseKey(line, comment)
                        comment = null
                    }
                }
            }
            require(keys.isNotEmpty()) { "No minisign public key found." }
            return keys
        }

        fun keyIdToHex(keyId: Long): String = "%016X".format(keyId)

        fun keyIdBytes(keyId: Long): ByteArray =
            ByteArray(KEY_ID_BYTES) { index -> (keyId shr (8 * index) and 0xFF).toByte() }

        /** Wraps a raw Ed25519 key in the DER encoding the JCA expects. */
        private fun ed25519PublicKey(rawKey: ByteArray): PublicKey {
            require(rawKey.size == PUBLIC_KEY_BYTES) {
                "An Ed25519 public key is $PUBLIC_KEY_BYTES bytes; this one is ${rawKey.size}."
            }
            return KeyFactory.getInstance(ALGORITHM_NAME)
                .generatePublic(X509EncodedKeySpec(PUBLIC_KEY_INFO_PREFIX + rawKey))
        }
    }
}

/**
 * A manifest signature: `ed25519:` + base64(`keyId(8) || signature(64)`).
 *
 * The payload is the manifest's Lenix Canonical JSON (see [RootfsManifestCanonicalizer]),
 * i.e. the document without its `signature` member — never the file as delivered.
 *
 * For compatibility with `minisign -S -l` (raw, non-pre-hashed), a full 74-byte minisign
 * signature struct (`"Ed" || keyId || signature`) is accepted as well and the algorithm
 * prefix is checked and dropped.
 */
class ManifestSignature(
    val keyId: Long,
    val signature: ByteArray,
) {
    val keyIdHex: String get() = MinisignPublicKey.keyIdToHex(keyId)

    /** The exact text that goes into the manifest's `signature` member. */
    fun toField(): String = ManifestSignature.PREFIX + Base64.getEncoder()
        .encodeToString(MinisignPublicKey.keyIdBytes(keyId) + signature)

    companion object {
        const val PREFIX = "ed25519:"
        const val SIGNATURE_BYTES = 64
        const val PAYLOAD_BYTES = MinisignPublicKey.KEY_ID_BYTES + SIGNATURE_BYTES
        const val MINISIGN_STRUCT_BYTES = 2 + PAYLOAD_BYTES

        /**
         * Parses a manifest `signature` value.
         *
         * @throws IllegalArgumentException with a message safe to show the user, for a
         *   missing, unknown-format or wrong-length signature (including the
         *   `unsigned:…` placeholder used by early development manifests).
         */
        fun parse(field: String): ManifestSignature {
            val trimmed = field.trim()
            require(trimmed.isNotEmpty()) { "The manifest carries no signature." }
            require(trimmed.startsWith(PREFIX)) {
                "Manifest signature '$trimmed' is not an $PREFIX Ed25519 signature; " +
                    "unsigned or foreign-format manifests are never trusted."
            }
            val body = decodeBase64(trimmed.substring(PREFIX.length), "signature")
            val (keyIdBytes, signatureBytes) = when (body.size) {
                PAYLOAD_BYTES -> body.copyOfRange(0, MinisignPublicKey.KEY_ID_BYTES) to
                    body.copyOfRange(MinisignPublicKey.KEY_ID_BYTES, body.size)
                MINISIGN_STRUCT_BYTES -> {
                    val algorithm = String(body, 0, 2, Charsets.ISO_8859_1)
                    require(algorithm == "Ed" || algorithm == "ED") {
                        "minisign signature algorithm '$algorithm' is not Ed25519."
                    }
                    body.copyOfRange(2, 2 + MinisignPublicKey.KEY_ID_BYTES) to
                        body.copyOfRange(2 + MinisignPublicKey.KEY_ID_BYTES, body.size)
                }
                else -> throw IllegalArgumentException(
                    "An ed25519 manifest signature is $PAYLOAD_BYTES bytes " +
                        "(or $MINISIGN_STRUCT_BYTES as a minisign struct); this one is ${body.size}.",
                )
            }
            return ManifestSignature(
                keyId = littleEndianLong(keyIdBytes, 0),
                signature = signatureBytes,
            )
        }
    }
}

/**
 * The set of public keys this build trusts to sign RootFS manifests
 * (the `*.pub` files under `assets/rootfs/keys/`, bundled with the APK).
 *
 * An empty ring is not a "skip verification" switch — it rejects every manifest, which
 * is the right failure for a build whose trust anchors were stripped.
 */
class TrustedKeyRing(val keys: List<MinisignPublicKey>) {

    val isEmpty: Boolean get() = keys.isEmpty()

    val size: Int get() = keys.size

    /** The key with [keyId], or null when this build does not trust it. */
    fun find(keyId: Long): MinisignPublicKey? = keys.firstOrNull { it.keyId == keyId }

    /** Key ids this build trusts, for error messages ("… but 560F… was used"). */
    fun describe(): String = keys.joinToString(", ") { "0x${it.keyIdHex}" }

    companion object {
        val EMPTY = TrustedKeyRing(emptyList())

        /** Parses one or more minisign public key documents into a ring. */
        fun parse(keyDocuments: String): TrustedKeyRing =
            TrustedKeyRing(MinisignPublicKey.parseAll(keyDocuments))

        fun of(vararg keyDocuments: String): TrustedKeyRing =
            TrustedKeyRing(keyDocuments.flatMap { MinisignPublicKey.parseAll(it) })
    }
}

internal fun decodeBase64(value: String, what: String): ByteArray = try {
    // The strict decoder: a MIME decoder silently drops characters outside the
    // alphabet, which would turn a corrupted signature into a wrong-but-decodable one.
    Base64.getDecoder().decode(value.filterNot { it.isWhitespace() })
} catch (e: IllegalArgumentException) {
    throw IllegalArgumentException("The manifest $what is not valid base64: ${e.message}", e)
}

internal fun littleEndianLong(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (index in 7 downTo 0) {
        value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
    }
    return value
}
