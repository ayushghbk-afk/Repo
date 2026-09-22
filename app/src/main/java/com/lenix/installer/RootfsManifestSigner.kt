package com.lenix.installer

import com.lenix.util.Digests
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Creates manifest signatures — the release-side half of [RootfsManifestVerifier].
 *
 * The app never signs anything; this exists so the signing path is the *same* code the
 * verifier reads (one canonicalizer, one encoding), which is what keeps
 * `scripts/sign-rootfs-manifest.sh` honest: `RootfsManifestSignerTest` round-trips
 * through the real Ed25519 primitive, and `BundledRootfsManifestTrustTest` proves the
 * manifest shipped in `assets/` — produced by the shell script — verifies against the
 * key shipped next to it.
 *
 * Payload rule (docs/ROOTFS_SYSTEM.md §3): Ed25519 over
 * [RootfsManifestCanonicalizer.canonicalBytes], i.e. the manifest without its
 * `signature` member, canonicalized. `minisign -S -l -m <payload>` produces a
 * signature in exactly the format [ManifestSignature] parses.
 */
object RootfsManifestSigner {

    /** Signs [manifestJson] and returns the value for its `signature` member. */
    fun signatureFor(manifestJson: String, privateKey: PrivateKey, keyId: Long): ManifestSignature {
        val signer = Signature.getInstance(MinisignPublicKey.ALGORITHM_NAME)
        signer.initSign(privateKey)
        signer.update(RootfsManifestCanonicalizer.canonicalBytes(manifestJson))
        return ManifestSignature(keyId, signer.sign())
    }

    /** [signatureFor] rendered as the `ed25519:…` field text. */
    fun signatureField(manifestJson: String, privateKey: PrivateKey, keyId: Long): String =
        signatureFor(manifestJson, privateKey, keyId).toField()

    /**
     * Returns [manifestJson] with its `signature` member replaced by a fresh signature.
     *
     * Only the signature value changes — the canonical payload ignores that member, so
     * the injected text can never invalidate itself.
     */
    fun sign(manifestJson: String, privateKey: PrivateKey, keyId: Long): String {
        val field = signatureField(manifestJson, privateKey, keyId)
        val matcher = SIGNATURE_MEMBER_REGEX.find(manifestJson)
        require(matcher != null) { "The manifest has no \"signature\" member to write into." }
        val updated = manifestJson.replaceRange(
            matcher.range,
            matcher.groupValues[1] + field + matcher.groupValues[3],
        )
        // Belt and braces: the payload must be identical before and after injection.
        check(
            RootfsManifestCanonicalizer.canonicalBytes(updated).contentEquals(
                RootfsManifestCanonicalizer.canonicalBytes(manifestJson),
            ),
        ) { "Signing changed the canonical payload; refusing to write the manifest." }
        return updated
    }

    /** minisign's key serial for [rawPublicKey]: the first 8 bytes of its SHA-256, LE. */
    fun keyIdOf(rawPublicKey: ByteArray): Long = littleEndianLong(Digests.sha256(rawPublicKey), 0)

    /** The 32 raw Ed25519 bytes inside a JCA public key's DER encoding. */
    fun rawPublicKeyOf(publicKey: PublicKey): ByteArray = publicKey.encoded.takeLast(32).toByteArray()

    /** A fresh Ed25519 pair, plus the [keyIdOf] serial of the public half. */
    fun generateKeyPair(): Pair<PrivateKey, MinisignPublicKey> {
        val generator = KeyPairGenerator.getInstance(MinisignPublicKey.ALGORITHM_NAME)
        val pair = generator.generateKeyPair()
        val rawPublic = rawPublicKeyOf(pair.public)
        val keyId = keyIdOf(rawPublic)
        return pair.private to MinisignPublicKey(
            keyId = keyId,
            algorithm = "Ed",
            rawPublicKey = rawPublic,
            untrustedComment = "minisign public key ${MinisignPublicKey.keyIdToHex(keyId)}",
        )
    }

    /** Reads a PKCS#8 private key, PEM (`-----BEGIN PRIVATE KEY-----`) or DER text. */
    fun privateKeyFromPkcs8(encoded: String): PrivateKey {
        val body = encoded
            .replace("-----[A-Z ]*-----".toRegex(), "")
            .filterNot { it.isWhitespace() }
        val der = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance(MinisignPublicKey.ALGORITHM_NAME)
            .generatePrivate(PKCS8EncodedKeySpec(der))
    }

    /** Matches a manifest's `"signature": "…"` member so it can be rewritten in place. */
    private val SIGNATURE_MEMBER_REGEX =
        Regex("(\"signature\"\\s*:\\s*\")([^\"]*)(\")")
}
