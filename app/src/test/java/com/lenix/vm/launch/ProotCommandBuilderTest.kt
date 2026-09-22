package com.lenix.vm.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotCommandBuilderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `shell argv fakes root and binds the documented host paths`() {
        val proot = tmp.newFile("proot")
        val rootfs = tmp.newFolder("rootfs")
        val home = tmp.newFolder("home")
        val shared = tmp.newFolder("shared")
        val resolv = File(tmp.newFolder("etc"), "resolv.conf").also { it.writeText("nameserver 1.1.1.1\n") }

        val argv = ProotCommandBuilder.shell(proot, rootfs, home, resolv, shared)

        assertEquals(proot.absolutePath, argv.first())
        assertTrue(argv.contains("-0"))
        assertTrue(argv.contains("-r"))
        assertTrue(argv.contains(rootfs.absolutePath))
        assertTrue(argv.any { it.startsWith(home.absolutePath) && it.endsWith(":/root") })
        assertTrue(argv.any { it.contains("resolv.conf") })
        // Shell now uses /bin/sh -c with a ready signal for the host to detect
        assertTrue(argv.any { it.contains("/bin/sh") })
        assertTrue(argv.any { it.contains("-c") })
        assertTrue(argv.any { it.contains("__LENIX_READY__") })
        assertTrue(argv.any { it.contains("exec /bin/bash") })
    }

    @Test
    fun `desktop argv launches Xvnc on loopback and openbox`() {
        val proot = tmp.newFile("proot")
        val rootfs = tmp.newFolder("rootfs")
        val home = tmp.newFolder("home")
        val shared = tmp.newFolder("shared")
        val resolv = File(tmp.newFolder("etc"), "resolv.conf").also { it.writeText("nameserver 8.8.8.8\n") }

        val argv = ProotCommandBuilder.desktop(
            proot, rootfs, home, resolv, shared,
            vncPort = 5901,
            geometry = "1280x720",
            desktop = "openbox",
        )
        val script = argv.last()
        assertTrue(script.contains("Xvnc :1"))
        assertTrue(script.contains("-localhost"))
        assertTrue(script.contains("-rfbport 5901"))
        assertTrue(script.contains("openbox-session"))
        assertTrue(script.contains("-geometry 1280x720"))
    }

    @Test
    fun `displayFor maps RFB ports onto X displays`() {
        assertEquals(1, ProotCommandBuilder.displayFor(5901))
        assertEquals(7, ProotCommandBuilder.displayFor(5907))
        assertEquals(ProotCommandBuilder.DEFAULT_VNC_DISPLAY, ProotCommandBuilder.displayFor(80))
    }

    @Test
    fun `desktopSession maps flavors`() {
        assertEquals("openbox-session", ProotCommandBuilder.desktopSession("openbox"))
        assertEquals("lxqt-session", ProotCommandBuilder.desktopSession("lxqt"))
        assertEquals("xfce4-session", ProotCommandBuilder.desktopSession("xfce"))
    }

    @Test
    fun `engine environment pins loader and keeps tmp and library paths in app data`() {
        val loader = tmp.newFile("loader")
        val tmpDir = tmp.newFolder("proot-tmp")
        val libDir = tmp.newFolder("lib")

        val env = mutableMapOf<String, String>("EXISTING" to "1")
        ProotCommandBuilder.applyEngineEnvironment(env, loader, tmpDir, libDir)

        assertEquals(loader.absolutePath, env[com.lenix.nativebridge.NativeSetup.ENV_PROOT_LOADER])
        assertEquals(
            tmpDir.absolutePath,
            env[com.lenix.nativebridge.NativeSetup.ENV_PROOT_TMP_DIR],
        )
        assertEquals(
            libDir.absolutePath,
            env[com.lenix.nativebridge.NativeSetup.ENV_LD_LIBRARY_PATH],
        )
        assertEquals("1", env["EXISTING"])
    }

    @Test
    fun `engine environment appends to an existing library path`() {
        val libDir = tmp.newFolder("lib2")
        val env = mutableMapOf<String, String>(
            com.lenix.nativebridge.NativeSetup.ENV_LD_LIBRARY_PATH to "/old",
        )
        ProotCommandBuilder.applyEngineEnvironment(env, null, null, libDir)
        assertEquals(
            "${libDir.absolutePath}:/old",
            env[com.lenix.nativebridge.NativeSetup.ENV_LD_LIBRARY_PATH],
        )
    }
}
