package com.lenix.installer

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lenix.installer.extract.LayerCompression
import com.lenix.util.Digests

/**
 * Parses a strict-schema RootFS manifest.
 *
 * Unknown fields are ignored so older APKs can still read newer catalogs where it is
 * safe to do so, but required fields are validated hard and reported as a readable
 * [IllegalArgumentException] instead of a Jackson stacktrace.
 */
class RootfsManifestParser(
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
) {
    fun parse(json: String): RootfsManifest {
        val manifest = try {
            objectMapper.readValue<RootfsManifest>(json)
        } catch (e: Exception) {
            // A truncated file, a missing member or a wrongly-typed field must all read as
            // "this manifest is not a manifest", not as a Jackson internal.
            throw IllegalArgumentException(
                "The RootFS manifest could not be read: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        validate(manifest)
        // Digests are content addresses and become cache file names: normalize once, so
        // every consumer (cache, verifier, provenance) compares the identical string.
        return manifest.copy(
            layers = manifest.layers.map { layer ->
                layer.copy(sha256 = layer.sha256.trim().lowercase())
            },
        )
    }

    /**
     * Hard schema validation. Everything the installer will later trust with the
     * network and the filesystem — URLs, sizes, digests — is checked here, before a
     * signature is even looked at, so a malformed manifest cannot reach the layer
     * cache's file naming or the downloader's offsets.
     */
    private fun validate(manifest: RootfsManifest) {
        require(manifest.schemaVersion >= SUPPORTED_SCHEMA_VERSION) {
            "Unsupported manifest schema ${manifest.schemaVersion}; this APK supports $SUPPORTED_SCHEMA_VERSION+"
        }
        require(manifest.layers.isNotEmpty()) {
            "Manifest ${manifest.id} contains no layers."
        }
        require(manifest.layers.size <= MAX_LAYERS) {
            "Manifest ${manifest.id} declares ${manifest.layers.size} layers; at most " +
                "$MAX_LAYERS are supported."
        }
        val seen = HashSet<String>()
        manifest.layers.forEach { layer ->
            require(layer.id.isNotBlank()) { "A layer has no id." }
            require(seen.add(layer.id)) { "Layer id '${layer.id}' appears twice in the manifest." }
            require(isTransportSecure(layer.url)) {
                "Layer ${layer.id} would be downloaded over an insecure URL: ${layer.url}"
            }
            require(layer.sizeBytes > 0L) { "Layer ${layer.id} has no size." }
            require(layer.uncompressedBytes > 0L) { "Layer ${layer.id} has no uncompressed size." }
            require(layer.uncompressedBytes >= layer.sizeBytes) {
                "Layer ${layer.id} claims ${layer.uncompressedBytes} uncompressed bytes for " +
                    "${layer.sizeBytes} compressed bytes — that cannot be a real archive."
            }
            require(Digests.isSha256Hex(layer.sha256.trim())) {
                "Layer ${layer.id} has no usable sha256 digest ('${layer.sha256}')."
            }
            require(LayerCompression.isKnown(layer.compression)) {
                "Layer ${layer.id} declares unknown compression '${layer.compression}'; this " +
                    "build reads xz, gz and plain tar (zstd lands with the native engine)."
            }
            require(layer.zstdLevel in 1..22) { "Layer ${layer.id} has an out-of-range zstd level." }
        }
        require(manifest.install.bootCommand.isNotBlank()) {
            "Manifest ${manifest.id} has no boot command."
        }
    }

    /**
     * True when a layer would not be downgraded on the wire.
     *
     * `https:` is required for everything a device can actually be sent. `http:` is
     * tolerated *only* for a loopback host: that is how the unit tests serve archives, and
     * it lets a developer mirror a candidate RootFS from their own machine. It cannot
     * become a network-wide hole — no release manifest can name a loopback URL, and even
     * then the signed digest, not the transport, is what decides whether bytes are trusted.
     */
    private fun isTransportSecure(url: String): Boolean {
        if (url.startsWith("https://")) return true
        if (!url.startsWith("http://")) return false
        val host = url.substringAfter("http://").substringBefore('/').substringBefore(':').lowercase()
        return host == "localhost" || host == "::1" || host == "0.0.0.0" || host.startsWith("127.")
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1

        /** Layer count ceiling: base + desktop + headroom for a split-out flavor. */
        const val MAX_LAYERS = 8
    }
}
