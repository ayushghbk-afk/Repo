package com.lenix.data

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persisted install checkpoint for one instance (`instances/<id>/state.json`).
 *
 * This is the per-step `state.json` from docs/ROOTFS_SYSTEM.md §2: the installer
 * refreshes it at every phase transition and at least every few seconds while a
 * layer downloads, so an interrupted install can be resumed — and shown to the
 * user at the right percentage — after the app process died. The layer `.part`
 * files in the content-addressed cache are the *real* resume points; this record
 * is the fast, human-readable progress snapshot layered on top (see ADR-014/015).
 *
 * [bytesDone]/[bytesTotal] are aggregate values across all layers of the manifest,
 * measured in the unit the phase works in: compressed bytes for `DOWNLOADING`, unpacked
 * bytes for `EXTRACTING` (an interrupted extraction is *restarted*, not resumed — the
 * UI says so rather than promising a continuation).
 */
data class InstallState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val instanceId: String,
    /** Install phase name (`DOWNLOADING`, `VERIFYING`, `EXTRACTING`, `COMMITTING`). */
    val phase: String,
    /** Index of the layer being downloaded or extracted when the phase names one. */
    val layerIndex: Int = 0,
    val layerCount: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val updatedAt: Long = 0,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Reads and writes one instance's [InstallState], with the same wire-format rules
 * as the other JSON stores: atomic temp-file + rename writes, unknown fields
 * ignored, newer-schema or corrupt files load as `null` (an unreadable checkpoint
 * simply means "restart the install from the layer cache's point of view"), and
 * plain [File] inputs so it is JVM unit-testable.
 */
class JsonInstallStateStore(
    private val file: File,
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
) {
    /** The persisted checkpoint, or null when none is readable. */
    fun load(): InstallState? {
        if (!file.isFile) return null
        val state = try {
            objectMapper.readValue<InstallState>(file)
        } catch (e: Exception) {
            return null
        }
        if (state.schemaVersion > InstallState.SCHEMA_VERSION) return null
        return state
    }

    /** Atomically writes (or rewrites) the checkpoint. */
    fun save(state: InstallState) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, file.name + TMP_SUFFIX)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp, state)
        moveAtomically(tmp, file)
    }

    /** Removes the checkpoint — called when an install commits or is discarded. */
    fun clear() {
        file.delete()
        File(file.parentFile, file.name + TMP_SUFFIX).delete()
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

    companion object {
        const val STATE_FILE = "state.json"
        const val TMP_SUFFIX = ".tmp"

        /** Store for `instances/<id>/state.json` given the app's `filesDir`. */
        fun forInstance(filesDir: File, instanceId: String): JsonInstallStateStore =
            JsonInstallStateStore(
                File(File(File(filesDir, INSTANCE_DIR), instanceId), STATE_FILE),
            )

        /** `filesDir/instances` — shared with [JsonInstanceStore]'s layout. */
        const val INSTANCE_DIR = "instances"
    }
}
