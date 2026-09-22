package com.lenix.data

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lenix.vm.VmError
import com.lenix.vm.VmInstance
import com.lenix.vm.VmState
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persistence for [VmInstance] records.
 *
 * Every mutation of an instance is persisted through this interface (see
 * docs/DECISIONS.md ADR-012: state is persisted on every transition), so the manager
 * can be reconstructed after the app process dies.
 */
interface InstanceStore {
    /** All readable instance records, in unspecified order. */
    fun loadAll(): List<VmInstance>

    /** Atomically writes (or rewrites) the record for [instance.id]. */
    fun save(instance: VmInstance)

    /** Removes the record and every file belonging to [id]. */
    fun delete(id: String)

    /** Directory that owns this instance's files (`instances/<id>/`). */
    fun instanceRoot(id: String): File

    /** Bytes used on disk by this instance (RootFS + records). */
    fun diskUsageBytes(id: String): Long
}

/**
 * On-disk serialization DTO for `instances/<id>/config.json`.
 *
 * Mirrors the instance-record subset of docs/ARCHITECTURE.md §7.3. It is a wire
 * format, not part of the app model — map to [VmInstance] and keep all logic there.
 */
data class InstanceRecord(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val distro: String,
    val codename: String,
    val version: String,
    val architecture: String,
    val state: String,
    val lastError: String? = null,
    val storagePath: String? = null,
    val memoryMB: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toInstance(): VmInstance? {
        val parsedState = runCatching { VmState.valueOf(state) }.getOrNull() ?: return null
        return VmInstance(
            id = id,
            name = name,
            distro = distro,
            codename = codename,
            version = version,
            architecture = architecture,
            state = parsedState,
            lastError = lastError?.let { error -> runCatching { VmError.valueOf(error) }.getOrNull() },
            storagePath = storagePath,
            memoryMB = memoryMB,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun from(instance: VmInstance) = InstanceRecord(
            id = instance.id,
            name = instance.name,
            distro = instance.distro,
            codename = instance.codename,
            version = instance.version,
            architecture = instance.architecture,
            state = instance.state.name,
            lastError = instance.lastError?.name,
            storagePath = instance.storagePath,
            memoryMB = instance.memoryMB,
            createdAt = instance.createdAt,
            updatedAt = instance.updatedAt,
        )
    }
}

/**
 * File-backed [InstanceStore]: one JSON `config.json` per instance directory under
 * [baseDir] (`filesDir/instances` on device), written atomically via temp-file +
 * rename so a crash mid-write can never corrupt the previous record.
 *
 * Takes a plain [File] root instead of a [android.content.Context] so it is unit
 * testable on the JVM. Unreadable records (corrupt JSON, newer schema, id that does
 * not match its directory) are skipped, never deleted — a half-written or foreign
 * file must not take the whole manager down.
 */
class JsonInstanceStore(
    private val baseDir: File,
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
) : InstanceStore {

    override fun loadAll(): List<VmInstance> = baseDir
        .listFiles { file -> file.isDirectory }
        .orEmpty()
        .mapNotNull { dir -> readRecord(File(dir, CONFIG_FILE)) }

    override fun save(instance: VmInstance) {
        val dir = instanceRoot(instance.id)
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, CONFIG_FILE)
        val tmp = File(dir, CONFIG_TMP_FILE)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp, InstanceRecord.from(instance))
        moveAtomically(tmp, target)
    }

    override fun delete(id: String) {
        requireValidId(id)
        File(baseDir, id).deleteRecursively()
    }

    override fun instanceRoot(id: String): File {
        requireValidId(id)
        return File(baseDir, id)
    }

    override fun diskUsageBytes(id: String): Long {
        val root = instanceRoot(id)
        if (!root.exists()) return 0L
        return root.walkBottomUp()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    private fun readRecord(file: File): VmInstance? {
        if (!file.isFile) return null
        val record = try {
            objectMapper.readValue<InstanceRecord>(file)
        } catch (e: Exception) {
            return null
        }
        if (record.schemaVersion > InstanceRecord.SCHEMA_VERSION) return null
        // The directory is the storage authority; a record claiming another id would
        // desync delete() and disk accounting, so it is ignored.
        if (record.id != file.parentFile?.name) return null
        return record.toInstance()
    }

    private fun moveAtomically(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Ids become directory names; never allow path tricks or hidden dirs. */
    private fun requireValidId(id: String) {
        require(ID_REGEX.matches(id)) { "Illegal instance id: '$id'" }
    }

    companion object {
        const val CONFIG_FILE = "config.json"
        const val CONFIG_TMP_FILE = "config.json.tmp"

        private val ID_REGEX = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")
    }
}
