package com.lenix.data

import java.io.File

/**
 * Remembers which instance the user last worked with, so the Home screen reopens on
 * the same environment after an app restart.
 *
 * A one-line plain-text file (`filesDir/selected_instance`) is all the state this
 * needs; heavyweight preference machinery is not justified yet.
 */
class SelectionStore(
    private val file: File,
) {
    /** The persisted selection, or null when nothing (valid) was stored. */
    fun load(): String? = runCatching {
        file.readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun save(id: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(id)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not persist selection to $file" }
        }
    }

    fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }
}
