package com.lenix.vm

/**
 * Single source of truth for the lifecycle of a Linux instance.
 *
 * The UI must never track status with loose booleans. Every screen reads the current
 * [VmState] and the installer/launcher transitions through [VmStateMachine].
 */
enum class VmState {
    /** No RootFS has been installed yet. */
    NOT_INSTALLED,

    /** A RootFS layer is being downloaded. */
    DOWNLOADING,

    /** Downloaded bytes are being hashed / signature checked. */
    VERIFYING,

    /** A verified archive is being expanded into the instance directory. */
    EXTRACTING,

    /** Instance metadata and bootstrap scripts are being written. */
    INSTALLING,

    /** Installation completed and the instance is ready to be started. */
    READY,

    /** The Linux process tree is being spawned. */
    STARTING,

    /** The guest is alive and serving a PTY / desktop. */
    RUNNING,

    /** The guest is being shut down cleanly. */
    STOPPING,

    /** The last operation failed. See [VmError] for the specific category. */
    ERROR,
}

/**
 * True while an install or launch operation is in flight. Busy instances cannot be
 * deleted — the installer or the guest owns files and processes underneath them.
 */
val VmState.isBusy: Boolean
    get() = this == VmState.DOWNLOADING ||
        this == VmState.VERIFYING ||
        this == VmState.EXTRACTING ||
        this == VmState.INSTALLING ||
        this == VmState.STARTING ||
        this == VmState.RUNNING ||
        this == VmState.STOPPING
