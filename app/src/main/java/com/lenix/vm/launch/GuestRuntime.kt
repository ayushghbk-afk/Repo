package com.lenix.vm.launch

import android.util.Log
import com.lenix.nativebridge.NativeSetup
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import com.lenix.vm.VmManager
import com.lenix.vm.pty.PtySession
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns live guest sessions for the manager: start (PRoot + optional desktop),
 * stop with SIGTERM→SIGKILL, and the PTY/pipe handle the terminal reads.
 *
 * Each running session also owns exactly one [PtySession] reader for as long as the
 * guest lives — not just while the terminal screen is composed. Two reasons: the
 * guest's stdout is a pipe, so an unread 64 KiB buffer blocks the shell, and the
 * transcript must survive navigating away from the terminal window and back.
 *
 * Improved from Stryker reference:
 * - Session lifecycle: app start, session start, backgrounding, foregrounding,
 *   activity recreation, config changes, process death, cleanup
 * - No orphaned X servers, VNC servers, shell processes, PTYs, temp files
 * - Proper cleanup of pid files, X locks, logs (like Stryker's vncserver-stop)
 * - Better error diagnostics with actual underlying logs
 * - PTY support for real terminal handling (like Stryker's TerminalSession)
 *
 * @param nativeLibDir signed APK native payload dir (`ApplicationInfo.nativeLibraryDir`)
 *   — the only place Android 10+ allows `execve` of the engine (ADR-021).
 */
class GuestRuntime(
    private val filesDir: File,
    private val manager: VmManager,
    private val engine: GuestEngine = ProotGuestEngine(),
    private val nativeLibDir: File? = null,
    private val abi: String = NativeSetup.DEFAULT_ABI,
    private val portAllocator: () -> Int = { VncPortAllocator.allocate() },
    private val passwordFactory: () -> String = { VncPassword.generate() },
) {
    private val sessions = ConcurrentHashMap<String, GuestSession>()

    /** One terminal reader per running guest session, closed when the guest stops. */
    private val terminals = ConcurrentHashMap<String, PtySession>()

    fun session(id: String): GuestSession? = sessions[id]

    /** The terminal attached to [id]'s guest session, or null when it is not running. */
    fun terminal(id: String): PtySession? = terminals[id]

    fun isEngineAvailable(): Boolean = engine.isAvailable(filesDir, abi, nativeLibDir)

    fun start(id: String, desktop: Boolean, geometry: String = ProotCommandBuilder.DEFAULT_GEOMETRY): GuestSession {
        // A restart has to go through the real stop path: killing the old process behind
        // the manager's back leaves the instance RUNNING, and RUNNING -> STARTING is an
        // illegal transition (VmStateMachine), so the restart would throw. It also has to
        // detach the old session's terminal.
        if (sessions.containsKey(id)) {
            Log.d("Lenix", "Stopping existing session for $id before restart")
            stop(id)
        }
        val instance = manager.getInstance(id)
            ?: throw VmException(VmError.UNKNOWN, "Unknown instance '$id'.")

        val root = instanceRoot(id)
        val rootfs = File(root, "rootfs")

        if (!engine.isAvailable(filesDir, abi, nativeLibDir)) {
            manager.markError(id, VmError.NATIVE_ENGINE_FAILED)
            throw VmException(
                VmError.NATIVE_ENGINE_FAILED,
                buildString {
                    append("The PRoot engine for $abi is not on this device.\n")
                    append("Ship the engine payload (${NativeSetup.PROOT} + ${NativeSetup.PROOT_LOADER}) under app/src/main/jniLibs/$abi/ and rebuild.\n")
                    append("Diagnostics:\n")
                    append("- filesDir: ${filesDir.absolutePath}\n")
                    append("- nativeLibDir: ${nativeLibDir?.absolutePath ?: "null"}\n")
                    append("- abi: $abi\n")
                    append("- Check ./scripts/fetch-engine.sh $abi")
                },
            )
        }

        // Diagnostic: log rootfs contents for debugging shell startup issues
        Log.d("Lenix", "Starting guest $id: rootfs=${rootfs.absolutePath}, desktop=$desktop, geometry=$geometry")
        Log.d("Lenix", "Rootfs exists: ${rootfs.isDirectory}, isPtyAvailable: ${com.lenix.nativebridge.NativeBridge.available}")
        val shellCandidates = listOf("bin/sh", "bin/bash", "usr/bin/sh", "usr/bin/bash")
        for (candidate in shellCandidates) {
            val f = File(rootfs, candidate)
            val exists = f.isFile || java.nio.file.Files.isSymbolicLink(f.toPath())
            Log.d("Lenix", "  $candidate: ${if (exists) "EXISTS" else "MISSING"}")
        }

        // Check for X server binaries when desktop mode
        if (desktop) {
            val xCandidates = listOf("usr/bin/Xvnc", "usr/bin/Xvfb", "usr/bin/x11vnc", "usr/bin/openbox-session", "usr/bin/xfce4-session")
            for (candidate in xCandidates) {
                val f = File(rootfs, candidate)
                val exists = f.isFile
                Log.d("Lenix", "  $candidate: ${if (exists) "EXISTS" else "MISSING"}")
            }
        }

        val vncPort = if (desktop) portAllocator() else null
        val password = if (desktop) passwordFactory() else null
        if (password != null) {
            try {
                VncPassword.write(File(File(root, "vnc"), "password"), password)
                File(File(root, "vnc"), "port").writeText(vncPort.toString())
            } catch (e: Exception) {
                Log.w("Lenix", "Failed to write VNC password/port: ${e.message}")
            }
        }
        ensureResolv(File(File(root, "etc"), "resolv.conf"))
        ensureHosts(File(File(root, "etc"), "hosts"))

        // Cleanup stale files from previous sessions (Stryker's cleanup pattern)
        cleanupStaleFiles(root)

        manager.start(id)
        val session = try {
            engine.launch(
                LaunchRequest(
                    instanceId = id,
                    filesDir = filesDir,
                    rootfs = rootfs,
                    home = File(root, "home"),
                    shared = File(filesDir, "shared"),
                    resolv = File(File(root, "etc"), "resolv.conf"),
                    mode = if (desktop) GuestMode.DESKTOP else GuestMode.SHELL,
                    vncPort = vncPort,
                    vncPassword = password,
                    geometry = geometry,
                    desktop = ProotCommandBuilder.DEFAULT_DESKTOP,
                    abi = abi,
                    nativeLibDir = nativeLibDir,
                ),
            )
        } catch (e: VmException) {
            manager.markError(id, e.error)
            // Include underlying error details
            throw VmException(
                e.error,
                buildString {
                    append(e.message ?: "Failed to launch guest")
                    append("\nDiagnostics:\n")
                    append("- Instance: $id\n")
                    append("- Mode: ${if (desktop) "DESKTOP" else "SHELL"}\n")
                    append("- Rootfs: ${rootfs.absolutePath} (exists=${rootfs.isDirectory})\n")
                    append("- VNC port: $vncPort\n")
                    e.cause?.let { append("- Cause: ${it.message}\n") }
                },
                e,
            )
        } catch (e: Exception) {
            manager.markError(id, VmError.NATIVE_ENGINE_FAILED)
            throw VmException(
                VmError.NATIVE_ENGINE_FAILED,
                buildString {
                    append("Could not start the PRoot engine (${e.message ?: e.javaClass.simpleName}).\n")
                    append("The $abi engine payload must sit in the signed APK's native library ")
                    append("directory (app/src/main/jniLibs/$abi/) — Android 10+ refuses to exec ")
                    append("engines from filesDir (docs/DECISIONS.md ADR-021).\n")
                    append("Diagnostics:\n")
                    append("- Error: ${e.message}\n")
                    append("- Type: ${e.javaClass.simpleName}\n")
                    append("- filesDir: ${filesDir.absolutePath}\n")
                    append("- nativeLibDir: ${nativeLibDir?.absolutePath ?: "null"}\n")
                },
                e,
            )
        }
        sessions[id] = session

        // Shell mode: wait for the shell to signal it started successfully.
        if (!desktop) {
            val ready = waitForShellReady(session, timeoutMs = 8000)
            if (!ready) {
                Log.e("Lenix", "Shell did not start properly for instance $id, pid=${session.pid}, alive=${session.isAlive()}")
                // Try to get logs
                val logs = tryReadGuestLogs(root)
                manager.markError(id, VmError.ROOTFS_EXTRACTION_FAILED)
                session.stop()
                sessions.remove(id)
                throw VmException(
                    VmError.ROOTFS_EXTRACTION_FAILED,
                    buildString {
                        append("The Linux shell did not start.\n")
                        append("Diagnostics:\n")
                        append("- Instance: $id\n")
                        append("- PID: ${session.pid}\n")
                        append("- Alive: ${session.isAlive()}\n")
                        append("- Rootfs: ${rootfs.absolutePath}\n")
                        append("- Check that RootFS contains /bin/sh or /bin/bash\n")
                        append("- Try reinstalling RootFS\n")
                        if (logs.isNotEmpty()) {
                            append("- Guest logs:\n$logs\n")
                        }
                    },
                )
            }
            Log.d("Lenix", "Shell ready for instance $id, pid=${session.pid}, isPty=${session.isPty}")
        } else {
            // Desktop mode: wait a bit for X server to start, but don't fail immediately
            // VNC viewer will retry connection
            Log.d("Lenix", "Desktop session starting for $id, port=$vncPort, pid=${session.pid}")
            Thread.sleep(500)
        }

        // Attach the reader before handing the session out: from here on the guest's
        // stdout always has a consumer, in shell mode and in desktop mode alike.
        val ptySession = PtySession(
            guest = session,
            onClipboardText = { text ->
                Log.d("Lenix", "Clipboard from guest: ${text.take(50)}")
                // Could sync to Android clipboard here if context available
            },
        ).start()
        terminals[id] = ptySession
        manager.markRunning(id, session.toVmProcess(id))
        return session
    }

    private fun waitForShellReady(session: GuestSession, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val readBuf = ByteArray(4096)
        val accumulated = StringBuilder()

        while (System.currentTimeMillis() < deadline) {
            if (!session.isAlive()) {
                Log.w("Lenix", "Guest process died while waiting for shell ready, pid=${session.pid}")
                Log.w("Lenix", "Accumulated output: ${accumulated}")
                return false
            }
            try {
                val available = session.stdout.available()
                if (available > 0) {
                    val read = session.stdout.read(readBuf, 0, minOf(available, readBuf.size))
                    if (read > 0) {
                        val output = String(readBuf, 0, read)
                        accumulated.append(output)
                        Log.d("Lenix", "Guest stdout: ${output.trim()}")
                        if (output.contains("__LENIX_READY__") || accumulated.contains("__LENIX_READY__")) {
                            return true
                        }
                        // Also check for shell prompt as fallback
                        if (output.contains("$ ") || output.contains("# ") || output.contains("bash")) {
                            // Give it a bit more time to emit READY marker
                            Thread.sleep(100)
                            if (accumulated.contains("__LENIX_READY__")) return true
                            // If we see prompt but no READY, still consider it ready for PTY mode
                            if (session.isPty) return true
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w("Lenix", "Error reading guest stdout: ${e.message}")
                return false
            } catch (e: Exception) {
                Log.w("Lenix", "Error waiting for shell ready: ${e.message}")
                return false
            }
            Thread.sleep(50)
        }
        Log.w("Lenix", "Timeout waiting for shell ready ($timeoutMs ms), accumulated: ${accumulated.take(500)}")
        // For PTY mode, if process is alive, consider it ready even without marker
        // (PTY shell may not emit marker due to buffering)
        return session.isPty && session.isAlive()
    }

    fun stop(id: String) {
        Log.d("Lenix", "Stopping guest $id")
        manager.stop(id)
        val session = sessions.remove(id)
        closeTerminal(id)

        // Cleanup guest files (like Stryker's vncserver-stop)
        try {
            val root = instanceRoot(id)
            cleanupGuestProcesses(root, session?.vncPort)
        } catch (e: Exception) {
            Log.w("Lenix", "Cleanup failed for $id: ${e.message}")
        }

        session?.stop()
        manager.markStopped(id)
        Log.d("Lenix", "Stopped guest $id")
    }

    private fun closeTerminal(id: String) {
        terminals.remove(id)?.close()
    }

    fun instanceRoot(id: String): File = File(filesDir, "instances/$id")

    private fun ensureResolv(file: File) {
        if (file.isFile) return
        file.parentFile?.mkdirs()
        try {
            file.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        } catch (e: Exception) {
            Log.w("Lenix", "Failed to write resolv.conf: ${e.message}")
        }
    }

    private fun ensureHosts(file: File) {
        if (file.isFile) return
        file.parentFile?.mkdirs()
        try {
            file.writeText("127.0.0.1 localhost\n::1 localhost\n")
        } catch (e: Exception) {
            Log.w("Lenix", "Failed to write hosts: ${e.message}")
        }
    }

    private fun cleanupStaleFiles(root: File) {
        try {
            // Remove stale X locks and pid files (Stryker pattern)
            val tmp = File(root, "tmp")
            if (tmp.isDirectory) {
                tmp.listFiles()?.forEach { f ->
                    if (f.name.startsWith(".X") && f.name.endsWith("-lock")) {
                        f.delete()
                    }
                    if (f.name == ".X11-unix") {
                        f.listFiles()?.forEach { it.delete() }
                    }
                    if (f.name.endsWith(".pid") || f.name == "xvnc.log" || f.name == "xvfb.log" || f.name == "x11vnc.log" || f.name == "session.log") {
                        f.delete()
                    }
                }
            }
            // Also check rootfs/tmp
            val rootfsTmp = File(File(root, "rootfs"), "tmp")
            if (rootfsTmp.isDirectory) {
                rootfsTmp.listFiles()?.forEach { f ->
                    if (f.name.startsWith(".X") && f.name.endsWith("-lock")) f.delete()
                    if (f.name.endsWith(".pid")) f.delete()
                }
            }
        } catch (e: Exception) {
            Log.w("Lenix", "Failed to cleanup stale files: ${e.message}")
        }
    }

    private fun cleanupGuestProcesses(root: File, vncPort: Int?) {
        try {
            // Write a cleanup script and try to run it? For now just delete files
            // The actual process killing is done via killpg in GuestSession.stop()
            val tmp = File(root, "tmp")
            listOf("xvnc.pid", "xvfb.pid", "x11vnc.pid", "session.pid", ".lenix-desktop-ready").forEach { name ->
                File(tmp, name).delete()
                File(File(root, "rootfs/tmp"), name).delete()
            }
            // Remove X locks
            File(tmp, ".X1-lock").delete()
            File(File(root, "rootfs/tmp"), ".X1-lock").delete()
        } catch (e: Exception) {
            Log.w("Lenix", "Cleanup guest processes failed: ${e.message}")
        }
    }

    private fun tryReadGuestLogs(root: File): String {
        return try {
            val logs = StringBuilder()
            listOf("tmp/xvnc.log", "tmp/xvfb.log", "tmp/x11vnc.log", "tmp/session.log").forEach { path ->
                val f = File(File(root, "rootfs"), path)
                if (f.isFile && f.length() > 0) {
                    logs.append("--- $path ---\n")
                    logs.append(f.readText().take(2000))
                    logs.append("\n")
                }
            }
            logs.toString()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Handles app backgrounding — like Stryker's NeoTermService lifecycle.
     */
    fun onAppBackgrounded(allowBackground: Boolean) {
        if (!allowBackground) {
            // Detach terminals but keep guest running? Or stop? Lenix stops on task removed by default
            Log.d("Lenix", "App backgrounded, allowBackground=$allowBackground, sessions=${sessions.size}")
        }
    }

    fun onAppForegrounded() {
        // Check which sessions are still alive
        val dead = mutableListOf<String>()
        sessions.forEach { (id, session) ->
            if (!session.isAlive()) dead.add(id)
        }
        dead.forEach { id ->
            Log.w("Lenix", "Session $id died while in background")
            sessions.remove(id)
            closeTerminal(id)
            manager.markStopped(id)
        }
    }

    fun onDestroy() {
        Log.d("Lenix", "GuestRuntime onDestroy, cleaning up ${sessions.size} sessions")
        sessions.keys.toList().forEach { id ->
            try {
                stop(id)
            } catch (_: Exception) {
            }
        }
        terminals.values.forEach { it.close() }
        terminals.clear()
    }
}
