package com.lenix.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeSetupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- ELF probing ------------------------------------------------------------

    @Test
    fun `probe rejects shell scripts and text files`() {
        val script = tmp.newFile("proot.sh")
        script.writeText("#!/system/bin/sh\necho hi\n")
        assertEquals(NativeSetup.ElfProbe(false, 0, 0, null), NativeSetup.probe(script))
        assertFalse(NativeSetup.isElf(script))
    }

    @Test
    fun `probe reads ELF class, machine and PT_INTERP`() {
        val bin = tmp.newFolder("elf").let { File(it, "proot") }
        bin.writeBytes(bionicElf(machine = NativeSetup.EM_AARCH64, interp = "/system/bin/linker64"))

        val info = NativeSetup.probe(bin)

        assertTrue(info.isElf)
        assertEquals(64, info.bits)
        assertEquals(NativeSetup.EM_AARCH64, info.machine)
        assertEquals("/system/bin/linker64", info.interp)
    }

    @Test
    fun `isBionicExecutable requires matching machine and Android linker`() {
        val bionic = tmp.newFolder("bionic").let { File(it, "proot") }
        bionic.writeBytes(bionicElf(NativeSetup.EM_AARCH64, "/system/bin/linker64"))
        val static = tmp.newFolder("static").let { File(it, "loader") }
        static.writeBytes(staticElf(NativeSetup.EM_AARCH64))
        val foreign = tmp.newFolder("foreign").let { File(it, "proot") }
        foreign.writeBytes(bionicElf(NativeSetup.EM_X86_64, "/system/bin/linker64"))
        val glibc = tmp.newFolder("glibc").let { File(it, "proot") }
        glibc.writeBytes(bionicElf(NativeSetup.EM_AARCH64, "/lib64/ld-linux-aarch64.so.1"))

        assertTrue(NativeSetup.isBionicExecutable(bionic, "arm64-v8a"))
        assertFalse(NativeSetup.isBionicExecutable(static, "arm64-v8a"))
        assertFalse(NativeSetup.isBionicExecutable(foreign, "arm64-v8a"))
        assertFalse(NativeSetup.isBionicExecutable(glibc, "arm64-v8a"))
    }

    @Test
    fun `isMachineCompatibleElf accepts static and bionic engines of this ABI`() {
        val static = tmp.newFolder("static2").let { File(it, "loader") }
        static.writeBytes(staticElf(NativeSetup.EM_AARCH64))
        val wrong = tmp.newFolder("wrong").let { File(it, "proot") }
        wrong.writeBytes(staticElf(NativeSetup.EM_ARM))

        assertTrue(NativeSetup.isMachineCompatibleElf(static, "arm64-v8a"))
        assertFalse(NativeSetup.isMachineCompatibleElf(wrong, "arm64-v8a"))
    }

    // ---- Engine resolution ------------------------------------------------------

    @Test
    fun `ensureEngine prefers the signed APK payload over filesDir`() {
        val files = tmp.newFolder("files")
        val payload = tmp.newFolder("payload")
        payload.resolve(NativeSetup.PROOT).writeBytes(
            bionicElf(NativeSetup.EM_AARCH64, "/system/bin/linker64"),
        )
        payload.resolve(NativeSetup.PROOT_LOADER).writeBytes(staticElf(NativeSetup.EM_AARCH64))
        writeBionicDeps(payload)
        // A legacy copy that must NOT win.
        val legacy = NativeSetup.nativeDir(files)
        legacy.mkdirs()
        legacy.resolve(NativeSetup.PROOT).writeBytes(staticElf(NativeSetup.EM_AARCH64))

        val status = EngineInstaller.ensureEngine(files, "arm64-v8a", payload, isAndroid = true)

        assertTrue(status.available)
        assertEquals(EngineInstaller.Source.APK_PAYLOAD, status.source)
        assertEquals(File(payload, NativeSetup.PROOT), status.proot)
        assertNotNull(status.loader)
        assertNull(status.reason)
    }

    @Test
    fun `payload without loader is still available but reports the missing loader`() {
        val files = tmp.newFolder("files2")
        val payload = tmp.newFolder("payload2")
        payload.resolve(NativeSetup.PROOT).writeBytes(
            bionicElf(NativeSetup.EM_AARCH64, "/system/bin/linker64"),
        )
        writeBionicDeps(payload)

        val status = EngineInstaller.ensureEngine(files, "arm64-v8a", payload)

        assertTrue(status.available)
        assertEquals(EngineInstaller.Source.APK_PAYLOAD, status.source)
        assertNull(status.loader)
        assertTrue(status.reason!!.contains("loader"))
    }

    @Test
    fun `ensureEngine reports missing engine with actionable reason`() {
        val files = tmp.newFolder("files3")
        val payload = tmp.newFolder("payload3")

        val status = EngineInstaller.ensureEngine(files, "arm64-v8a", payload)

        assertFalse(status.available)
        assertTrue(status.reason!!.contains("jniLibs/arm64-v8a"))
    }

    @Test
    fun `ensureEngine rejects legacy filesDir on Android but accepts it on the JVM`() {
        val files = tmp.newFolder("files4")
        val legacy = NativeSetup.nativeDir(files)
        legacy.mkdirs()
        legacy.resolve(NativeSetup.PROOT).writeBytes(
            bionicElf(NativeSetup.EM_AARCH64, "/system/bin/linker64"),
        )

        val onDevice = EngineInstaller.ensureEngine(files, "arm64-v8a", null, isAndroid = true)
        assertFalse(onDevice.available)
        assertTrue(onDevice.reason!!.contains("jniLibs/arm64-v8a"))

        val onJvm = EngineInstaller.ensureEngine(files, "arm64-v8a", null, isAndroid = false)
        assertTrue(onJvm.available)
        assertEquals(EngineInstaller.Source.LEGACY_FILES_DIR, onJvm.source)
        assertNull(onJvm.reason)
    }

    @Test
    fun `ensureEngine rejects a non-ELF payload with an actionable reason`() {
        val files = tmp.newFolder("files5")
        val payload = tmp.newFolder("payload5")
        payload.resolve(NativeSetup.PROOT).writeText("#!/system/bin/sh\n")

        val status = EngineInstaller.ensureEngine(files, "arm64-v8a", payload)

        assertFalse(status.available)
        assertTrue(status.reason!!.contains("not an ELF"))
    }

    @Test
    fun `ensureEngine rejects a payload for a different architecture`() {
        val files = tmp.newFolder("files6")
        val payload = tmp.newFolder("payload6")
        payload.resolve(NativeSetup.PROOT).writeBytes(
            bionicElf(NativeSetup.EM_X86_64, "/system/bin/linker64"),
        )

        val status = EngineInstaller.ensureEngine(files, "arm64-v8a", payload)

        assertFalse(status.available)
        assertTrue(status.reason!!.contains("different architecture"))
    }

    @Test
    fun `ensureEngine rejects a static legacy engine on Android`() {
        val files = tmp.newFolder("files7")
        val legacy = NativeSetup.nativeDir(files)
        legacy.mkdirs()
        legacy.resolve(NativeSetup.PROOT).writeBytes(staticElf(NativeSetup.EM_AARCH64))

        val status = EngineInstaller.ensureEngine(files, "arm64-v8a", null, isAndroid = true)

        assertFalse(status.available)
        assertTrue(status.reason!!.contains("cannot be relayed"))
    }

    @Test
    fun `isReady is false when a valid payload reports a caveat`() {
        val files = tmp.newFolder("files_ready")
        val payload = tmp.newFolder("payload_ready")
        payload.resolve(NativeSetup.PROOT).writeBytes(
            bionicElf(NativeSetup.EM_AARCH64, "/system/bin/linker64"),
        )

        // Fully stocked: proot, loader and bionic deps -> ready.
        payload.resolve(NativeSetup.PROOT_LOADER).writeBytes(staticElf(NativeSetup.EM_AARCH64))
        writeBionicDeps(payload)
        assertTrue(EngineInstaller.ensureEngine(files, "arm64-v8a", payload).isReady)

        // Missing loader -> available still true, but not ready.
        payload.resolve(NativeSetup.PROOT_LOADER).delete()
        val status = EngineInstaller.ensureEngine(files, "arm64-v8a", payload)
        assertTrue(status.available)
        assertFalse(status.isReady)
        assertTrue(status.reason!!.contains("loader"))
    }

    @Test
    fun `isReady is false when bionic dependencies are missing`() {
        val files = tmp.newFolder("files_deps")
        val payload = tmp.newFolder("payload_deps")
        payload.resolve(NativeSetup.PROOT).writeBytes(
            bionicElf(NativeSetup.EM_AARCH64, "/system/bin/linker64"),
        )
        payload.resolve(NativeSetup.PROOT_LOADER).writeBytes(staticElf(NativeSetup.EM_AARCH64))

        val status = EngineInstaller.ensureEngine(files, "arm64-v8a", payload)

        assertFalse(status.isReady)
        assertTrue(status.reason!!.contains(NativeSetup.LIB_TALLOC))
        assertTrue(status.reason!!.contains(NativeSetup.LIB_ANDROID_SHMEM))
    }

    @Test
    fun `static proot needs no bionic shared libraries`() {
        val files = tmp.newFolder("files_static")
        val payload = tmp.newFolder("payload_static")
        payload.resolve(NativeSetup.PROOT).writeBytes(staticElf(NativeSetup.EM_AARCH64))
        payload.resolve(NativeSetup.PROOT_LOADER).writeBytes(staticElf(NativeSetup.EM_AARCH64))

        val status = EngineInstaller.ensureEngine(files, "arm64-v8a", payload)

        assertTrue(status.isReady)
        assertTrue(status.available)
        assertNull(status.reason)
    }

    // ---- APK extraction filter (ApkParsing.cpp / ValidLibraryPathLastSlash) ------

    @Test
    fun `every engine payload name survives Android's lib extraction filter`() {
        // Android only extracts lib*.so entries from the APK's lib/<abi>/ on release
        // builds, so a payload named `proot` or `libtalloc.so.2` never reaches the
        // device. Every canonical name we ship must pass.
        val shipped = listOf(
            NativeSetup.PROOT,
            NativeSetup.PROOT_LOADER,
            NativeSetup.TINI,
            NativeSetup.BUSYBOX,
        ) + NativeSetup.BIONIC_DEPS.map { it.canonical }

        shipped.forEach { name ->
            assertTrue(
                "$name would not be extracted by Android",
                NativeSetup.isExtractablePayloadName(name),
            )
        }
    }

    @Test
    fun `isExtractablePayloadName rejects the names that silently fail on device`() {
        assertFalse(NativeSetup.isExtractablePayloadName("proot"))
        assertFalse(NativeSetup.isExtractablePayloadName("loader"))
        assertFalse(NativeSetup.isExtractablePayloadName("tini"))
        // Versioned sonames fail too: the name must END with ".so".
        assertFalse(NativeSetup.isExtractablePayloadName("libtalloc.so.2"))
        // Unsafe characters are dropped by isFilenameSafe().
        assertFalse(NativeSetup.isExtractablePayloadName("lib proot.so"))
        assertTrue(NativeSetup.isExtractablePayloadName("libproot.so"))
    }

    @Test
    fun `ensureEngine still accepts a legacy dev payload using the old names`() {
        val files = tmp.newFolder("files_legacy_names")
        val payload = tmp.newFolder("payload_legacy_names")
        // A debug build (or an old checkout) extracts these unprefixed names.
        payload.resolve("proot").writeBytes(
            bionicElf(NativeSetup.EM_AARCH64, "/system/bin/linker64"),
        )
        payload.resolve("loader").writeBytes(staticElf(NativeSetup.EM_AARCH64))
        payload.resolve("libtalloc.so.2").writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        payload.resolve(NativeSetup.LIB_ANDROID_SHMEM).writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))

        val status = EngineInstaller.ensureEngine(files, "arm64-v8a", payload)

        assertTrue(status.isReady)
        assertEquals(File(payload, "proot"), status.proot)
        assertEquals(File(payload, "loader"), status.loader)
    }

    @Test
    fun `missing engine explains that stray files were not named lib so`() {
        val files = tmp.newFolder("files_stray")
        val payload = tmp.newFolder("payload_stray")
        // Present on disk in the repo, but Android never extracted them: not lib*.so.
        payload.resolve("README.txt").writeText("engine goes here")

        val status = EngineInstaller.ensureEngine(files, "arm64-v8a", payload)

        assertFalse(status.available)
        assertTrue(status.reason!!.contains("lib*.so"))
        assertTrue(status.reason!!.contains("README.txt"))
    }

    private fun writeBionicDeps(payload: File) {
        payload.resolve(NativeSetup.LIB_TALLOC).writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        payload.resolve(NativeSetup.LIB_ANDROID_SHMEM).writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
    }

    // ---- synthetic ELF builders -------------------------------------------------

    private fun bionicElf(machine: Int, interp: String): ByteArray =
        elf(machine, PT_INTERP = interp)

    private fun staticElf(machine: Int): ByteArray = elf(machine, PT_INTERP = null)

    private fun elf(machine: Int, PT_INTERP: String?): ByteArray {
        val header = ByteArray(64)
        header[0] = 0x7f
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2 // ELFCLASS64
        header[5] = 1 // ELFDATA2LSB
        putU16(header, 18, machine)
        putU32(header, 32, 64) // e_phoff
        putU16(header, 52, 64) // e_ehsize
        putU16(header, 54, 56) // e_phentsize
        putU16(header, 56, if (PT_INTERP == null) 0 else 1) // e_phnum
        if (PT_INTERP == null) return header

        val interpBytes = PT_INTERP.toByteArray() + byteArrayOf(0)
        val phdr = ByteArray(56)
        putU32(phdr, 0, 3) // PT_INTERP
        putU64(phdr, 8, 64L + 56L) // p_offset
        putU64(phdr, 32, interpBytes.size.toLong()) // p_filesz
        return header + phdr + interpBytes
    }

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v shr 8) and 0xff).toByte()
    }

    private fun putU32(b: ByteArray, off: Int, v: Int) {
        for (i in 0 until 4) b[off + i] = ((v shr (8 * i)) and 0xff).toByte()
    }

    private fun putU64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v shr (8 * i)) and 0xff).toByte()
    }
}
