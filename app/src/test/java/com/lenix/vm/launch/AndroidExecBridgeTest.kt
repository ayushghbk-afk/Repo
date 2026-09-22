package com.lenix.vm.launch

import com.lenix.nativebridge.NativeSetup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AndroidExecBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `non-Android platforms exec directly`() {
        val proot = File(tmp.root, "proot")
        proot.writeBytes(bionicElf())

        val argv = listOf(proot.absolutePath, "-r", "/rootfs", "-0")
        assertEquals(argv, AndroidExecBridge.resolve(argv, "arm64-v8a", isAndroid = false))
    }

    @Test
    fun `Android wraps a bionic engine from app data with the system linker`() {
        val proot = File(tmp.root, "proot")
        proot.writeBytes(bionicElf())

        val argv = listOf(proot.absolutePath, "-r", "/rootfs", "-0")
        val wrapped = AndroidExecBridge.resolve(
            argv,
            "arm64-v8a",
            isAndroid = true,
            isRestricted = { true },
            linkerAvailable = { true },
        )

        assertEquals(
            listOf("/system/bin/linker64", proot.absolutePath, "-r", "/rootfs", "-0"),
            wrapped,
        )
    }

    @Test
    fun `Android does not wrap payload or system executables`() {
        val proot = File(tmp.root, "proot")
        proot.writeBytes(bionicElf())
        val argv = listOf(proot.absolutePath, "-r")

        // Signed APK payload (apk_data_file) — direct exec is allowed.
        assertEquals(
            argv,
            AndroidExecBridge.resolve(
                argv,
                "arm64-v8a",
                isAndroid = true,
                isRestricted = { false },
                linkerAvailable = { true },
            ),
        )
        // System binaries — direct exec is allowed.
        assertEquals(
            listOf("/system/bin/sh", "-c", "true"),
            AndroidExecBridge.resolve(
                listOf("/system/bin/sh", "-c", "true"),
                "arm64-v8a",
                isAndroid = true,
                isRestricted = { true },
                linkerAvailable = { true },
            ),
        )
    }

    @Test
    fun `Android leaves static and non-ELF engines alone`() {
        val static = File(tmp.root, "loader")
        static.writeBytes(staticElf())
        val script = File(tmp.root, "script.sh")
        script.writeText("#!/system/bin/sh\n")

        val staticArgv = listOf(static.absolutePath)
        assertEquals(
            staticArgv,
            AndroidExecBridge.resolve(
                staticArgv,
                "arm64-v8a",
                isAndroid = true,
                isRestricted = { true },
                linkerAvailable = { true },
            ),
        )
        val scriptArgv = listOf(script.absolutePath)
        assertEquals(
            scriptArgv,
            AndroidExecBridge.resolve(
                scriptArgv,
                "arm64-v8a",
                isAndroid = true,
                isRestricted = { true },
                linkerAvailable = { true },
            ),
        )
    }

    private fun bionicElf(): ByteArray = elf("/system/bin/linker64")

    private fun staticElf(): ByteArray = elf(null)

    private fun elf(interp: String?): ByteArray {
        val header = ByteArray(64)
        header[0] = 0x7f
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2 // ELFCLASS64
        header[5] = 1 // ELFDATA2LSB
        putU16(header, 18, NativeSetup.EM_AARCH64)
        putU32(header, 32, 64) // e_phoff
        putU16(header, 54, 56) // e_phentsize
        putU16(header, 56, if (interp == null) 0 else 1)
        if (interp == null) return header

        val interpBytes = interp.toByteArray() + byteArrayOf(0)
        val phdr = ByteArray(56)
        putU32(phdr, 0, 3) // PT_INTERP
        putU64(phdr, 8, 64L + 56L)
        putU64(phdr, 32, interpBytes.size.toLong())
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
