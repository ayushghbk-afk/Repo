package com.lenix.nativebridge

/**
 * Thin JNI surface for `libpvmnative.so` (H4 in docs/NATIVE_BINARIES.md).
 *
 * The library is optional in v0.1: PRoot is launched as a separate process, the
 * terminal falls back to pipe-backed stdio when [openPtyMaster] is unavailable,
 * and zstd extraction stays refused until this `.so` actually loads. Callers must
 * check [available] rather than assuming JNI is present.
 *
 * Adapted from Stryker's proven terminal implementation (terminal.cpp):
 * - Real PTY via /dev/ptmx with UTF8 mode, window size, proper fork/setsid
 * - File descriptor wrapping via reflection (FileDescriptor#descriptor)
 * - ByteQueue-equivalent handling in Kotlin (PtySession)
 * - Process group killing for cleanup
 *
 * Native methods are invoked reflectively so this class still compiles when the
 * NDK / `.so` is not part of the APK (no `external` declarations).
 */
object NativeBridge {

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var loadError: String? = "libpvmnative.so is not bundled in this build"
        private set

    fun tryLoad(loader: () -> Unit = { System.loadLibrary("pvmnative") }): Boolean {
        if (available) return true
        return try {
            loader()
            available = true
            loadError = null
            true
        } catch (e: Throwable) {
            available = false
            loadError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /**
     * Opens a PTY master. Returns a native fd, or -1 when the library is missing
     * or `/dev/ptmx` is blocked (ARCHITECTURE.md §15).
     */
    fun openPtyMaster(): Int {
        if (!available) return -1
        return invokeNative("nativeOpenPty") as? Int ?: -1
    }

    /**
     * Creates a subprocess with PTY, like Stryker's JNI.createSubprocess.
     * Returns PTY master fd, or -1 on failure. Pid is written to pidOut[0].
     */
    fun createSubprocess(
        cmd: String,
        cwd: String,
        args: Array<String>,
        env: Array<String>,
        pidOut: IntArray,
        rows: Int,
        cols: Int,
    ): Int {
        if (!available) return -1
        return invokeNative(
            "nativeCreateSubprocess",
            cmd,
            cwd,
            args,
            env,
            pidOut,
            rows,
            cols,
        ) as? Int ?: -1
    }

    fun setPtyWindowSize(fd: Int, rows: Int, cols: Int): Boolean {
        if (!available) return false
        return try {
            invokeNative("nativeSetPtyWindowSize", fd, rows, cols)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun setPtyUTF8Mode(fd: Int): Boolean {
        if (!available) return false
        return try {
            invokeNative("nativeSetPtyUTF8Mode", fd)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun waitFor(pid: Int): Int {
        if (!available) return -1
        return invokeNative("nativeWaitFor", pid) as? Int ?: -1
    }

    fun closeFd(fd: Int): Boolean {
        if (!available) return false
        return try {
            invokeNative("nativeClose", fd)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun killProcessGroup(pid: Long, signal: Int): Boolean {
        if (!available) return false
        return invokeNative("nativeKillpg", pid, signal) as? Boolean ?: false
    }

    private fun invokeNative(name: String, vararg args: Any): Any? = try {
        val types = args.map { arg ->
            when (arg) {
                is Int -> Int::class.javaPrimitiveType
                is Long -> Long::class.javaPrimitiveType
                is Boolean -> Boolean::class.javaPrimitiveType
                else -> arg.javaClass
            }
        }.toTypedArray()
        val method = javaClass.getDeclaredMethod(name, *types)
        method.isAccessible = true
        method.invoke(this, *args)
    } catch (_: Throwable) {
        null
    }

    // Direct JNI declarations — used when lib is present, called via reflection above
    @Suppress("unused")
    private external fun nativeOpenPty(): Int

    @Suppress("unused")
    private external fun nativeCreateSubprocess(
        cmd: String,
        cwd: String,
        args: Array<String>,
        env: Array<String>,
        pidOut: IntArray,
        rows: Int,
        cols: Int,
    ): Int

    @Suppress("unused")
    private external fun nativeSetPtyWindowSize(fd: Int, rows: Int, cols: Int)

    @Suppress("unused")
    private external fun nativeSetPtyUTF8Mode(fd: Int)

    @Suppress("unused")
    private external fun nativeWaitFor(pid: Int): Int

    @Suppress("unused")
    private external fun nativeClose(fd: Int)

    @Suppress("unused")
    private external fun nativeKillpg(pid: Long, signal: Int): Boolean
}
