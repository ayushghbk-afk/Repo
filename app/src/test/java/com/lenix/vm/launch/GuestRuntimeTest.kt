package com.lenix.vm.launch

import com.lenix.data.JsonInstanceStore
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import com.lenix.vm.VmManager
import com.lenix.vm.VmState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class GuestRuntimeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `start without proot is NATIVE_ENGINE_FAILED`() {
        val files = tmp.newFolder("files")
        val manager = VmManager(store = JsonInstanceStore(File(files, "instances")))
        installReady(manager)
        val runtime = GuestRuntime(
            filesDir = files,
            manager = manager,
            engine = MissingEngine(),
            nativeLibDir = null,
        )
        try {
            runtime.start(manager.selectedInstance().id, desktop = false)
            throw AssertionError("expected VmException")
        } catch (e: VmException) {
            assertEquals(VmError.NATIVE_ENGINE_FAILED, e.error)
        }
        assertEquals(VmState.ERROR, manager.selectedInstance().state)
    }

    @Test
    fun `start desktop allocates a port and records the password`() {
        val files = tmp.newFolder("files")
        val manager = VmManager(store = JsonInstanceStore(File(files, "instances")))
        installReady(manager)
        val id = manager.selectedInstance().id
        File(files, "instances/$id/rootfs").mkdirs()
        val engine = RecordingEngine()
        val runtime = GuestRuntime(
            filesDir = files,
            manager = manager,
            engine = engine,
            portAllocator = { 5907 },
            passwordFactory = { "aabbccddeeff" },
        )

        val session = runtime.start(id, desktop = true)

        assertEquals(5907, session.vncPort)
        assertEquals(VmState.RUNNING, manager.getInstance(id)?.state)
        assertEquals(5907, manager.processFor(id)?.vncPort)
        assertEquals("aabbccddeeff", File(files, "instances/$id/vnc/password").readText())
        assertEquals(GuestMode.DESKTOP, engine.last?.mode)
        assertEquals(5907, engine.last?.vncPort)

        runtime.stop(id)
        assertEquals(VmState.READY, manager.getInstance(id)?.state)
        assertFalse(session.isAlive())
    }

    @Test
    fun `a running guest owns a terminal that reads even while the window is closed`() {
        val files = tmp.newFolder("files")
        val manager = VmManager(store = JsonInstanceStore(File(files, "instances")))
        installReady(manager)
        val id = manager.selectedInstance().id
        File(files, "instances/$id/rootfs").mkdirs()
        val engine = PipedEngine()
        val runtime = GuestRuntime(filesDir = files, manager = manager, engine = engine)

        runtime.start(id, desktop = false)

        val terminal = runtime.terminal(id)
        assertNotNull("a running guest must have a terminal attached", terminal)
        assertEquals("", terminal!!.snapshot.value.text)

        // Nobody is watching the window; the reader still has to drain the pipe, or the
        // guest blocks once 64 KiB of output piles up.
        engine.session.emit("boot noise\n")
        awaitUntil { terminal.snapshot.value.text.contains("boot noise") }

        runtime.stop(id)
        assertNull(runtime.terminal(id))
        assertFalse(terminal.snapshot.value.text.isEmpty())
    }

    @Test
    fun `restarting a guest replaces its terminal`() {
        val files = tmp.newFolder("files")
        val manager = VmManager(store = JsonInstanceStore(File(files, "instances")))
        installReady(manager)
        val id = manager.selectedInstance().id
        File(files, "instances/$id/rootfs").mkdirs()
        val runtime = GuestRuntime(filesDir = files, manager = manager, engine = PipedEngine())

        runtime.start(id, desktop = false)
        val first = runtime.terminal(id)
        runtime.start(id, desktop = false)
        val second = runtime.terminal(id)

        assertNotNull(first)
        assertNotNull(second)
        assertTrue("a restart must attach a fresh reader", first !== second)
        runtime.stop(id)
    }

    @Test
    fun `shell startup waits for __LENIX_READY__ signal`() {
        val files = tmp.newFolder("files")
        val manager = VmManager(store = JsonInstanceStore(File(files, "instances")))
        installReady(manager)
        val id = manager.selectedInstance().id
        File(files, "instances/$id/rootfs").mkdirs()
        val engine = ShellReadyEngine()
        val runtime = GuestRuntime(filesDir = files, manager = manager, engine = engine)

        // Should succeed - shell emits ready signal
        val session = runtime.start(id, desktop = false)
        assertNotNull(session)
        assertEquals(VmState.RUNNING, manager.getInstance(id)?.state)
        runtime.stop(id)
    }

    @Test
    fun `shell startup fails without ready signal`() {
        val files = tmp.newFolder("files")
        val manager = VmManager(store = JsonInstanceStore(File(files, "instances")))
        installReady(manager)
        val id = manager.selectedInstance().id
        File(files, "instances/$id/rootfs").mkdirs()
        val engine = NoReadySignalEngine()
        val runtime = GuestRuntime(filesDir = files, manager = manager, engine = engine)

        try {
            runtime.start(id, desktop = false)
            throw AssertionError("expected VmException")
        } catch (e: VmException) {
            assertEquals(VmError.ROOTFS_EXTRACTION_FAILED, e.error)
        }
        assertEquals(VmState.ERROR, manager.getInstance(id)?.state)
    }

    @Test
    fun `shell startup fails when process dies before ready`() {
        val files = tmp.newFolder("files")
        val manager = VmManager(store = JsonInstanceStore(File(files, "instances")))
        installReady(manager)
        val id = manager.selectedInstance().id
        File(files, "instances/$id/rootfs").mkdirs()
        val engine = DiesBeforeReadyEngine()
        val runtime = GuestRuntime(filesDir = files, manager = manager, engine = engine)

        try {
            runtime.start(id, desktop = false)
            throw AssertionError("expected VmException")
        } catch (e: VmException) {
            assertEquals(VmError.ROOTFS_EXTRACTION_FAILED, e.error)
        }
    }

    @Test
    fun `vnc password is 12 hex chars`() {
        val pw = VncPassword.generate()
        assertEquals(12, pw.length)
        assertTrue(pw.matches(Regex("[0-9a-f]{12}")))
    }

    private fun awaitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition was still false after ${timeoutMs}ms")
    }

    private fun installReady(manager: VmManager) {
        val id = manager.selectedInstance().id
        manager.beginInstall(id)
        manager.markVerifying(id)
        manager.markExtracting(id)
        manager.markInstalling(id)
        manager.markReady(id)
    }

    private class MissingEngine : GuestEngine {
        override fun isAvailable(filesDir: File, abi: String, nativeLibDir: File?) = false
        override fun launch(request: LaunchRequest): GuestSession = error("unused")
    }

    private class RecordingEngine : GuestEngine {
        var last: LaunchRequest? = null
        override fun isAvailable(filesDir: File, abi: String, nativeLibDir: File?) = true
        override fun launch(request: LaunchRequest): GuestSession {
            last = request
            return FakeSession(request.vncPort)
        }
    }

    private class PipedEngine : GuestEngine {
        /** The session created by the most recent [launch]; tests drive its stdout. */
        lateinit var session: PipedSession
            private set

        override fun isAvailable(filesDir: File, abi: String, nativeLibDir: File?) = true

        // Every launch exec's a fresh shell: the previous session's pipes were closed by
        // stop(), and GuestRuntime.waitForShellReady() needs a live process that prints
        // the ready marker — exactly what ProotCommandBuilder.shell arranges on-device.
        override fun launch(request: LaunchRequest): GuestSession {
            session = PipedSession()
            return session
        }
    }

    /** A guest whose stdout the test writes to, like a shell printing while we look away. */
    private class PipedSession : GuestSession {
        override val isPty: Boolean = false
        override fun updateSize(cols: Int, rows: Int) {}

        private val alive = AtomicBoolean(true)
        private val writer = PipedOutputStream()
        override val pid: Long = 31337
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val stdout: InputStream = PipedInputStream(writer)
        override val vncPort: Int? = null

        init {
            // Signal readiness like the real guest shell does before handing over the
            // prompt, so GuestRuntime.waitForShellReady() lets the start complete.
            // The marker bytes are consumed by waitForShellReady() itself — the terminal
            // reader only attaches afterwards, so the window still starts empty.
            writer.write("__LENIX_READY__\n".toByteArray())
            writer.flush()
        }

        fun emit(text: String) {
            writer.write(text.toByteArray())
            writer.flush()
        }

        override fun isAlive(): Boolean = alive.get()

        override fun stop(graceMs: Long) {
            alive.set(false)
            writer.close()
        }
    }

    private class FakeSession(override val vncPort: Int?) : GuestSession {
        override val isPty: Boolean = false
        override fun updateSize(cols: Int, rows: Int) {}

        private val alive = AtomicBoolean(true)
        override val pid: Long = 4242
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
        override fun isAlive(): Boolean = alive.get()
        override fun stop(graceMs: Long) { alive.set(false) }
    }

    /** Emits __LENIX_READY__ on stdout then stays alive — simulates a working shell. */
    private class ShellReadyEngine : GuestEngine {
        val session = ShellReadySession()
        override fun isAvailable(filesDir: File, abi: String, nativeLibDir: File?) = true
        override fun launch(request: LaunchRequest): GuestSession = session
    }

    private class ShellReadySession : GuestSession {
        override val isPty: Boolean = false
        override fun updateSize(cols: Int, rows: Int) {}

        private val alive = AtomicBoolean(true)
        private val writer = PipedOutputStream()
        override val pid: Long = 31337
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val stdout: InputStream = PipedInputStream(writer)
        override val vncPort: Int? = null

        init {
            // Simulate shell emitting ready signal
            Thread {
                try {
                    writer.write("__LENIX_READY__\n".toByteArray())
                    writer.flush()
                    // Keep alive for a bit
                    Thread.sleep(5000)
                } catch (e: Exception) {
                    // ignore
                }
            }.start()
        }

        override fun isAlive(): Boolean = alive.get()

        override fun stop(graceMs: Long) {
            alive.set(false)
            writer.close()
        }
    }

    /** Emits other output but never __LENIX_READY__ — simulates a broken shell. */
    private class NoReadySignalEngine : GuestEngine {
        val session = NoReadySignalSession()
        override fun isAvailable(filesDir: File, abi: String, nativeLibDir: File?) = true
        override fun launch(request: LaunchRequest): GuestSession = session
    }

    private class NoReadySignalSession : GuestSession {
        override val isPty: Boolean = false
        override fun updateSize(cols: Int, rows: Int) {}

        private val alive = AtomicBoolean(true)
        private val writer = PipedOutputStream()
        override val pid: Long = 31337
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val stdout: InputStream = PipedInputStream(writer)
        override val vncPort: Int? = null

        init {
            Thread {
                try {
                    writer.write("some boot noise\n".toByteArray())
                    writer.flush()
                    Thread.sleep(5000)
                } catch (e: Exception) {
                    // ignore
                }
            }.start()
        }

        override fun isAlive(): Boolean = alive.get()

        override fun stop(graceMs: Long) {
            alive.set(false)
            writer.close()
        }
    }

    /** Dies immediately without emitting ready — simulates shell crash. */
    private class DiesBeforeReadyEngine : GuestEngine {
        val session = DiesBeforeReadySession()
        override fun isAvailable(filesDir: File, abi: String, nativeLibDir: File?) = true
        override fun launch(request: LaunchRequest): GuestSession = session
    }

    private class DiesBeforeReadySession : GuestSession {
        override val isPty: Boolean = false
        override fun updateSize(cols: Int, rows: Int) {}

        private val alive = AtomicBoolean(true)
        override val pid: Long = 31337
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
        override val vncPort: Int? = null

        init {
            // Process dies immediately
            Thread {
                alive.set(false)
            }.start()
        }

        override fun isAlive(): Boolean = alive.get()

        override fun stop(graceMs: Long) {
            alive.set(false)
        }
    }
}
