package com.lenix.vm.launch

import com.lenix.nativebridge.NativeSetup
import java.io.File

/** Builds the PRoot argv for a guest (ARCHITECTURE.md §7.2, ADR-001 / ADR-006).

  * GPL `proot` is executed as a separate process, never linked. `-0` only fakes
  * uid 0 inside the emulated view. Bind mounts are the documented host paths —
  * never `/sdcard`.
  */
object ProotCommandBuilder {

    const val DEFAULT_GEOMETRY = "1280x720"
    const val DEFAULT_DESKTOP = "openbox"
    const val DEFAULT_VNC_DISPLAY = 1
    const val DISPLAY_BASE_PORT = 5900

    fun shell(
        proot: File,
        rootfs: File,
        home: File,
        resolv: File,
        shared: File,
        workDir: String = "/root",
    ): List<String> = base(proot, rootfs, home, resolv, shared, workDir) + listOf(
        "/bin/sh", "-c",
        // Signal the host that the shell started successfully, then exec bash.
        // The host waits for __LENIX_READY__ before marking the instance RUNNING
        // (see GuestRuntime.waitForShellReady).
        "echo __LENIX_READY__ >&2; exec /bin/bash -l 2>&1",
    )

    fun desktop(
        proot: File,
        rootfs: File,
        home: File,
        resolv: File,
        shared: File,
        vncPort: Int,
        geometry: String = DEFAULT_GEOMETRY,
        desktop: String = DEFAULT_DESKTOP,
        tini: File? = null,
    ): List<String> {
        val display = displayFor(vncPort)
        val session = desktopSession(desktop)
        val inner = listOf(
            "/bin/sh", "-c",
            buildString {
                append("export DISPLAY=:$display; ")
                append("export HOME=/root; ")
                append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; ")
                append("echo __XVNC_STARTING__ >&2; ")
                append("if ! command -v Xvnc >/dev/null 2>&1; then echo 'Xvnc is not installed in the rootfs' >&2; exit 1; fi; ")
                append("Xvnc :$display -localhost -geometry $geometry -depth 24 ")
                append("-rfbport $vncPort -SecurityTypes None >/tmp/xvnc.log 2>&1 & ")
                append("XVNCPID=\$!; ")
                append("echo \$XVNCPID > /tmp/xvnc.pid; ")
                append("READY=0; ")
                // Check both liveness and the actual listener. Do not report a desktop ready
                // merely because the Xvnc child briefly existed.
                append("for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do ")
                append("  if ! kill -0 \$XVNCPID 2>/dev/null; then echo __XVNC_FAILED__ >&2; cat /tmp/xvnc.log >&2; exit 1; fi; ")
                append("  if python3 -c \"import socket; s=socket.create_connection(('127.0.0.1',$vncPort),.5); s.close()\" 2>/dev/null; then READY=1; break; fi; ")
                append("  sleep 0.25; ")
                append("done; ")
                append("if [ \$READY -ne 1 ]; then echo __XVNC_PORT_FAILED__ >&2; cat /tmp/xvnc.log >&2; exit 1; fi; ")
                append("echo __DESKTOP_READY__ >&2; ")
                append("$session >/tmp/session.log 2>&1 & ")
                append("touch /run/pvm-ready 2>/dev/null || true; ")
                append("wait")
            },
        )
        val afterTini = if (tini != null && tini.isFile) {
            listOf(tini.absolutePath, "-s", "--") + inner
        } else {
            inner
        }
        return base(proot, rootfs, home, resolv, shared, "/root") + afterTini
    }

    fun displayFor(vncPort: Int): Int {
        val display = vncPort - DISPLAY_BASE_PORT
        return if (display in 1..99) display else DEFAULT_VNC_DISPLAY
    }

    fun desktopSession(desktop: String): String = when (desktop.lowercase()) {
        "lxqt" -> "lxqt-session"
        "xfce", "xfce4" -> "xfce4-session"
        else -> "openbox-session"
    }

    /** Environment for the PRoot host process (ADR-021).

      * - `PROOT_LOADER`: pin PRoot's static loader to the APK payload so it never extracts
      *   + execs one from app temp (denied by SELinux on Android 10+).
      * - `PROOT_TMP_DIR`/`TMPDIR`: PRoot's scratch space (never exec'd — safe in filesDir).
      * - `LD_LIBRARY_PATH`: PRoot is a bionic binary; its `.so` deps (libtalloc, etc.)
      *   ship beside the engine binary in the payload dir.
      */
    fun applyEngineEnvironment(
        environment: MutableMap<String, String>,
        loader: File?,
        tmpDir: File?,
        libDir: File?,
    ) {
        if (loader != null && loader.isFile) {
            environment[NativeSetup.ENV_PROOT_LOADER] = loader.absolutePath
        }
        if (tmpDir != null) {
            environment[NativeSetup.ENV_PROOT_TMP_DIR] = tmpDir.absolutePath
            environment[NativeSetup.ENV_TMPDIR] = tmpDir.absolutePath
        }
        if (libDir != null) {
            val existing = environment[NativeSetup.ENV_LD_LIBRARY_PATH]
            environment[NativeSetup.ENV_LD_LIBRARY_PATH] =
                if (existing.isNullOrBlank()) libDir.absolutePath
                else "${libDir.absolutePath}:$existing"
        }
    }

    private fun base(
        proot: File,
        rootfs: File,
        home: File,
        resolv: File,
        shared: File,
        workDir: String,
    ): List<String> {
        val argv = mutableListOf(
            proot.absolutePath,
            "-r", rootfs.absolutePath,
            "-0",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${home.absolutePath}:/root",
            "-w", workDir,
        )
        if (resolv.isFile) {
            argv += listOf("-b", "${resolv.absolutePath}:/etc/resolv.conf")
        }
        if (shared.exists() || shared.mkdirs()) {
            argv += listOf("-b", "${shared.absolutePath}:/shared")
        }
        return argv
    }
}
