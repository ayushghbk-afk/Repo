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
                // Adapted from Stryker's proven vncserver-start script:
                // - Clean environment (Android vars, DBUS leftovers)
                // - XDG vars, software GL, runtime dir
                // - Cleanup stale X locks, kill old servers
                // - D-Bus setup for XFCE/LXQt
                // - Xvnc primary, Xvfb+x11vnc fallback (Stryker uses Xvfb+x11vnc)
                // - Robust port check via /dev/tcp, not python3
                append("set -e; ")
                append("unset DBUS_SESSION_BUS_ADDRESS XAUTHORITY ICEAUTHORITY SESSION_MANAGER; ")
                append("unset ANDROID_ROOT ANDROID_DATA ANDROID_STORAGE EXTERNAL_STORAGE; ")
                append("unset GTK_PATH GIO_LAUNCHED_DESKTOP_FILE_PID GIO_LAUNCHED_DESKTOP_FILE; ")
                append("unset DESKTOP_SESSION GNOME_KEYRING_CONTROL TMPDIR TEMP TMP; ")

                append("export DISPLAY=:$display; ")
                append("export HOME=/root; export USER=root; export LOGNAME=root; ")
                append("export XDG_RUNTIME_DIR=/tmp/runtime-root; ")
                append("export XDG_CONFIG_HOME=/root/.config; ")
                append("export XDG_CACHE_HOME=/root/.cache; ")
                append("export XDG_DATA_HOME=/root/.local/share; ")
                append("export XDG_CONFIG_DIRS=/etc/xdg; ")
                append("export XDG_DATA_DIRS=/usr/local/share:/usr/share; ")
                append("export XDG_SESSION_TYPE=x11; ")
                append("export LANG=C.UTF-8; export LC_ALL=C.UTF-8; ")
                append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; ")
                append("export LIBGL_ALWAYS_SOFTWARE=1; export GALLIUM_DRIVER=llvmpipe; ")

                append("mkdir -p \$XDG_RUNTIME_DIR && chmod 700 \$XDG_RUNTIME_DIR; ")
                append("mkdir -p \$XDG_CONFIG_HOME \$XDG_CACHE_HOME \$XDG_DATA_HOME; ")
                append("mkdir -p /tmp/.X11-unix /var/run/dbus /run; ")

                // Cleanup stale state (Stryker pattern)
                append("rm -rf /tmp/.X${display}-lock /tmp/.X11-unix/X${display} /tmp/dbus-* 2>/dev/null || true; ")
                append("pkill -9 -f \"Xvnc :$display\" 2>/dev/null || true; ")
                append("pkill -9 -f \"Xvfb :$display\" 2>/dev/null || true; ")
                append("pkill -9 -f \"x11vnc.*-rfbport $vncPort\" 2>/dev/null || true; ")
                append("sleep 0.5; ")

                // D-Bus setup (required for XFCE/LXQt — Stryker does this)
                append("[ ! -s /etc/machine-id ] && dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null || true; ")
                append("[ ! -s /var/lib/dbus/machine-id ] && cp /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null || true; ")
                append("if [ -z \"\$(pidof dbus-daemon 2>/dev/null)\" ]; then dbus-daemon --system --fork 2>/dev/null || true; fi; ")
                append("rm -rf /root/.cache/sessions /root/.cache/xfce4 2>/dev/null || true; ")

                append("echo __XVNC_STARTING__ >&2; ")

                // Choose X server: Xvnc (TigerVNC) preferred, Xvfb+x11vnc fallback (Stryker's method)
                append("XSERVER=\"\"; ")
                append("if command -v Xvnc >/dev/null 2>&1; then XSERVER=\"Xvnc\"; ")
                append("elif command -v Xvfb >/dev/null 2>&1 && command -v x11vnc >/dev/null 2>&1; then XSERVER=\"Xvfb\"; ")
                append("else echo 'Neither Xvnc nor Xvfb+x11vnc found in rootfs. Install tigervnc-standalone-server or xvfb+x11vnc' >&2; exit 1; fi; ")

                // Start X server
                append("if [ \"\$XSERVER\" = \"Xvnc\" ]; then ")
                append("  Xvnc :$display -localhost -geometry $geometry -depth 24 -rfbport $vncPort -SecurityTypes None -ac +extension GLX +render -noreset >/tmp/xvnc.log 2>&1 & ")
                append("  XVNCPID=\$!; echo \$XVNCPID > /tmp/xvnc.pid; ")
                append("else ")
                append("  Xvfb :$display -screen 0 $geometry -ac +extension GLX +render -noreset >/tmp/xvfb.log 2>&1 & ")
                append("  XVFBPID=\$!; echo \$XVFBPID > /tmp/xvfb.pid; ")
                append("  sleep 1; ")
                append("  xset -display :$display s off s noblank 2>/dev/null || true; ")
                append("  xset -display :$display -dpms 2>/dev/null || true; ")
                append("  x11vnc -xkb -noxrecord -noxfixes -noxdamage -display :$display -forever -bg -rfbport $vncPort -SecurityTypes None -noshm >/tmp/x11vnc.log 2>&1 & ")
                append("  XVNCPID=\$!; echo \$XVNCPID > /tmp/xvnc.pid; ")
                append("fi; ")

                // Robust port check — Stryker uses pidof, but we also check TCP connect via /dev/tcp (bash) without python3
                append("READY=0; ")
                append("for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do ")
                append("  if ! kill -0 \$XVNCPID 2>/dev/null; then echo __XVNC_FAILED__ >&2; cat /tmp/xvnc.log /tmp/xvfb.log /tmp/x11vnc.log 2>/dev/null >&2; exit 1; fi; ")
                // Try /dev/tcp (bash builtin), fallback to ss, netstat, or simple sleep
                append("  if (echo > /dev/tcp/127.0.0.1/$vncPort) >/dev/null 2>&1; then READY=1; break; fi; ")
                append("  if command -v ss >/dev/null 2>&1 && ss -tln 2>/dev/null | grep -q \":$vncPort\"; then READY=1; break; fi; ")
                append("  if command -v netstat >/dev/null 2>&1 && netstat -tln 2>/dev/null | grep -q \":$vncPort\"; then READY=1; break; fi; ")
                append("  sleep 0.25; ")
                append("done; ")
                append("if [ \$READY -ne 1 ]; then echo __XVNC_PORT_FAILED__ port=$vncPort >&2; cat /tmp/xvnc.log /tmp/xvfb.log /tmp/x11vnc.log 2>/dev/null >&2; exit 1; fi; ")
                append("echo __DESKTOP_READY__ display=:$display port=$vncPort geometry=$geometry >&2; ")

                // Start desktop session — use dbus-run-session if available (Stryker pattern)
                append("if command -v dbus-run-session >/dev/null 2>&1; then ")
                append("  dbus-run-session -- $session >/tmp/session.log 2>&1 & ")
                append("else ")
                append("  $session >/tmp/session.log 2>&1 & ")
                append("fi; ")
                append("SESSIONPID=\$!; echo \$SESSIONPID > /tmp/session.pid; ")
                append("touch /run/pvm-ready 2>/dev/null || true; ")
                append("touch /tmp/.lenix-desktop-ready 2>/dev/null || true; ")
                append("echo \"Lenix desktop: DISPLAY=:$display PORT=$vncPort SESSION=$session PID=\$SESSIONPID\" >&2; ")
                append("wait \$XVNCPID || true")
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
