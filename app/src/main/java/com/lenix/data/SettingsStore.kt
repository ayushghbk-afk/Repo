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
 * User settings for the Lenix app shell.
 *
 * Defaults matter: a fresh install must behave exactly like the documented v0.1
 * flow (storage care on, background runtime and desktop auto-start off).
 */
data class LenixSettings(
    /** Check free space before every RootFS install (storage care). */
    val smartStorage: Boolean = true,

    /** Keep the Linux runtime alive in the background (foreground service, Phase 6+). */
    val allowBackground: Boolean = false,

    /** Jump to the Desktop screen right after START (Phase 7). */
    val autoStartDesktop: Boolean = false,
)

/**
 * On-disk serialization DTO for `filesDir/settings.json`.
 *
 * Same wire-format rules as [InstanceRecord]: a versioned schema, unknown fields
 * ignored so an older APK can read a newer file where safe, and mapping to the
 * app model happens in [toSettings], never in the UI.
 */
data class SettingsRecord(
    val schemaVersion: Int = SCHEMA_VERSION,
    val smartStorage: Boolean = true,
    val allowBackground: Boolean = false,
    val autoStartDesktop: Boolean = false,
) {
    fun toSettings(): LenixSettings = LenixSettings(
        smartStorage = smartStorage,
        allowBackground = allowBackground,
        autoStartDesktop = autoStartDesktop,
    )

    companion object {
        const val SCHEMA_VERSION = 1

        fun from(settings: LenixSettings) = SettingsRecord(
            smartStorage = settings.smartStorage,
            allowBackground = settings.allowBackground,
            autoStartDesktop = settings.autoStartDesktop,
        )
    }
}

/**
 * File-backed settings persistence (`filesDir/settings.json`), written atomically
 * (temp file + rename) so a crash mid-write can never lose the previous settings.
 *
 * Takes a plain [File] instead of a [android.content.Context] so it is unit
 * testable on the JVM, mirroring [JsonInstanceStore] and [SelectionStore]. A
 * missing, corrupt, or newer-schema file loads as defaults: settings must never
 * take the app down, and every field has a documented default.
 *
 * This store fixes the "settings not saving" bug: the Settings screen used to
 * keep toggles in `remember { mutableStateOf(...) }` UI state, so every value was
 * lost on navigation or process death.
 */
class JsonSettingsStore(
    private val file: File,
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
) {
    /** The persisted settings, or [LenixSettings] defaults when nothing readable exists. */
    fun load(): LenixSettings {
        if (!file.isFile) return LenixSettings()
        val record = try {
            objectMapper.readValue<SettingsRecord>(file)
        } catch (e: Exception) {
            return LenixSettings()
        }
        if (record.schemaVersion > SettingsRecord.SCHEMA_VERSION) return LenixSettings()
        return record.toSettings()
    }

    /** Atomically writes [settings]. */
    fun save(settings: LenixSettings) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, file.name + TMP_SUFFIX)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp, SettingsRecord.from(settings))
        moveAtomically(tmp, file)
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
        const val TMP_SUFFIX = ".tmp"
    }
}
