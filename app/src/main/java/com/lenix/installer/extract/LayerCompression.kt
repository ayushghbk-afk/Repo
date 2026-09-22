package com.lenix.installer.extract

import com.lenix.vm.VmError
import com.lenix.vm.VmException

/**
 * How a RootFS layer archive is compressed (docs/ROOTFS_SYSTEM.md §1: `tar.zst`,
 * `tar.xz`, …), as pinned by the signed manifest.
 *
 * The manifest's `compression` member is authoritative; the archive suffix is only a
 * fallback for manifests that leave the field out. An unknown value is a schema error,
 * a known-but-unreadable one is [VmError.UNSUPPORTED_COMPRESSION] — the two cases the UI
 * must explain differently ("bad manifest" vs "this build cannot read it yet").
 */
enum class LayerCompression(
    /** The canonical spelling used in manifests and documentation. */
    val canonicalName: String,

    /** True when the app's pure-JVM extractor can read it (no native engine needed). */
    val supportedByAppExtractor: Boolean,
) {
    /** An unpacked `tar` stream. */
    NONE("none", true),

    /** `tar.gz` / `tar.tgz` — `java.util.zip`. */
    GZIP("gz", true),

    /** `tar.xz` — `org.tukaani.xz` (pure Java, 0BSD); what the v0.1 layer uses. */
    XZ("xz", true),

    /**
     * `tar.zst` — the format Lenix's own builder will publish. Reading it needs zstd
     * native code, which arrives with the `libpvmnative.so` extractor in Phase 6
     * (`docs/NATIVE_BINARIES.md` H4); the JVM path deliberately does not pretend
     * otherwise.
     */
    ZSTD("zstd", false),
    ;

    companion object {

        /** All spellings accepted in a manifest, mapped to the real format. */
        private val ALIASES = mapOf(
            "none" to NONE,
            "tar" to NONE,
            "plain" to NONE,
            "gz" to GZIP,
            "gzip" to GZIP,
            "tgz" to GZIP,
            "xz" to XZ,
            "lzma2" to XZ,
            "zstd" to ZSTD,
            "zst" to ZSTD,
        )

        /** True when a manifest may name [value] at all. */
        fun isKnown(value: String?): Boolean =
            value != null && ALIASES.containsKey(value.trim().lowercase())

        /**
         * Resolves the format for a layer, preferring [declared] (the manifest field) and
         * falling back to the [url] suffix.
         *
         * @throws VmException with [VmError.UNSUPPORTED_COMPRESSION] when the format is
         *   known but this build cannot read it, and [VmError.DOWNLOAD_CORRUPTED] when the
         *   manifest names something that is not a supported archive format at all.
         */
        fun resolve(declared: String?, url: String, layerId: String = "layer"): LayerCompression {
            val trimmed = declared?.trim()?.lowercase().orEmpty()
            val compression = if (trimmed.isEmpty()) detectFromName(url) else ALIASES[trimmed]
            if (compression == null && trimmed.isEmpty()) {
                throw VmException(
                    VmError.DOWNLOAD_CORRUPTED,
                    "Layer $layerId names no compression and '${url.substringAfterLast('/')}' " +
                        "has no recognized archive suffix.",
                )
            }
            if (compression == null) {
                throw VmException(
                    VmError.DOWNLOAD_CORRUPTED,
                    "Layer $layerId declares unsupported compression '$declared'.",
                )
            }
            if (!compression.supportedByAppExtractor) {
                throw VmException(
                    VmError.UNSUPPORTED_COMPRESSION,
                    "Layer $layerId is a ${compression.canonicalName}-compressed archive, which " +
                        "needs the native Lenix extractor (Phase 6); this build only reads " +
                        "xz, gz and plain tar.",
                )
            }
            return compression
        }

        /** Format from an archive file name (`base.tar.xz` → [XZ]); null when unclear. */
        fun detectFromName(name: String): LayerCompression? {
            val lower = name.substringBefore('?').substringAfter('#').lowercase()
            return when {
                lower.endsWith(".tar.xz") || lower.endsWith(".txz") || lower.endsWith(".xz") -> XZ
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".gz") -> GZIP
                lower.endsWith(".tar.zst") || lower.endsWith(".tzst") || lower.endsWith(".zst") ||
                    lower.endsWith(".zstd") -> ZSTD
                lower.endsWith(".tar") -> NONE
                else -> null
            }
        }
    }
}
