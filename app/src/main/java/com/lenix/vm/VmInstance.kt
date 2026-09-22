package com.lenix.vm

/**
 * A single Linux environment managed by Lenix.
 *
 * v0.1 deliberately supports ARM64 Debian only. Other distros / ABIs are parsed by
 * the catalog and displayed as "coming soon" rather than being enabled.
 */
data class VmInstance(
    val id: String,
    val name: String,
    val distro: String,
    val codename: String,
    val version: String,
    val architecture: String = SUPPORTED_ABI,
    val state: VmState = VmState.NOT_INSTALLED,
    val lastError: VmError? = null,
    val storagePath: String? = null,
    val memoryMB: Int = DEFAULT_MEMORY_MB,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SUPPORTED_ABI = "arm64-v8a"
        const val DEFAULT_MEMORY_MB = 512

        val DEFAULT = VmInstance(
            id = "default",
            name = "Lenix Linux",
            distro = "Debian",
            codename = "bookworm",
            version = "12",
        )

        /**
         * Normalizes a persisted state after the app process died.
         *
         * Transient states can never survive a process restart: the guest dies with
         * the app, and an in-flight install has no worker left. Anything that was
         * starting/running/stopping becomes READY again (the RootFS is installed and
         * the process is simply gone), and an interrupted install becomes ERROR with
         * [VmError.INSTALL_INTERRUPTED] so the UI can offer a RESUME — the Phase 3
         * installer picks the download back up from the layer cache and any
         * `.part` file instead of restarting from zero.
         */
        fun recoveredForAppRestart(instance: VmInstance): VmInstance = when (instance.state) {
            VmState.DOWNLOADING,
            VmState.VERIFYING,
            VmState.EXTRACTING,
            VmState.INSTALLING,
            -> instance.copy(
                state = VmState.ERROR,
                lastError = VmError.INSTALL_INTERRUPTED,
                updatedAt = System.currentTimeMillis(),
            )

            VmState.STARTING,
            VmState.RUNNING,
            VmState.STOPPING,
            -> instance.copy(
                state = VmState.READY,
                lastError = null,
                updatedAt = System.currentTimeMillis(),
            )

            VmState.NOT_INSTALLED, VmState.READY, VmState.ERROR -> instance
        }
    }
}
