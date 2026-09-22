package com.lenix.nativebridge

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Host-side engine layout and ELF validation (H1–H3 in docs/NATIVE_BINARIES.md).
 *
 * Android 10+ SELinux policy (AOSP commit 0dd738d8) denies `execve()` on files in the
 * app's own data directory — `neverallow all_untrusted_apps { app_data_file
 * privapp_data_file }:file execute_no_trans` — even when the file is mode 0700. That is
 * the "W^X" restriction ARCHITECTURE.md §5.3 used to describe. What actually stays
 * allowed is:
 *
 *  - `execve()` of files under `/data/app/...` (the signed APK's native library payload
 *    dir, SELinux type `apk_data_file`): `allow appdomain apk_data_file:file
 *    { ... x_file_perms }` in app.te, where `x_file_perms` includes `execute_no_trans`.
 *  - `mmap(PROT_EXEC)` / `dlopen` of `app_data_file` files (`execute`, not
 *    `execute_no_trans`) — which is exactly how PRoot's built-in loader runs guest
 *    binaries: the kernel only ever `execve`s PRoot's own loader, never the guest ELF.
 *  - `execve()` of `/system/bin/linker[64]` (`system_linker_exec`, the
 *    `system_linker_exec` workaround Termux uses).
 *
 * So engine binaries must live in the APK's native payload directory
 * (`app/src/main/jniLibs/<abi>/`, extracted to `ApplicationInfo.nativeLibraryDir`),
 * never in `filesDir/`. See docs/DECISIONS.md ADR-021.
 */
object NativeSetup {

    const val NATIVE_DIR = "native"

    /**
     * Engine payload file names.
     *
     * They **must** look like `lib*.so` even though they are executables, not libraries.
     * The package manager does not extract everything under the APK's `lib/<abi>/`: for a
     * non-debuggable package `NativeLibrariesIterator` keeps only entries whose base name
     * starts with `lib` and ends with `.so`
     * (`frameworks/base` → `libs/androidfw/ApkParsing.cpp`, `ValidLibraryPathLastSlash()`).
     * A payload shipped as `proot` / `loader` / `libtalloc.so.2` is packaged into the APK
     * but silently **never extracted** to `ApplicationInfo.nativeLibraryDir`, so the app
     * reports "No PRoot engine found" on every release build. Debug builds hide the bug:
     * `debuggable` relaxes that filter, so the old names work in development only.
     *
     * The names are arbitrary as far as the engine is concerned — PRoot is exec'd by
     * absolute path and its loader is pinned through `$PROOT_LOADER` — so `lib*.so`
     * costs nothing. Only the `.so` DT_NEEDED entries have to agree with the file names,
     * which `scripts/fetch-engine.sh` guarantees when it stages the payload.
     */
    const val PROOT = "libproot.so"

    /** PRoot's static runtime loader — must be exec-able, so it ships in the APK payload. */
    const val PROOT_LOADER = "libprootloader.so"
    const val TINI = "libtini.so"
    const val BUSYBOX = "libbusybox.so"

    /** Bionic shared-library dependencies of the Termux PRoot build (H1c). */
    const val LIB_TALLOC = "libtalloc.so"
    const val LIB_ANDROID_SHMEM = "libandroid-shmem.so"

    /**
     * Historic payload names, still accepted when they are actually present.
     *
     * Debug builds do extract them (the `lib*.so` filter is skipped for debuggable
     * packages) and `filesDir/native/<abi>` installs from older dev setups use them, so
     * resolution falls back to them instead of failing on a payload that is right there.
     */
    val PROOT_NAMES = listOf(PROOT, "proot")
    val PROOT_LOADER_NAMES = listOf(PROOT_LOADER, "loader")
    val TINI_NAMES = listOf(TINI, "tini")
    val BUSYBOX_NAMES = listOf(BUSYBOX, "busybox")

    /** A shared-library dependency of the engine and the file names it may carry. */
    data class EngineDep(val canonical: String, val aliases: List<String> = emptyList()) {
        val candidates: List<String> get() = listOf(canonical) + aliases
    }

    val BIONIC_DEPS = listOf(
        EngineDep(LIB_TALLOC, listOf("libtalloc.so.2")),
        EngineDep(LIB_ANDROID_SHMEM),
    )

    /**
     * True when Android's package manager extracts `name` from the APK's `lib/<abi>/`
     * into `nativeLibraryDir` for a **release** (non-debuggable) build.
     *
     * Mirrors `ValidLibraryPathLastSlash()`: base name starts with `lib`, ends with
     * `.so`, and contains only characters `isFilenameSafe()` accepts.
     */
    fun isExtractablePayloadName(name: String): Boolean =
        name.startsWith("lib") &&
            name.endsWith(".so") &&
            name.length >= "lib.so".length &&
            name.all { c ->
                // isFilenameSafe() is ASCII-only; `Char.isLetterOrDigit()` would also
                // accept non-ASCII letters that the installer rejects.
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                    c == '+' || c == ',' || c == '-' || c == '.' || c == '=' || c == '_'
            }

    /** First existing file among [names] in [dir], or null when none is present. */
    fun findPayloadFile(dir: File, names: List<String>): File? =
        names.asSequence().map { File(dir, it) }.firstOrNull { it.isFile }

    /** Environment variables consumed by proot's runtime (src/execve/enter.c). */
    const val ENV_PROOT_LOADER = "PROOT_LOADER"
    const val ENV_PROOT_TMP_DIR = "PROOT_TMP_DIR"
    const val ENV_TMPDIR = "TMPDIR"
    const val ENV_LD_LIBRARY_PATH = "LD_LIBRARY_PATH"

    val REQUIRED_FOR_GUEST = listOf(PROOT)

    /** Legacy debug location only — direct exec here fails on Android 10+ (ADR-021). */
    fun nativeDir(filesDir: File, abi: String = DEFAULT_ABI): File =
        File(filesDir, "$NATIVE_DIR/$abi")

    // ---- ELF probing -------------------------------------------------------------

    /** Minimal ELF facts needed to decide how (and whether) an engine can be executed. */
    data class ElfProbe(
        val isElf: Boolean,
        /** 32 or 64; 0 when the header is not a usable ELF. */
        val bits: Int,
        /** `e_machine`, 0 when unknown. */
        val machine: Int,
        /** Contents of the PT_INTERP segment (`/system/bin/linker64` for bionic), or null. */
        val interp: String? = null,
    ) {
        val isClass64: Boolean get() = bits == 64
    }

    // ELF e_machine values (ELF spec).
    const val EM_386 = 3
    const val EM_ARM = 40
    const val EM_X86_64 = 62
    const val EM_AARCH64 = 183

    private const val PT_INTERP = 3

    /**
     * Reads just enough of [file] to identify an ELF, its architecture and its dynamic
     * loader. Never throws; a non-ELF or unreadable file yields [ElfProbe.isElf] == false.
     */
    fun probe(file: File): ElfProbe {
        if (!file.isFile || file.length() < 20) return ElfProbe(false, 0, 0, null)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                // ELF32 header is 52 bytes, ELF64 is 64; read the union size.
                val header = ByteArray(64)
                var off = 0
                while (off < header.size) {
                    val n = raf.read(header, off, header.size - off)
                    if (n <= 0) break
                    off += n
                }
                if (off < 52) return ElfProbe(false, 0, 0, null)
                if (header[0] != 0x7f.toByte() ||
                    header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() ||
                    header[3] != 'F'.code.toByte()
                ) {
                    return ElfProbe(false, 0, 0, null)
                }
                val class64 = when (header[4]) {
                    2.toByte() -> true
                    1.toByte() -> false
                    else -> return ElfProbe(false, 0, 0, null)
                }
                if (class64 && off < 64) return ElfProbe(false, 0, 0, null)
                val machine = u16(header, 18)
                val phOff = if (class64) u64(header, 32) else u32(header, 28).toLong()
                val phEntSize = if (class64) u16(header, 54) else u16(header, 42)
                val phNum = if (class64) u16(header, 56) else u16(header, 44)
                val interp = readInterp(raf, class64, phOff, phEntSize, phNum)
                ElfProbe(true, if (class64) 64 else 32, machine, interp)
            }
        } catch (_: IOException) {
            ElfProbe(false, 0, 0, null)
        } catch (_: SecurityException) {
            ElfProbe(false, 0, 0, null)
        }
    }

    private fun readInterp(
        raf: RandomAccessFile,
        class64: Boolean,
        phOff: Long,
        phEntSize: Int,
        phNum: Int,
    ): String? {
        // Hoist the if-expression: inline `a < if (x) 1 else 0 || b` is parsed as
        // `a < (if (x) 1 else (0 || b))` (an Int || Boolean) — a compile error.
        val interpEntSize = if (class64) 56 else 32
        if (phNum !in 1..4096 || phEntSize < interpEntSize || phOff <= 0L) return null
        val phdr = ByteArray(phEntSize)
        for (i in 0 until phNum) {
            raf.seek(phOff + i.toLong() * phEntSize)
            if (!readFully(raf, phdr)) return null
            if (u16(phdr, 0) != PT_INTERP) continue
            val pOff = if (class64) u64(phdr, 8) else u32(phdr, 4).toLong()
            val pFilesz = if (class64) u64(phdr, 32) else u32(phdr, 16).toLong()
            if (pFilesz !in 1L..4096L) return null
            raf.seek(pOff)
            val buf = ByteArray(pFilesz.toInt())
            if (!readFully(raf, buf)) return null
            return String(buf, Charsets.UTF_8).trimEnd('\u0000')
        }
        return null
    }

    private fun readFully(raf: RandomAccessFile, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = raf.read(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    fun isElf(file: File): Boolean = probe(file).isElf

    /** The bionic dynamic linker Android uses for [abi] (the `system_linker_exec` path). */
    fun systemLinkerPath(abi: String): String? = when (abi) {
        "arm64-v8a", "x86_64" -> "/system/bin/linker64"
        "armeabi-v7a", "x86" -> "/system/bin/linker"
        else -> null
    }

    fun machineFor(abi: String): Int = when (abi) {
        "arm64-v8a" -> EM_AARCH64
        "armeabi-v7a" -> EM_ARM
        "x86_64" -> EM_X86_64
        "x86" -> EM_386
        else -> 0
    }

    /**
     * True when [file] is an ELF for [abi]'s architecture (static or dynamic). A real
     * PRoot build, a static loader and a static tini all qualify.
     */
    fun isMachineCompatibleElf(file: File, abi: String): Boolean {
        val info = probe(file)
        return info.isElf && info.machine == machineFor(abi)
    }

    /**
     * True when [file] is a bionic-linked ELF for [abi] — i.e. PT_INTERP is the Android
     * system linker. Such a binary can be exec'd from the APK payload dir directly, or
     * from app data via the `system_linker_exec` workaround ([AndroidExecBridge]).
     */
    fun isBionicExecutable(file: File, abi: String): Boolean {
        val info = probe(file)
        return info.isElf &&
            info.machine == machineFor(abi) &&
            info.interp == systemLinkerPath(abi)
    }

    // ---- little-endian header helpers --------------------------------------------

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)

    private fun u64(b: ByteArray, off: Int): Long =
        u32(b, off) or (u32(b, off + 4) shl 32)

    const val DEFAULT_ABI = "arm64-v8a"
}
