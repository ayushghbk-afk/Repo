package com.lenix.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsonInstallStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = JsonInstallStateStore(File(tmp.root, "state.json"))

    private val state = InstallState(
        instanceId = "debian-ab12cd",
        phase = "DOWNLOADING",
        layerIndex = 1,
        layerCount = 2,
        bytesDone = 12_000_000,
        bytesTotal = 24_000_000,
        updatedAt = 1_760_000_000_000,
    )

    @Test
    fun `missing checkpoint loads as null`() {
        assertNull(store().load())
    }

    @Test
    fun `save and load roundtrip`() {
        val fileStore = store()

        fileStore.save(state)

        assertEquals(state, fileStore.load())
    }

    @Test
    fun `corrupt checkpoint loads as null`() {
        val file = File(tmp.root, "state.json")
        file.writeText("not json at all")

        assertNull(JsonInstallStateStore(file).load())
    }

    @Test
    fun `newer schema loads as null`() {
        val file = File(tmp.root, "state.json")
        file.writeText(
            """
            {
              "schemaVersion": 99,
              "instanceId": "debian-ab12cd",
              "phase": "DOWNLOADING",
              "updatedAt": 1
            }
            """.trimIndent(),
        )

        assertNull(JsonInstallStateStore(file).load())
    }

    @Test
    fun `clear removes the checkpoint and its temp file`() {
        val fileStore = store()
        fileStore.save(state)

        fileStore.clear()

        assertNull(fileStore.load())
        val dir = tmp.root
        assertFalse(File(dir, "state.json").exists())
        assertFalse(File(dir, "state.json.tmp").exists())
    }

    @Test
    fun `forInstance points at instances-slash-id-slash state json`() {
        val filesDir = tmp.newFolder("files")

        JsonInstallStateStore.forInstance(filesDir, "debian-ab12cd").save(state)

        val expected = File(File(File(filesDir, "instances"), "debian-ab12cd"), "state.json")
        assertTrue(expected.isFile)
        assertEquals(
            state,
            JsonInstallStateStore(File(File(File(filesDir, "instances"), "debian-ab12cd"), "state.json")).load(),
        )
    }
}
