package com.lenix.nativebridge

import com.lenix.vm.launch.AndroidExecBridge
import java.io.File

/**
 * Locates and validates the host-side PRoot engine (H1 in docs/NATIVE_BINARIES.md).
 *
 * Since Android 10 the engine must be **executed from the APK's native payload
 * directory** (`app/src/main/jniLibs/<abi>/` → `ApplicationInfo.nativeLibraryDir`,
 * SELinux `apk_data_file`), never from `filesDir/` (ADR-021). This class therefore
 * validates the payload that the package manager extracted, not an asset copy.
 */
object EngineInstaller {

    enum class Source {
        /** Engine from the signed APK payload under `/data/app/.../lib/<abi>`. */
        APK_PAYLOAD,

        /** Legacy `filesDir/native/<abi>` — debug only; direct exec is denied on Android 10+. */
        LEGACY_FILES_DIR,

        NONE,
    }

    data class EngineStatus(
        val available: Boolean,
        /** The PRoot binary to execute (may be under app data in legacy mode). */
        val proot: File?,
        /** PRoot's static runtime loader, or null when it is not shipped (guest execs will fail). */
        val loader: File?,
        val source: Source,
        /**
         * Human-readable explanation when the engine is not usable. It is non-null when
         * [available] is false, when [source] is legacy, or when a payload is present but
         * has a runtime caveat (missing loader / bionic deps).
         */
        val reason: String? = null,
    ) {
        /**
         * True only when the engine can actually launch a guest. `available` alone is not
         * enough: a payload may contain a valid `proot` while still failing at runtime
         * (missing static loader or bionic `.so` deps), and [reason] records that caveat.
         * Autofix / start must use this, not raw [available].
         */
        val isReady: Boolean get() = available && reason == null
    }

    private val NOT_AVAILABLE = EngineStatus(false, null, null, Source.NONE)

    /**
     * Resolves the engine with a hard preference for the signed APK payload.
     *
     * @param nativeLibDir `ApplicationInfo.nativeLibraryDir` (already ABI-specific), or null
     *   outside Android / in tests.
     */
    fun ensureEngine(
        filesDir: File,
        abi: String = NativeSetup.DEFAULT_ABI,
        nativeLibDir: File? = null,
        isAndroid: Boolean = AndroidExecBridge.isAndroidRuntime(),
    ): EngineStatus {
        // 1. Signed APK payload (the only location Android 10+ allows exec from).
        if (nativeLibDir != null && nativeLibDir.isDirectory) {
            val proot = NativeSetup.findPayloadFile(nativeLibDir, NativeSetup.PROOT_NAMES)
                ?: File(nativeLibDir, NativeSetup.PROOT)
            if (NativeSetup.isMachineCompatibleElf(proot, abi)) {
                val loader = NativeSetup.findPayloadFile(nativeLibDir, NativeSetup.PROOT_LOADER_NAMES)
                val loaderValid = loader != null && NativeSetup.isMachineCompatibleElf(loader, abi)
                val caveats = mutableListOf<String>().apply {
                    // A bionic-linked PRoot needs its shared libraries next to it. Without
                    // them the process cannot even start, so this must block `isReady`.
                    if (NativeSetup.isBionicExecutable(proot, abi)) {
                        val missing = NativeSetup.BIONIC_DEPS.filter { dep ->
                            NativeSetup.findPayloadFile(nativeLibDir, dep.candidates) == null
                        }
                        if (missing.isNotEmpty()) {
                            add("its shared library " +
                                (if (missing.size == 1) "dependency is" else "dependencies are") +
                                " missing: ${missing.joinToString { it.canonical }}")
                        }
                    }
                    if (!loaderValid) {
                        add("its static loader is missing or corrupt")
                    }
                }
                return EngineStatus(
                    available = true,
                    proot = proot,
                    loader = loader.takeIf { loaderValid },
                    source = Source.APK_PAYLOAD,
                    reason = if (caveats.isEmpty()) {
                        null
                    } else {
                        "PRoot '$abi' payload is present but ${caveats.joinToString("; ")} — " +
                            "guest binaries cannot start. Ship the missing files (named " +
                            "lib*.so so Android extracts them) next to ${NativeSetup.PROOT} " +
                            "in app/src/main/jniLibs/$abi/ — run scripts/fetch-engine.sh."
                    },
                )
            }
            val payloadProot = NativeSetup.findPayloadFile(nativeLibDir, NativeSetup.PROOT_NAMES)
                ?: File(nativeLibDir, NativeSetup.PROOT)
            if (payloadProot.exists() && !NativeSetup.isElf(payloadProot)) {
                return EngineStatus(
                    available = false,
                    proot = null,
                    loader = null,
                    source = Source.NONE,
                    reason = "The bundled '$abi' proot payload is not an ELF executable " +
                        "(${payloadProot.absolutePath}). Drop the real PRoot binary into " +
                        "app/src/main/jniLibs/$abi/ and rebuild.",
                )
            }
            if (payloadProot.exists()) {
                val want = NativeSetup.machineFor(abi)
                val got = NativeSetup.probe(payloadProot).machine
                return EngineStatus(
                    available = false,
                    proot = null,
                    loader = null,
                    source = Source.NONE,
                    reason = "The bundled proot payload is for a different architecture " +
                        "(e_machine 0x${got.toString(16)}, expected $abi " +
                        "0x${want.toString(16)} at ${payloadProot.absolutePath}). Ship " +
                        "the ${abi} build in app/src/main/jniLibs/$abi/.",
                )
            }
        }

        // 2. Legacy user-placed filesDir copy. On Android 10+ this cannot work at all:
        //    SELinux denies direct exec of app-data files, and PRoot's static `loader`
        //    (exec'd for every guest binary) has no `/system/bin/linker64` relay — only
        //    the signed APK payload is exec-able. Keep it as the JVM/desktop fallback.
        val legacyDir = NativeSetup.nativeDir(filesDir, abi)
        val legacyProot = NativeSetup.findPayloadFile(legacyDir, NativeSetup.PROOT_NAMES)
            ?: File(legacyDir, NativeSetup.PROOT)
        if (NativeSetup.isMachineCompatibleElf(legacyProot, abi)) {
            if (isAndroid) {
                return NOT_AVAILABLE.copy(
                    reason = "A $abi engine exists at ${legacyProot.absolutePath}, but " +
                        "Android 10+ denies execve from app data and PRoot's static loader " +
                        "cannot be relayed through /system/bin/linker64. Ship the engine " +
                        "payload (${NativeSetup.PROOT} + ${NativeSetup.PROOT_LOADER} + .so deps) in " +
                        "app/src/main/jniLibs/$abi/ " +
                        "and rebuild (ADR-021).",
                )
            }
            val loader = NativeSetup.findPayloadFile(legacyDir, NativeSetup.PROOT_LOADER_NAMES)
            return EngineStatus(
                available = true,
                proot = legacyProot,
                loader = loader?.takeIf { NativeSetup.isMachineCompatibleElf(it, abi) },
                source = Source.LEGACY_FILES_DIR,
            )
        }

        // Nothing anywhere. The most common cause is not "the file was never added" but
        // "it was added under a name Android refuses to extract": the package manager
        // only unpacks `lib*.so` entries from the APK's lib/<abi>/ on release builds
        // (ApkParsing.cpp), so a payload named `proot`/`loader` is packaged and then
        // silently dropped. Say so, because the directory looks correct on disk.
        val strayNames = nativeLibDir
            ?.takeIf { it.isDirectory }
            ?.listFiles()
            .orEmpty()
            .filter { it.isFile && !NativeSetup.isExtractablePayloadName(it.name) }
            .map { it.name }
            .sorted()
        val extractionHint = if (strayNames.isEmpty()) {
            ""
        } else {
            " Note: ${strayNames.joinToString()} " +
                (if (strayNames.size == 1) "is" else "are") +
                " present but not named lib*.so, which Android only extracts on " +
                "debuggable builds."
        }
        return NOT_AVAILABLE.copy(
            reason = "No PRoot engine for '$abi' was found. Add the engine payload " +
                "(${NativeSetup.PROOT}, ${NativeSetup.PROOT_LOADER} and PRoot's .so " +
                "dependencies) under app/src/main/jniLibs/$abi/ — run " +
                "scripts/fetch-engine.sh — and rebuild. Payload files must be named " +
                "lib*.so or Android will not extract them from the APK.$extractionHint",
        )
    }
}
