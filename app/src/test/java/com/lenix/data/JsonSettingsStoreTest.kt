package com.lenix.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsonSettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = JsonSettingsStore(File(tmp.root, "settings.json"))

    @Test
    fun `missing file loads as defaults`() {
        assertEquals(LenixSettings(), store().load())
    }

    @Test
    fun `save and load roundtrip for every field`() {
        val fileStore = store()

        fileStore.save(
            LenixSettings(smartStorage = false, allowBackground = true, autoStartDesktop = true),
        )

        val loaded = fileStore.load()
        assertFalse(loaded.smartStorage)
        assertTrue(loaded.allowBackground)
        assertTrue(loaded.autoStartDesktop)
    }

    @Test
    fun `defaults roundtrip too`() {
        val fileStore = store()

        fileStore.save(LenixSettings())

        assertEquals(LenixSettings(), fileStore.load())
    }

    @Test
    fun `save overwrites the previous settings`() {
        val fileStore = store()
        fileStore.save(LenixSettings(allowBackground = true))

        fileStore.save(LenixSettings(allowBackground = false))

        assertFalse(fileStore.load().allowBackground)
    }

    @Test
    fun `corrupt file loads as defaults instead of crashing`() {
        val file = File(tmp.root, "settings.json")
        file.writeText("{ this is not json")

        assertEquals(LenixSettings(), JsonSettingsStore(file).load())
    }

    @Test
    fun `newer schema loads as defaults`() {
        val file = File(tmp.root, "settings.json")
        file.writeText(
            """
            {
              "schemaVersion": 99,
              "smartStorage": false,
              "allowBackground": true,
              "autoStartDesktop": true
            }
            """.trimIndent(),
        )

        assertEquals(LenixSettings(), JsonSettingsStore(file).load())
    }

    @Test
    fun `unknown fields from a newer minor format are ignored`() {
        val file = File(tmp.root, "settings.json")
        file.writeText(
            """
            {
              "schemaVersion": 1,
              "smartStorage": false,
              "allowBackground": false,
              "autoStartDesktop": true,
              "someFutureSetting": "value"
            }
            """.trimIndent(),
        )

        val loaded = JsonSettingsStore(file).load()
        assertFalse(loaded.smartStorage)
        assertTrue(loaded.autoStartDesktop)
    }

    @Test
    fun `writes are atomic - no temp file is left behind`() {
        val fileStore = store()

        fileStore.save(LenixSettings(autoStartDesktop = true))

        val dir = tmp.root
        assertTrue(File(dir, "settings.json").isFile)
        assertEquals(1, dir.listFiles()?.size)
    }
}
