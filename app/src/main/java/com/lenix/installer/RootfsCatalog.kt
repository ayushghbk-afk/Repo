package com.lenix.installer

import com.lenix.vm.VmError
import com.lenix.vm.VmException

/**
 * Product-scope catalog for v0.1.
 *
 * The implementation intentionally exposes Debian arm64 only. Ubuntu and Alpine rows
 * remain visible in the installer UI but disabled until a later release, which keeps
 * the first build "one distro, one ABI" as decided in docs/DECISIONS.md.
 */
data class DistroOption(
    val id: String,
    val displayName: String,
    val codename: String,
    val version: String,
    val architecture: String,
    val enabled: Boolean,
    /**
     * Bundled manifest asset (`app/src/main/assets/<path>`) pinned by this build.
     * v0.1 has no remote channel yet — see docs/ROOTFS_SYSTEM.md §7 for how the
     * layer referenced by the bundled manifest is sourced.
     */
    val manifestAsset: String? = null,
)

object RootfsCatalog {

    const val SUPPORTED_ABI = "arm64-v8a"

    /**
     * Minisign public-key document(s) embedded in the APK (`assets/<path>`) — the trust
     * anchors for manifest signatures (Phase 4, ADR-017). Rotating a key means adding its
     * public half here and shipping a new APK; old manifests keep verifying while the
     * retired key stays listed.
     */
    const val SIGNING_KEYS_ASSET = "rootfs/keys/lenix-release-2026.pub"

    private const val SUPPORTED_PRODUCT_ABI = "aarch64"

    val options = listOf(
        DistroOption(
            id = "debian",
            displayName = "Debian",
            codename = "bookworm",
            version = "12",
            architecture = SUPPORTED_ABI,
            enabled = true,
            manifestAsset = "rootfs/debian-bookworm-aarch64.json",
        ),
        DistroOption(
            id = "ubuntu",
            displayName = "Ubuntu",
            codename = "noble",
            version = "24.04",
            architecture = SUPPORTED_ABI,
            enabled = false,
        ),
        DistroOption(
            id = "alpine",
            displayName = "Alpine",
            codename = "edge",
            version = "3.20",
            architecture = SUPPORTED_ABI,
            enabled = false,
        ),
    )

    fun selected(): DistroOption = options.first { it.enabled }

    /**
     * Reject ABIs before any network or storage work happens.
     */
    fun requireSupportedArchitecture(architecture: String) {
        if (architecture != SUPPORTED_PRODUCT_ABI && architecture != SUPPORTED_ABI) {
            throw VmException(VmError.UNSUPPORTED_ARCHITECTURE)
        }
    }
}
