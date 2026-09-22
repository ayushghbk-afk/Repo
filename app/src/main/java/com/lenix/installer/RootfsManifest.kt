package com.lenix.installer

/**
 * Signed RootFS manifest (v0.1 schema).
 *
 * The APK never trusts a URL from the manifest without first validating the
 * corresponding [signature] and each layer [sha256] in [RootfsVerifier].
 */
data class RootfsManifest(
    val schemaVersion: Int,
    val id: String,
    val distro: String,
    val codename: String,
    val arch: String,
    val version: String,
    val channel: String,
    val releasedAt: String,
    val compatibility: Compatibility = Compatibility(),
    val desktop: Desktop = Desktop(),
    val layers: List<Layer>,
    val install: Install,
    val buildinfoUrl: String? = null,
    /**
     * The manifest's own signature — see [RootfsManifestVerifier]. Absent or blank is
     * representable here on purpose: "unsigned" is a *trust* verdict the verifier reports
     * (as `SIGNATURE_FAILED`, with its own UI copy), not a schema error, so the parser must
     * not be the one to call a missing signature "corrupt".
     */
    val signature: String = "",
) {
    data class Compatibility(
        val minAndroidSdk: Int = 29,
        val minRamMb: Int = 2048,
        val recommendedRamMb: Int = 4096,
    )

    data class Desktop(
        val default: String = "openbox",
        val flavors: List<String> = listOf("openbox"),
    )

    data class Layer(
        val id: String,
        val url: String,
        val sizeBytes: Long,
        val uncompressedBytes: Long,
        val sha256: String,
        val compression: String = "zstd",
        val zstdLevel: Int = 19,
    )

    data class Install(
        val estimatedFreeGb: Double,
        val bootCommand: String,
    )
}
