package com.lenix.vm

/**
 * Explicit, user-visible failure categories.
 *
 * UI code should map [VmError] to a human readable message instead of showing a
 * generic "Something went wrong" banner.
 */
enum class VmError {
    NETWORK_ERROR,
    INSUFFICIENT_STORAGE,
    DOWNLOAD_CORRUPTED,
    CHECKSUM_FAILED,

    /** The manifest's Ed25519 signature is missing, foreign or forged (Phase 4). */
    SIGNATURE_FAILED,
    ROOTFS_EXTRACTION_FAILED,

    /** The layer uses an archive format this build cannot read (e.g. zstd pre-Phase 6). */
    UNSUPPORTED_COMPRESSION,
    INSTALL_INTERRUPTED,
    UNSUPPORTED_ARCHITECTURE,
    NATIVE_ENGINE_FAILED,
    PROCESS_CRASHED,
    VNC_CONNECTION_FAILED,
    UNKNOWN,
}

private fun VmError.defaultMessage(): String = when (this) {
    VmError.NETWORK_ERROR -> "A network error occurred while downloading the RootFS."
    VmError.INSUFFICIENT_STORAGE -> "Not enough free storage for the selected RootFS."
    VmError.DOWNLOAD_CORRUPTED -> "The downloaded file was damaged or incomplete."
    VmError.CHECKSUM_FAILED -> "The RootFS checksum did not match the signed manifest."
    VmError.SIGNATURE_FAILED -> "The RootFS manifest is not signed by a key this build trusts."
    VmError.ROOTFS_EXTRACTION_FAILED -> "The RootFS could not be extracted."
    VmError.UNSUPPORTED_COMPRESSION ->
        "This RootFS layer uses an archive format this build cannot read yet."
    VmError.INSTALL_INTERRUPTED -> "The install was interrupted. Retry it or reset the instance."
    VmError.UNSUPPORTED_ARCHITECTURE -> "This device architecture is not supported yet."
    VmError.NATIVE_ENGINE_FAILED -> "The native Lenix engine failed to start."
    VmError.PROCESS_CRASHED -> "The Linux process exited unexpectedly."
    VmError.VNC_CONNECTION_FAILED -> "The built-in desktop viewer could not connect."
    VmError.UNKNOWN -> "An unexpected Lenix error occurred."
}

class VmException(
    val error: VmError,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: error.defaultMessage(), cause)
