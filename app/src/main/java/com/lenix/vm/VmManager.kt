package com.lenix.vm

import com.lenix.data.InstanceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Distro identity for creating a new instance.
 *
 * The UI resolves this from the catalog (`installer.RootfsCatalog`); the manager only
 * validates it, so `vm` stays independent of the installer package.
 */
data class DistroSpec(
    val distro: String,
    val codename: String,
    val version: String,
    val architecture: String = VmInstance.SUPPORTED_ABI,
)

/**
 * Reactive source of truth for the VM manager, backed by an optional [InstanceStore].
 *
 * With a store, every mutation is persisted (ADR-012) and persisted instances are
 * reloaded — with crash normalization via [VmInstance.recoveredForAppRestart] — when
 * the manager is constructed. Without one, it behaves as the Phase 1 in-memory store.
 *
 * Callers are expected to keep disk-facing calls off the main thread; the state-flow
 * contract itself is thread-safe.
 */
class VmManager(
    private val stateMachine: VmStateMachine = VmStateMachine(),
    private val store: InstanceStore? = null,
    private val idGenerator: () -> String = { UUID.randomUUID().toString().substring(0, ID_SUFFIX_LENGTH) },
) {

    private val mutableInstances = MutableStateFlow<Map<String, VmInstance>>(emptyMap())

    val instances: StateFlow<Map<String, VmInstance>> = mutableInstances.asStateFlow()

    private val mutableRunningProcesses = MutableStateFlow<Map<String, VmProcess>>(emptyMap())

    /** Handles for processes that are currently running. */
    val runningProcesses: StateFlow<Map<String, VmProcess>> =
        mutableRunningProcesses.asStateFlow()

    private val activeProcesses = ConcurrentHashMap<String, VmProcess>()

    init {
        val restored = store?.loadAll().orEmpty()
        val recovered = restored.map { VmInstance.recoveredForAppRestart(it) }
        mutableInstances.value = recovered.associateBy { it.id }
        // Repersist anything normalization changed, so disk matches the flow.
        recovered.forEachIndexed { index, instance ->
            if (instance != restored[index]) persist(instance)
        }
        if (mutableInstances.value.isEmpty()) {
            upsert(VmInstance.DEFAULT)
        }
    }

    fun getInstance(id: String): VmInstance? = mutableInstances.value[id]

    fun selectedInstance(): VmInstance = instances.value
        .values
        .minByOrNull { it.updatedAt }
        ?: VmInstance.DEFAULT

    fun setState(id: String, to: VmState, error: VmError? = null): VmInstance? {
        val current = getInstance(id) ?: return null
        val updated = when (to) {
            VmState.ERROR -> current.copy(
                state = VmState.ERROR,
                lastError = error ?: current.lastError ?: VmError.UNKNOWN,
                updatedAt = System.currentTimeMillis(),
            )
            else -> stateMachine.apply(current, to, error)
        }
        return upsert(updated)
    }

    fun beginInstall(id: String) = setState(id, VmState.DOWNLOADING)

    fun markVerifying(id: String) = setState(id, VmState.VERIFYING)

    fun markExtracting(id: String) = setState(id, VmState.EXTRACTING)

    fun markInstalling(id: String) = setState(id, VmState.INSTALLING)

    fun markReady(id: String, storagePath: String? = null): VmInstance? {
        val updated = setState(id, VmState.READY) ?: return null
        return upsert(
            updated.copy(
                storagePath = storagePath ?: updated.storagePath ?: store?.instanceRoot(id)?.absolutePath,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun markError(id: String, error: VmError) = setState(id, VmState.ERROR, error)

    fun start(id: String): VmInstance? {
        val current = getInstance(id) ?: return null
        val updated = stateMachine.apply(current, VmState.STARTING)
        return upsert(updated)
    }

    fun markRunning(id: String, process: VmProcess = VmProcess(instanceId = id)): VmInstance? {
        val updated = setState(id, VmState.RUNNING) ?: return null
        activeProcesses[id] = process
        mutableRunningProcesses.update { it + (id to process) }
        return updated
    }

    fun stop(id: String): VmInstance? {
        val current = getInstance(id) ?: return null
        val updated = when (current.state) {
            VmState.RUNNING, VmState.STARTING -> stateMachine.apply(current, VmState.STOPPING)
            else -> current
        }
        return upsert(updated)
    }

    fun markStopped(id: String): VmInstance? {
        val updated = setState(id, VmState.READY) ?: return null
        activeProcesses.remove(id)
        mutableRunningProcesses.update { it - id }
        return updated
    }

    fun reset(id: String): VmInstance? {
        val current = getInstance(id) ?: return null
        val updated = stateMachine.apply(current, VmState.NOT_INSTALLED)
        return upsert(updated.copy(storagePath = null))
    }

    fun processFor(id: String): VmProcess? = activeProcesses[id]

    /**
     * Creates and persists a new, not-yet-installed instance.
     *
     * @throws IllegalArgumentException for a blank/oversized name or an unsupported
     *   distro/architecture.
     * @throws IllegalStateException when the v0.1 instance limit is reached or no
     *   unique id could be allocated.
     */
    fun createInstance(name: String, distro: DistroSpec): VmInstance {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Instance name cannot be empty." }
        require(trimmed.length <= MAX_NAME_LENGTH) {
            "Instance names are limited to $MAX_NAME_LENGTH characters."
        }
        require(distro.distro.isNotBlank() && distro.version.isNotBlank()) {
            "Incomplete distro selection."
        }
        require(distro.architecture == VmInstance.SUPPORTED_ABI) {
            "Architecture '${distro.architecture}' is not supported yet; " +
                "v0.1 is ${VmInstance.SUPPORTED_ABI} only."
        }
        check(instances.value.size < MAX_INSTANCES) {
            "Lenix supports at most $MAX_INSTANCES instances in v0.1. Delete one first."
        }
        val instance = VmInstance(
            id = uniqueId(slugFor(trimmed).ifEmpty { slugFor(distro.distro) }),
            name = trimmed,
            distro = distro.distro,
            codename = distro.codename,
            version = distro.version,
            architecture = distro.architecture,
        )
        return upsert(instance)
    }

    /**
     * Deletes an instance together with all of its files.
     *
     * @throws IllegalStateException while the instance is installing or running —
     *   those own files/processes that must not vanish underneath them.
     */
    fun deleteInstance(id: String) {
        val current = getInstance(id) ?: return
        check(!current.state.isBusy) {
            "Cannot delete '${current.name}' while it is ${current.state.name.lowercase()}."
        }
        mutableInstances.update { it - id }
        store?.delete(id)
    }

    /**
     * Renames an instance (record-only; touches no RootFS files).
     *
     * @throws IllegalArgumentException for a blank or oversized name.
     */
    fun renameInstance(id: String, newName: String): VmInstance? {
        val current = getInstance(id) ?: return null
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "Instance name cannot be empty." }
        require(trimmed.length <= MAX_NAME_LENGTH) {
            "Instance names are limited to $MAX_NAME_LENGTH characters."
        }
        return upsert(current.copy(name = trimmed, updatedAt = System.currentTimeMillis()))
    }

    /** Bytes used on disk by this instance; 0 when it has no store. */
    fun diskUsageBytes(id: String): Long {
        if (getInstance(id) == null) return 0L
        return store?.diskUsageBytes(id) ?: 0L
    }

    private fun upsert(instance: VmInstance): VmInstance {
        mutableInstances.update { it + (instance.id to instance) }
        persist(instance)
        return instance
    }

    private fun persist(instance: VmInstance) {
        store?.save(instance)
    }

    private fun uniqueId(base: String): String {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = "$base-${idGenerator()}"
            if (!instances.value.containsKey(candidate)) return candidate
        }
        error("Could not allocate a unique id for '$base'.")
    }

    /** `"My Debian 12!" -> "my-debian-12"`; ASCII-only so ids stay valid dir names. */
    private fun slugFor(value: String): String = buildString {
        var lastWasDash = true
        value.lowercase().forEach { c ->
            when {
                c in 'a'..'z' || c in '0'..'9' -> {
                    append(c)
                    lastWasDash = false
                }
                !lastWasDash -> {
                    append('-')
                    lastWasDash = true
                }
            }
        }
    }.take(MAX_ID_LENGTH).trimEnd('-')

    companion object {
        const val MAX_INSTANCES = 4
        const val MAX_NAME_LENGTH = 24
        const val MAX_ID_LENGTH = 16
        const val MAX_ID_ATTEMPTS = 5
        const val ID_SUFFIX_LENGTH = 6
    }
}
