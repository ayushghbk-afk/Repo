package com.lenix.vm

/**
 * Lightweight handle for a running guest process.
 *
 * Phase 1 stores metadata only. Phase 3+ will attach the real Process / PTY ids and
 * provide stop() with SIGTERM then SIGKILL semantics.
 */
data class VmProcess(
    val instanceId: String,
    val pid: Long? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val vncPort: Int? = null,
    val exitCode: Int? = null,
)
