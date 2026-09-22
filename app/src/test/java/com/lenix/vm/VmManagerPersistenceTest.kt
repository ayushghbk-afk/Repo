package com.lenix.vm

import com.lenix.data.JsonInstanceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 2 contract: the manager is rebuildable from disk and persists on every
 * transition, and process death never leaks transient states into the next run.
 */
class VmManagerPersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newDir(): File = tmp.newFolder()

    private fun newManager(dir: File, idGenerator: () -> String = { "aa1111" }) =
        VmManager(store = JsonInstanceStore(dir), idGenerator = idGenerator)

    /** A second manager over the same directory = "the app restarted". */
    private fun restarted(dir: File) = VmManager(store = JsonInstanceStore(dir))

    @Test
    fun `empty store seeds and persists the default instance`() {
        val dir = newDir()

        val manager = newManager(dir)

        assertNotNull(manager.getInstance(VmInstance.DEFAULT.id))
        assertTrue(JsonInstanceStore(dir).loadAll().isNotEmpty())
    }

    @Test
    fun `mid-install state is persisted and normalized on restart`() {
        val dir = newDir()
        val manager = newManager(dir)
        val id = manager.selectedInstance().id

        manager.beginInstall(id)
        manager.markVerifying(id)
        manager.markExtracting(id)

        // The raw record on disk carries the latest transition...
        assertEquals(VmState.EXTRACTING, JsonInstanceStore(dir).loadAll().single().state)

        // ...and constructing a manager again ("the app restarted") normalizes the
        // interrupted install to a retryable error (VmInstance.recoveredForAppRestart).
        val reloaded = restarted(dir)
        assertEquals(VmState.ERROR, reloaded.getInstance(id)?.state)
        assertEquals(VmError.INSTALL_INTERRUPTED, reloaded.getInstance(id)?.lastError)
    }

    @Test
    fun `markReady persists the storage path`() {
        val dir = newDir()
        val manager = newManager(dir)
        val id = manager.selectedInstance().id
        manager.beginInstall(id)
        manager.markVerifying(id)
        manager.markExtracting(id)
        manager.markInstalling(id)

        manager.markReady(id, storagePath = "/somewhere/instances/$id")

        val reloaded = restarted(dir).getInstance(id)
        assertEquals(VmState.READY, reloaded?.state)
        assertEquals("/somewhere/instances/$id", reloaded?.storagePath)
    }

    @Test
    fun `running instance is recovered as ready after a restart`() {
        val dir = newDir()
        val store = JsonInstanceStore(dir)
        val running = installed().copy(state = VmState.RUNNING)
        store.save(running)

        val manager = restarted(dir)

        assertEquals(VmState.READY, manager.getInstance(running.id)?.state)
        // The normalized state is re-persisted, so a second restart is stable.
        assertEquals(VmState.READY, restarted(dir).getInstance(running.id)?.state)
    }

    @Test
    fun `interrupted install is recovered as retryable error`() {
        val dir = newDir()
        JsonInstanceStore(dir).save(installed().copy(state = VmState.DOWNLOADING))

        val manager = restarted(dir)
        val instance = manager.getInstance(installed().id)

        assertEquals(VmState.ERROR, instance?.state)
        assertEquals(VmError.INSTALL_INTERRUPTED, instance?.lastError)
        // ... and the recovery path stays legal: ERROR -> DOWNLOADING is allowed.
        assertEquals(VmState.DOWNLOADING, manager.beginInstall(installed().id)?.state)
    }

    @Test
    fun `ready and error states survive a restart untouched`() {
        val dir = newDir()
        val store = JsonInstanceStore(dir)
        val ready = installed().copy(id = "debian-ready", state = VmState.READY)
        val errored = installed().copy(id = "debian-broken", state = VmState.ERROR, lastError = VmError.CHECKSUM_FAILED)
        store.save(ready)
        store.save(errored)

        val manager = restarted(dir)

        assertEquals(VmState.READY, manager.getInstance("debian-ready")?.state)
        assertEquals(VmState.ERROR, manager.getInstance("debian-broken")?.state)
        assertEquals(VmError.CHECKSUM_FAILED, manager.getInstance("debian-broken")?.lastError)
    }

    @Test
    fun `createInstance persists a new instance with a slug id`() {
        val dir = newDir()
        val manager = newManager(dir)

        val created = manager.createInstance(
            name = "My Debian 12!",
            distro = DistroSpec(distro = "Debian", codename = "bookworm", version = "12"),
        )

        assertTrue(created.id.startsWith("my-debian-12-"))
        assertEquals(VmState.NOT_INSTALLED, restarted(dir).getInstance(created.id)?.state)
    }

    @Test
    fun `createInstance allocates unique ids on collision`() {
        val dir = newDir()
        var counter = 0
        val manager = VmManager(
            store = JsonInstanceStore(dir),
            idGenerator = { if (counter++ == 0) "dup000" else "uniq1" },
        )

        val first = manager.createInstance("Box One", debianSpec())
        val second = manager.createInstance("Box Two", debianSpec())

        assertEquals("box-one-dup000", first.id)
        assertEquals("box-two-uniq1", second.id)
    }

    @Test
    fun `createInstance rejects bad input`() {
        val dir = newDir()
        val manager = newManager(dir)
        manager.createInstance("First", debianSpec())

        assertThrows(IllegalArgumentException::class.java) {
            manager.createInstance("   ", debianSpec())
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.createInstance("Way too long name for v0.1 limits", debianSpec())
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.createInstance("x86 box", DistroSpec("Debian", "bookworm", "12", architecture = "x86_64"))
        }
    }

    @Test
    fun `instance limit is enforced`() {
        val dir = newDir()
        val manager = newManager(dir, idGenerator = { "aaaa${(0..999).random()}" })

        // Default instance already exists (1 of 4).
        repeat(VmManager.MAX_INSTANCES - 1) { index ->
            manager.createInstance("Box $index", debianSpec())
        }

        assertThrows(IllegalStateException::class.java) {
            manager.createInstance("One too many", debianSpec())
        }
    }

    @Test
    fun `deleteInstance removes the instance from memory and disk`() {
        val dir = newDir()
        val manager = newManager(dir)
        val created = manager.createInstance("Doomed", debianSpec())

        manager.deleteInstance(created.id)

        assertNull(manager.getInstance(created.id))
        assertNull(restarted(dir).getInstance(created.id))
    }

    @Test
    fun `deleteInstance refuses while busy`() {
        val dir = newDir()
        val manager = newManager(dir)
        val id = manager.selectedInstance().id
        manager.beginInstall(id)

        assertThrows(IllegalStateException::class.java) {
            manager.deleteInstance(id)
        }
        assertNotNull(manager.getInstance(id))
    }

    @Test
    fun `renameInstance persists the new name`() {
        val dir = newDir()
        val manager = newManager(dir)
        val id = manager.selectedInstance().id

        manager.renameInstance(id, "Renamed Box")

        assertEquals("Renamed Box", restarted(dir).getInstance(id)?.name)
        assertThrows(IllegalArgumentException::class.java) {
            manager.renameInstance(id, "  ")
        }
    }

    @Test
    fun `reset clears the storage path`() {
        val dir = newDir()
        val manager = newManager(dir)
        val id = manager.selectedInstance().id
        installDemo(manager, id)

        manager.reset(id)

        val reloaded = restarted(dir).getInstance(id)
        assertEquals(VmState.NOT_INSTALLED, reloaded?.state)
        assertNull(reloaded?.storagePath)
    }

    private fun debianSpec() = DistroSpec(distro = "Debian", codename = "bookworm", version = "12")

    /** A READY instance record as it would exist after a completed install. */
    private fun installed() = VmInstance(
        id = "debian-ab12cd",
        name = "Debian Box",
        distro = "Debian",
        codename = "bookworm",
        version = "12",
        state = VmState.READY,
        storagePath = "/data/instances/debian-ab12cd",
    )

    private fun installDemo(manager: VmManager, id: String) {
        manager.beginInstall(id)
        manager.markVerifying(id)
        manager.markExtracting(id)
        manager.markInstalling(id)
        manager.markReady(id)
    }
}
