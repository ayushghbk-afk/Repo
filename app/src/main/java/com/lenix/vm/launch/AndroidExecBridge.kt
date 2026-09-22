package com.lenix.vm.launch

import com.lenix.nativebridge.NativeSetup
import java.io.File

/**
 * The `system_linker_exec` workaround for Android's W^X policy (ADR-021).
 *
 * Android 10+ (targetSdk ≥ 29) denies `execve()` of files under the app's data
 * directory: SELinux `neverallow { all_untrusted_apps } { app_data_file
 * privapp_data_file }:file execute_no_trans`. The kernel only allows `execve` of:
 *
 *  - files under `/data/app/...` (`apk_data_file`, which keeps `x_file_perms`),
 *  - `/system/bin/linker[64]` (`system_linker_exec`),
 *  - and `mmap(PROT_EXEC)` of app-data files (`execute`, which is how dlopen and
 *    PRoot's own loader map guest ELFs).
 *
 * So a bionic-linked engine that must run from app data (legacy
 * `filesDir/native/<abi>/` installs) is executed via the Android dynamic linker:
 * the kernel sees only `/system/bin/linker64` being executed and the linker
 * mmap-loads the engine — exactly the mechanism Termux's termux-exec uses.
 */
object AndroidExecBridge {

    /** Read-only system locations where direct exec is allowed for app domains. */
    private val SYSTEM_DIRS = listOf(
        "/system", "/apex", "/vendor", "/odm", "/product", "/system_ext", "/sbin",
    )

    /**
     * Returns the argv for [ProcessBuilder], wrapping [argv]'s program with the system
     * linker when direct exec would be denied.
     *
     * @param isAndroid false on the JVM (unit tests, desktop) — nothing is wrapped.
     * @param isRestricted predicate for "direct execve is denied here"; defaults to app
     *   data paths, which is the SELinux case on Android 10+.
     */
    fun resolve(
        argv: List<String>,
        abi: String = NativeSetup.DEFAULT_ABI,
        isAndroid: Boolean = isAndroidRuntime(),
        isRestricted: (String) -> Boolean = { path -> isAppDataPath(path) },
        linkerAvailable: (String) -> Boolean = { path -> File(path).isFile },
    ): List<String> {
        if (argv.isEmpty() || !isAndroid) return argv
        val programPath = File(argv[0]).absolutePath
        if (SYSTEM_DIRS.any { programPath.startsWith(it) }) return argv
        if (!isRestricted(programPath)) return argv
        val program = File(programPath)
        // Only bionic-linked ELFs can be run by the Android linker; static ELFs (like
        // PRoot's loader or a static tini) must live somewhere SELinux allows exec.
        if (!NativeSetup.isBionicExecutable(program, abi)) return argv
        val linker = NativeSetup.systemLinkerPath(abi) ?: return argv
        if (!linkerAvailable(linker)) return argv
        // The linker treats its first non-option argument as the ELF to load; the loaded
        // program sees argv[0] = the ELF path (bionic sets initial_linker_arg_count=1),
        // so the PRoot arg layout is preserved exactly.
        return listOf(linker, program.absolutePath) + argv.drop(1)
    }

    /** True when running on Android 10+ (the SELinux policy applies from API 29). */
    fun isAndroidRuntime(): Boolean = try {
        android.os.Build.VERSION.SDK_INT >= 29
    } catch (_: Throwable) {
        false
    }

    fun isAppDataPath(path: String): Boolean =
        path.startsWith("/data/user/") ||
            path.startsWith("/data/data/") ||
            path.startsWith("/mnt/")
}
