package com.lenix.installer

import com.lenix.util.Digests
import com.lenix.vm.VmError
import com.lenix.vm.VmException

/**
 * What the installer is allowed to know about a manifest once its signature checks out.
 *
 * [manifest] is only ever handed out by [RootfsManifestVerifier.verify], so "I have a
 * [RootfsManifest]" means "a key this build trusts pinned these URLs and digests".
 */
data class VerifiedManifest(
    val manifest: RootfsManifest,
    /** The key id that signed it — recorded in the instance's `rootfs.json`. */
    val keyIdHex: String,
    /** The untrusted comment from the public key file, for logs and the UI. */
    val signer: String,
    /** SHA-256 of the canonical payload, so a verification can be re-checked by hand. */
    val payloadSha256: String,
)

/**
 * The trust gate of the install pipeline (docs/ROOTFS_SYSTEM.md §2 step [1]).
 *
 * Parses the manifest, then verifies its Ed25519 signature against the build's
 * [TrustedKeyRing] over the canonical payload. Nothing downstream may trust a URL, a
 * size or a checksum before this returns: a tampered layer digest, a swapped download
 * URL, or a manifest signed by a key this APK does not trust all fail here, before a
 * single byte is fetched.
 */
class RootfsManifestVerifier(
    private val parser: RootfsManifestParser = RootfsManifestParser(),
    private val keyRing: TrustedKeyRing = TrustedKeyRing.EMPTY,
) {

    /**
     * @throws VmException with [VmError.DOWNLOAD_CORRUPTED] for a manifest that does not
     *   parse or breaks the schema, and [VmError.SIGNATURE_FAILED] for a missing,
     *   malformed, unknown-key or forged signature.
     */
    fun verify(manifestJson: String): VerifiedManifest {
        val manifest = try {
            parser.parse(manifestJson)
        } catch (e: VmException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw VmException(VmError.DOWNLOAD_CORRUPTED, e.message, e)
        }

        if (keyRing.isEmpty) {
            throw VmException(
                VmError.SIGNATURE_FAILED,
                "This build embeds no trusted RootFS signing keys, so manifest " +
                    "${manifest.id} cannot be trusted.",
            )
        }

        val signature = try {
            ManifestSignature.parse(manifest.signature)
        } catch (e: IllegalArgumentException) {
            throw VmException(
                VmError.SIGNATURE_FAILED,
                "Manifest ${manifest.id} is not signed with a recognized ed25519 signature " +
                    "(${e.message}).",
                e,
            )
        }

        val key = keyRing.find(signature.keyId)
            ?: throw VmException(
                VmError.SIGNATURE_FAILED,
                "Manifest ${manifest.id} is signed by key 0x${signature.keyIdHex}, which this " +
                    "build does not trust (trusted: ${keyRing.describe()}).",
            )

        val payload = try {
            RootfsManifestCanonicalizer.canonicalBytes(manifestJson)
        } catch (e: IllegalArgumentException) {
            throw VmException(VmError.DOWNLOAD_CORRUPTED, e.message, e)
        }

        val valid = try {
            key.verify(payload, signature.signature)
        } catch (e: Exception) {
            throw VmException(
                VmError.SIGNATURE_FAILED,
                "Ed25519 verification failed on this device: ${e.message}",
                e,
            )
        }
        if (!valid) {
            throw VmException(
                VmError.SIGNATURE_FAILED,
                "The ed25519 signature of manifest ${manifest.id} does not match its content " +
                    "(payload sha256 ${Digests.sha256Hex(payload).take(Digests.SHA256_HEX_LENGTH)}).",
            )
        }

        return VerifiedManifest(
            manifest = manifest,
            keyIdHex = key.keyIdHex,
            signer = key.untrustedComment ?: "key 0x${key.keyIdHex}",
            payloadSha256 = Digests.sha256Hex(payload),
        )
    }
}
