package com.lenix.vm.launch

import android.system.OsConstants
import android.util.Log
import com.lenix.nativebridge.EngineInstaller
import com.lenix.nativebridge.NativeBridge
import com.lenix.nativebridge.NativeSetup
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import com.lenix.vm.VmProcess
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Field
import java.util.concurrent.TimeUnit

enum class GuestMode { SHELL, DESKTOP }

data class LaunchRequest(
    val instanceId: String,
    val filesDir: File,
    val rootfs: File,
    val home: File,
    val shared: File,
    val resolv: File,
    val mode: GuestMode,
    val vncPort: Int? = null,
    val vncPassword: String? = null,
    val geometry: String = ProotCommandBuilder.DEFAULT_GEOMETRY,
    val desktop: String = ProotCommandBuilder.DEFAULT_DESKTOP,
    val abi: String = NativeSetup.DEFAULT_ABI,
    /**
     * `ApplicationInfo.nativeLibraryDir` — the signed APK payload directory that
     * Android 10+ actually allows `execve` from (ADR-021).
     */
    val nativeLibDir: File? = null,
    val termRows: Int = 24,
    val termCols: Int = 80,
)

interface GuestSession {
    val pid: Long
    val stdin: OutputStream
    val stdout: InputStream
    val vncPort: Int?
    val isPty: Boolean
    fun isAlive(): Boolean
    fun stop(graceMs: Long = 10_000)
    fun updateSize(cols: Int, rows: Int)
}

interface GuestEngine {
    /**
     * @param nativeLibDir signed APK payload dir (already ABI-specific), or null.
     */
    fun isAvailable(filesDir: File, abi: String, nativeLibDir: File?): Boolean
    fun launch(request: LaunchRequest): GuestSession
}

/**
 * Real PRoot engine — improved from Stryker's proven terminal implementation.
 *
 * The engine is exec'd from the APK payload (`ApplicationInfo.nativeLibraryDir`):
 * Android 10+'s SELinux W^X policy denies direct exec from `filesDir`, and a legacy
 * `filesDir/native/<abi>/proot` install is rejected on-device (its static loader
 * cannot be relayed through `/system/bin/linker64`). `PROOT_LOADER` is pinned to the
 * payload's static loader so PRoot never extracts+execs one into app temp (also
 * denied). [AndroidExecBridge] remains as a safety net for bionic host helpers that
 * must run from app data.
 *
 * PTY improvements from Stryker:
 * - When NativeBridge is available, shell sessions use real PTY via /dev/ptmx
 *   (open, grantpt, unlockpt, set UTF8, set window size, fork, setsid)
 * - PTY provides proper line discipline, echo, signals (Ctrl-C → SIGINT)
 * - Fallback to pipe mode when PTY unavailable (old behavior)
 * - Process group killing via killpg for cleanup
 */
class ProotGuestEngine : GuestEngine {

    override fun isAvailable(filesDir: File, abi: String, nativeLibDir: File?): Boolean =
        EngineInstaller.ensureEngine(filesDir, abi, nativeLibDir).isReady

    override fun launch(request: LaunchRequest): GuestSession {
        val status = EngineInstaller.ensureEngine(
            filesDir = request.filesDir,
            abi = request.abi,
            nativeLibDir = request.nativeLibDir,
        )
        if (!status.isReady) {
            throw VmException(
                VmError.NATIVE_ENGINE_FAILED,
                status.reason
                    ?: "PRoot engine for ${request.abi} is not ready. Add the complete engine " +
                        "payload under app/src/main/jniLibs/${request.abi}/ and rebuild.",
            )
        }
        val proot = status.proot
        if (proot == null) {
            throw VmException(
                VmError.NATIVE_ENGINE_FAILED,
                status.reason
                    ?: "PRoot engine for ${request.abi} is not installed. Add the engine " +
                        "payload under app/src/main/jniLibs/${request.abi}/ and rebuild.",
            )
        }
        if (!request.rootfs.isDirectory) {
            throw VmException(
                VmError.NATIVE_ENGINE_FAILED,
                "RootFS is missing at ${request.rootfs.absolutePath}.",
            )
        }
        // Pre-flight: the rootfs must contain at least one shell binary or PRoot will
        // fail with a cryptic "'/bin/sh' not found" error.
        val shellCandidates = listOf("bin/sh", "bin/bash", "usr/bin/sh", "usr/bin/bash")
        val hasShell = shellCandidates.any { candidate ->
            val f = File(request.rootfs, candidate)
            f.isFile || java.nio.file.Files.isSymbolicLink(f.toPath())
        }
        if (!hasShell) {
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "The RootFS at ${request.rootfs.absolutePath} is empty or missing a shell " +
                    "(/bin/sh, /bin/bash). Install or reinstall the RootFS from the Home screen.",
            )
        }
        request.home.mkdirs()
        request.shared.mkdirs()

        val payloadDir = request.nativeLibDir?.takeIf { it.isDirectory }
        val legacyDir = NativeSetup.nativeDir(request.filesDir, request.abi)
        val libDir = (payloadDir ?: legacyDir).takeIf { it.isDirectory }
        val tini = payloadDir?.let { NativeSetup.findPayloadFile(it, NativeSetup.TINI_NAMES) }
            ?: NativeSetup.findPayloadFile(legacyDir, NativeSetup.TINI_NAMES)
            ?: File(legacyDir, NativeSetup.TINI)

        val argv = when (request.mode) {
            GuestMode.SHELL -> ProotCommandBuilder.shell(
                proot = proot,
                rootfs = request.rootfs,
                home = request.home,
                resolv = request.resolv,
                shared = request.shared,
            )
            GuestMode.DESKTOP -> ProotCommandBuilder.desktop(
                proot = proot,
                rootfs = request.rootfs,
                home = request.home,
                resolv = request.resolv,
                shared = request.shared,
                vncPort = request.vncPort ?: error("desktop launch needs a VNC port"),
                geometry = request.geometry,
                desktop = request.desktop,
                tini = tini.takeIf { it.isFile },
            )
        }

        val tmpDir = File(request.filesDir, "proot-tmp").apply { mkdirs() }

        // For shell mode, try PTY first (Stryker's proven approach)
        if (request.mode == GuestMode.SHELL && NativeBridge.available) {
            try {
                val ptySession = tryLaunchWithPty(
                    argv = argv,
                    cwd = request.rootfs.absolutePath,
                    env = buildEnv(status, tmpDir, libDir),
                    rows = request.termRows,
                    cols = request.termCols,
                    vncPort = request.vncPort,
                )
                if (ptySession != null) {
                    Log.d("Lenix", "PTY session created for ${request.instanceId}, pid=${ptySession.pid}")
                    return ptySession
                }
            } catch (e: Exception) {
                Log.w("Lenix", "PTY launch failed, falling back to pipe: ${e.message}")
            }
        }

        // Fallback: ProcessBuilder (pipe mode)
        val command = AndroidExecBridge.resolve(argv, request.abi)
        val builder = ProcessBuilder(command)
            .directory(request.rootfs)
            .redirectErrorStream(true)
        ProotCommandBuilder.applyEngineEnvironment(
            environment = builder.environment(),
            loader = status.loader,
            tmpDir = tmpDir,
            libDir = libDir,
        )
        val process = builder.start()
        return ProcessGuestSession(
            process = process,
            vncPort = request.vncPort,
            isPty = false,
        )
    }

    private fun buildEnv(
        status: com.lenix.nativebridge.EngineInstaller.EngineStatus,
        tmpDir: File,
        libDir: File?,
    ): Map<String, String> {
        val env = mutableMapOf<String, String>()
        // Copy current env plus engine env
        env.putAll(System.getenv() ?: emptyMap())
        ProotCommandBuilder.applyEngineEnvironment(
            environment = env,
            loader = status.loader,
            tmpDir = tmpDir,
            libDir = libDir,
        )
        return env
    }

    private fun tryLaunchWithPty(
        argv: List<String>,
        cwd: String,
        env: Map<String, String>,
        rows: Int,
        cols: Int,
        vncPort: Int?,
    ): GuestSession? {
        if (argv.isEmpty()) return null
        val cmd = argv[0]
        val args = argv.toTypedArray()
        val envArray = env.map { "${it.key}=${it.value}" }.toTypedArray()
        val pidOut = IntArray(1)

        val fd = NativeBridge.createSubprocess(
            cmd = cmd,
            cwd = cwd,
            args = args,
            env = envArray,
            pidOut = pidOut,
            rows = rows,
            cols = cols,
        )
        if (fd < 0) return null
        val pid = pidOut[0]
        if (pid <= 0) {
            NativeBridge.closeFd(fd)
            return null
        }

        // Wrap fd to FileDescriptor via reflection (Stryker's method)
        val fileDesc = wrapFileDescriptor(fd) ?: run {
            NativeBridge.closeFd(fd)
            return null
        }

        val input = FileInputStream(fileDesc)
        val output = FileOutputStream(fileDesc)

        return PtyGuestSession(
            pid = pid.toLong(),
            ptyFd = fd,
            fileDescriptor = fileDesc,
            stdin = output,
            stdout = input,
            vncPort = vncPort,
        )
    }

    private fun wrapFileDescriptor(fd: Int): FileDescriptor? {
        return try {
            val result = FileDescriptor()
            val field: Field = try {
                FileDescriptor::class.java.getDeclaredField("descriptor")
            } catch (e: NoSuchFieldException) {
                FileDescriptor::class.java.getDeclaredField("fd")
            }
            field.isAccessible = true
            field.set(result, fd)
            result
        } catch (e: Exception) {
            Log.e("Lenix", "Failed to wrap fd $fd: ${e.message}")
            null
        }
    }
}

class ProcessGuestSession(
    private val process: Process,
    override val vncPort: Int?,
    override val isPty: Boolean = false,
) : GuestSession {
    override val pid: Long = pidOf(process)
    override val stdin: OutputStream = process.outputStream
    override val stdout: InputStream = process.inputStream

    override fun isAlive(): Boolean = process.isAlive

    override fun stop(graceMs: Long) {
        try {
            // Try process group kill first (Stryker's cleanup pattern)
            if (NativeBridge.available && pid > 0) {
                NativeBridge.killProcessGroup(pid, OsConstants.SIGTERM)
            }
        } catch (_: Exception) {
        }
        process.destroy()
        if (!process.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
            try {
                if (NativeBridge.available && pid > 0) {
                    NativeBridge.killProcessGroup(pid, OsConstants.SIGKILL)
                }
            } catch (_: Exception) {
            }
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }

    override fun updateSize(cols: Int, rows: Int) {
        // No-op for pipe mode
    }

    companion object {
        fun pidOf(process: Process): Long = try {
            val method = Process::class.java.getMethod("pid")
            (method.invoke(process) as? Long) ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }
}

/**
 * PTY-backed guest session — from Stryker's TerminalSession.
 * Uses real PTY master fd, so bash gets proper terminal handling.
 */
class PtyGuestSession(
    override val pid: Long,
    private val ptyFd: Int,
    private val fileDescriptor: FileDescriptor,
    override val stdin: OutputStream,
    override val stdout: InputStream,
    override val vncPort: Int?,
) : GuestSession {
    override val isPty: Boolean = true

    @Volatile
    private var alive = true

    override fun isAlive(): Boolean {
        if (!alive) return false
        // Check if process still exists via kill(pid, 0)
        return try {
            if (NativeBridge.available) {
                // Use waitpid with WNOHANG to check if exited? For now check via kill
                android.system.Os.kill(pid.toInt(), 0)
                true
            } else {
                true
            }
        } catch (e: android.system.ErrnoException) {
            if (e.errno == OsConstants.ESRCH) {
                alive = false
                false
            } else {
                true
            }
        } catch (_: Exception) {
            alive
        }
    }

    override fun stop(graceMs: Long) {
        alive = false
        try {
            if (NativeBridge.available) {
                NativeBridge.killProcessGroup(pid, OsConstants.SIGTERM)
                // Wait a bit
                Thread.sleep(500)
                if (isAlive()) {
                    NativeBridge.killProcessGroup(pid, OsConstants.SIGKILL)
                }
            }
        } catch (_: Exception) {
        }
        try {
            stdin.close()
        } catch (_: Exception) {
        }
        try {
            stdout.close()
        } catch (_: Exception) {
        }
        if (NativeBridge.available) {
            NativeBridge.closeFd(ptyFd)
        }
    }

    override fun updateSize(cols: Int, rows: Int) {
        if (NativeBridge.available) {
            NativeBridge.setPtyWindowSize(ptyFd, rows, cols)
        }
    }
}

fun GuestSession.toVmProcess(instanceId: String): VmProcess = VmProcess(
    instanceId = instanceId,
    pid = pid,
    vncPort = vncPort,
)
