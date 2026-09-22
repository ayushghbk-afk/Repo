package com.lenix.data

import com.lenix.vm.VmError
import com.lenix.vm.VmInstance
import com.lenix.vm.VmState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsonInstanceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): JsonInstanceStore = JsonInstanceStore(tmp.newFolder("instances"))

    @Test
    fun `save and load roundtrips every field`() {
        val store = newStore()
        val instance = VmInstance(
            id = "debian-ab12cd",
            name = "Build Box",
            distro = "Debian",
            codename = "bookworm",
            version = "12",
            architecture = "arm64-v8a",
            state = VmState.RUNNING,
            lastError = VmError.CHECKSUM_FAILED,
            storagePath = "/data/user/0/com.lenix/files/instances/debian-ab12cd",
            memoryMB = 1024,
            createdAt = 111L,
            updatedAt = 222L,
        )

        store.save(instance)

        val loaded = JsonInstanceStore(store.instanceRoot("debian-ab12cd").parentFile).loadAll()
        assertEquals(listOf(instance), loaded)
    }

    @Test
    fun `corrupt config is skipped without throwing`() {
        val base = tmp.newFolder("instances")
        File(File(base, "broken"), JsonInstanceStore.CONFIG_FILE).apply {
            parentFile.mkdirs()
            writeText("{ this is not json")
        }

        val loaded = JsonInstanceStore(base).loadAll()

        assertTrue(loaded.isEmpty())
        // The damaged file is left alone for later inspection, not deleted.
        assertTrue(File(base, "broken/${JsonInstanceStore.CONFIG_FILE}").isFile)
    }

    @Test
    fun `newer schema versions are skipped`() {
        val base = tmp.newFolder("instances")
        writeConfig(base, "future", schemaVersion = 99)

        assertTrue(JsonInstanceStore(base).loadAll().isEmpty())
    }

    @Test
    fun `record whose id does not match its directory is skipped`() {
        val base = tmp.newFolder("instances")
        writeConfig(base, "directory-name", id = "some-other-id")

        assertTrue(JsonInstanceStore(base).loadAll().isEmpty())
    }

    @Test
    fun `unknown enum values are handled`() {
        val base = tmp.newFolder("instances")
        writeConfig(base, "bad-state", state = "SOME_FUTURE_STATE")
        writeConfig(base, "bad-error", state = "READY", lastError = "SOME_FUTURE_ERROR")

        val loaded = JsonInstanceStore(base).loadAll()

        // An unparsable state invalidates the record; an unknown error degrades to null.
        assertEquals(1, loaded.size)
        assertEquals("bad-error", loaded.single().id)
        assertEquals(VmState.READY, loaded.single().state)
        assertNull(loaded.single().lastError)
    }

    @Test
    fun `delete removes the whole instance directory`() {
        val store = newStore()
        val instance = VmInstance.DEFAULT.copy(id = "debian-ab12cd")
        store.save(instance)
        File(store.instanceRoot("debian-ab12cd"), "rootfs/somefile").apply {
            parentFile.mkdirs()
            writeText("payload")
        }

        store.delete("debian-ab12cd")

        assertFalse(store.instanceRoot("debian-ab12cd").exists())
        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun `delete rejects path-traversal ids`() {
        val store = newStore()

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.delete("../evil")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.instanceRoot(".hidden")
        }
    }

    @Test
    fun `disk usage sums files under the instance root`() {
        val store = newStore()
        val instance = VmInstance.DEFAULT.copy(id = "debian-ab12cd")
        store.save(instance)
        val root = store.instanceRoot("debian-ab12cd")
        File(root, "a.bin").writeText("12345") // 5 bytes
        File(root, "rootfs/b.bin").apply {
            parentFile.mkdirs()
            writeText("1234567890") // 10 bytes
        }

        val usage = store.diskUsageBytes("debian-ab12cd")

        assertTrue(usage >= 15L)
        assertEquals(0L, store.diskUsageBytes("does-not-exist"))
    }

    @Test
    fun `save leaves no temporary file behind`() {
        val store = newStore()

        store.save(VmInstance.DEFAULT)

        val files = store.instanceRoot(VmInstance.DEFAULT.id).list().orEmpty().toList()
        assertEquals(listOf(JsonInstanceStore.CONFIG_FILE), files)
    }

    private fun writeConfig(
        base: File,
        dirId: String,
        id: String = dirId,
        schemaVersion: Int = 1,
        state: String = "READY",
        lastError: String? = null,
    ) {
        val dir = File(base, dirId)
        dir.mkdirs()
        val lastErrorJson = lastError?.let { "\"$it\"" } ?: "null"
        File(dir, JsonInstanceStore.CONFIG_FILE).writeText(
            """
            {
              "schemaVersion": $schemaVersion,
              "id": "$id",
              "name": "Test $id",
              "distro": "Debian",
              "codename": "bookworm",
              "version": "12",
              "architecture": "arm64-v8a",
              "state": "$state",
              "lastError": $lastErrorJson,
              "storagePath": null,
              "memoryMB": 512,
              "createdAt": 10,
              "updatedAt": 20
            }
            """.trimIndent(),
        )
    }
}
