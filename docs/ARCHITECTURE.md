# Lenix — Architecture & Project Structure

> **Document status: historical design record, superseded by the implementation.**
> Written at Phase 0 under the codename "PocketVM" and kept as the design rationale
> for the current code. The project has since shipped **Phases 1–7** (Compose UI,
> instance manager, resumable RootFS downloader, signed manifests, streaming
> extraction, PRoot engine + terminal, Openbox desktop + VNC) — see the README for
> the current state and `docs/DECISIONS.md` for the ADRs that supersede the open
> questions below. Package/class names in this document may predate the rename to
> `com.lenix`; the source tree is authoritative where they differ.

---

## 1. Goals

1. **One APK.** A user installs the app and never touches Termux, a package manager,
   or a VNC viewer.
2. **No root.** PRoot-style ptrace userspace isolation; no kernel modules, no loop
   devices, no `/system` modification.
3. **Real Linux userspace.** glibc/musl rootfs (Ubuntu, Debian, Alpine), a real
   package manager (`apt`/`apk`) inside the guest, real processes.
4. **Desktop inside the app.** Xvnc serves RFB on `127.0.0.1` only; a bundled RFB
   client renders directly to an Android `Surface`/Compose canvas.
5. **App is a VM manager.** Install / delete / clone / backup / restore / storage
   control per instance, with per-instance `config.json`.
6. **First user-visible promise:** *Install Ubuntu → press Start → an Ubuntu desktop
   opens.* On a modern 8 GB phone this must feel usable; on 3–4 GB phones it must still
   boot.

## 2. Constraints (design drivers)

| Constraint | Consequence |
|---|---|
| No root | No chroot, no mount(2), no overlayfs, no loop devices → PRoot ptrace interception; kernels appear as files |
| Android app sandbox | Everything lives under the app's private dir; network is shared with Android; no netns |
| No `/proc` from host | PRoot emulates `/proc` partial views; guest `ps`/`/proc` works via proot's emulation |
| Android 10+ W^X | SELinux denies `execve` of `app_data_file` (`execute_no_trans`); engine binaries must ship as APK native payloads (`jniLibs/<abi>/` → `/data/app/.../lib/<abi>/`, still `x_file_perms`) and PRoot's loader is pinned there — ADR-021 |
| SELinux | `ptrace` of own children is allowed in the app domain (same uid) — Termux proves this works; some OEMs are stricter (see Risks §15) |
| Foreground limits | A running Linux instance keeps the app in a foreground service |
| Play policy | User-installable Linux environments are generally allowed, but `specialUse` FGS + clear disclosure is required; sideloading is the primary channel for v1 |
| FUSE `/sdcard` is slow | RootFS and instances live on internal `/data` (app private), never on shared storage |

## 3. System overview

```
┌────────────────────────────────────────────────────────────────────────┐
│  PocketVM APK (one process tree, app uid)                               │
│                                                                        │
│  ┌────────────────────────────┐        ┌────────────────────────────┐  │
│  │ Jetpack Compose UI         │        │ RFB Client (pure Kotlin)   │  │
│  │ Home · Installer · Desktop │◄──────►│ 127.0.0.1:5901 (loopback)  │  │
│  │ Toolbar · Settings         │        │ Tight/Hextile decode →     │  │
│  └──────────┬─────────────────┘        │ Bitmap → SurfaceView      │  │
│             │                          └────────────┬───────────────┘  │
│  ┌──────────▼─────────────────┐                     │                  │
│  │ VM Manager (Kotlin)        │                     │                  │
│  │ InstanceStore · Tasks ·    │                     │                  │
│  │ Launcher · ForegroundSvc   │                     │                  │
│  └──────────┬─────────────────┘                     │                  │
│             │ JNI / ProcessBuilder                  │                  │
│  ┌──────────▼───────────────────────────────────────▼──────────────┐   │
│  │ Native Engine (app-private dir, bionic/musl-static)             │   │
│  │  pvmextract (libarchive+zstd) · proot-rs · tini · busybox       │   │
│  │  qemu-<arch>-static (cross-arch only) · pty helper (openpty)    │   │
│  └───────────────────────────────┬─────────────────────────────────┘   │
│                                  │ fork/exec, ptrace guest             │
│  ┌───────────────────────────────▼─────────────────────────────────┐   │
│  │ PRoot Linux userspace (guest rootfs, glibc, runs as app uid)    │   │
│  │   tini (pid 1)                                                 │   │
│  │   dbus · pulseaudio (optional)                                 │   │
│  │   Xvnc (TigerVNC, -localhost, per-instance password)            │   │
│  │   openbox / LXQt / XFCE session + xterm/xfce4-terminal          │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

Key property: **the RFB connection never leaves the device**. Both endpoints are in
the same app process tree — the client connects to `127.0.0.1` from the app, and the
server binds loopback inside the guest (which shares the device loopback). A guest
process *can* reach the VNC port, but only with the per-instance password that is
generated at boot and never logged.

## 4. Repository layout (target)

```
lenix/
├── README.md
├── docs/
│   ├── ARCHITECTURE.md            ← this file
│   ├── NATIVE_BINARIES.md         ← exact binary/module inventory
│   ├── ROOTFS_SYSTEM.md           ← image format + install pipeline
│   └── DECISIONS.md               ← ADR-style log (start at Phase 1)
│
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── gradlew, gradle/
│
├── app/                                   # Android app (Compose)
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/pocketvm/
│       │   ├── PocketVmApp.kt              # Application: DI graph, native setup
│       │   ├── MainActivity.kt
│       │   ├── ui/
│       │   │   ├── home/                   # instance list, create/delete
│       │   │   │   ├── HomeScreen.kt
│       │   │   │   └── HomeViewModel.kt
│       │   │   ├── installer/              # distro picker, progress, logs
│       │   │   │   ├── InstallerScreen.kt
│       │   │   │   ├── InstallerViewModel.kt
│       │   │   │   └── DistroCatalog.kt
│       │   │   ├── desktop/                # RFB canvas + touch overlay
│       │   │   │   ├── DesktopScreen.kt
│       │   │   │   ├── DesktopViewModel.kt
│       │   │   │   ├── RfbSurfaceView.kt
│       │   │   │   └── Toolbar.kt
│       │   │   ├── terminal/               # Phase 1 built-in terminal
│       │   │   └── settings/
│       │   ├── vm/
│       │   │   ├── instance/               # InstanceStore, config.json codec
│       │   │   ├── install/                # InstallTask, DownloadTask, ExtractTask
│       │   │   ├── launch/                 # Launcher, GuestProcess, VncPortAllocator
│       │   │   └── service/                # VmRuntimeService (foreground)
│       │   ├── vnc/                        # pure-Kotlin RFB 3.8 client
│       │   │   ├── RfbClient.kt            # handshake, security, messages
│       │   │   ├── Decoder.kt              # Raw/Hextile/Tight/ZRLE
│       │   │   ├── TightDecoder.kt         # zlib(Inflater) + JPEG(BitmapFactory)
│       │   │   ├── Framebuffer.kt          # IntArray/ByteArray → Bitmap
│       │   │   └── InputMapper.kt          # touch → pointer/keyboard
│       │   ├── nativebridge/               # JNI surface (thin, safe)
│       │   │   ├── Extractor.kt
│       │   │   ├── Pty.kt
│       │   │   ├── NativeSetup.kt          # ELF probe + engine validation
│       │   │   └── NativeLibs.kt
│       │   ├── data/
│       │   │   ├── distro/                 # catalog repository, update channel
│       │   │   ├── download/               # ResumableDownloader (OkHttp, Range)
│       │   │   └── backup/                 # SAF export/import (libarchive tar)
│       │   └── util/
│       └── res/
│
├── native/                                # NDK / Rust code
│   ├── CMakeLists.txt                     # top-level
│   ├── extractor/                         # C++: libarchive + zstd, JNI entry
│   │   ├── extractor.cpp
│   │   └── progress.hpp
│   ├── pty/                               # C++: openpty(3) → ParcelFileDescriptor
│   ├── launcher/                          # C++: posix_spawn wrapper, proc mgmt
│   ├── third_party/
│   │   ├── proot-rs/                      # vendored (GPL-2.0)
│   │   ├── tini/                          # vendored (MIT), android patch
│   │   ├── busybox/                       # vendored (GPL-2.0), termux config
│   │   ├── libarchive/                    # vendored (BSD)
│   │   ├── zstd/                          # vendored (BSD/GPL dual)
│   │   ├── qemu/                          # qemu-*-static (only if cross-arch)
│   │   └── libjpeg-turbo/                 # only if native Tight JPEG path chosen
│   └── rust/
│       ├── Cargo.toml                     # cargo-ndk workspace
│       └── proot-runner/                  # thin Rust wrapper (optional)
│
├── dist/                                  # rootfs images + native packages
│   ├── rootfs/
│   │   ├── builder/                       # Dockerfiles + build scripts
│   │   │   ├── Dockerfile.debian-arm64
│   │   │   ├── Dockerfile.ubuntu-arm64
│   │   │   ├── build-base.sh              # mmdebstrap / debootstrap / apk
│   │   │   ├── build-desktop.sh           # layer: Xvnc + DE + fonts
│   │   │   └── sign.sh                    # ed25519 (minisign) over manifest
│   │   ├── manifests/                     # per-distro manifest.json (canonical)
│   │   └── layers/                        # *.tar.zst + *.sha256 (release assets, git-lfs)
│   └── native-pkgs/                       # per-ABI zips: native-<abi>-<ver>.zip
│
├── tools/
│   ├── fetch-deps.sh                      # clone/pin third_party at exact revisions
│   ├── pvm                                # CLI smoke harness (ADB)
│   │   ├── install.sh
│   │   ├── launch.sh
│   │   └── probe.sh                       # uname/apt/xvnc/framebuffer assertions
│   └── release.sh                         # assemble + sign + publish
│
├── .github/workflows/
│   ├── native.yml                         # NDK + cargo-ndk builds per ABI
│   ├── rootfs.yml                         # build+sign layers (arm64 runners)
│   ├── apk.yml                            # assemble, lint, test, upload
│   ├── smoke.yml                          # emulator instrumented smoke test
│   └── release.yml                        # tag → APK + layers + native zips
│
└── .gitattributes (git-lfs for dist/layers) · .gitignore
```

## 5. Android app architecture (Kotlin + Compose)

### 5.1 Gradle modules

We start **single-module** (`:app`) with clear package boundaries; split into
`:core:data`, `:core:vm`, `:core:vnc` when the module graph demands it. Single module
keeps Phase 1 velocity; package boundaries enforce the same seams.

| Package | Responsibility | Key types |
|---|---|---|
| `ui.home` | Instance card list, storage bar, actions | `HomeScreen`, `HomeViewModel` |
| `ui.installer` | Distro picker → download → extract → post-install | `InstallerScreen`, `InstallerViewModel`, `DistroCatalog` |
| `ui.desktop` | RFB canvas, touch toolbar, fullscreen | `DesktopScreen`, `RfbSurfaceView`, `Toolbar` |
| `ui.terminal` | Phase 1 shell (pty-backed) | `TerminalScreen`, `TerminalEmulator` |
| `vm.instance` | Instance model + `config.json` persistence | `Instance`, `InstanceStore` (JSON, atomic writes) |
| `vm.install` | State machine steps, task runners | `InstallTask`, `DownloadTask`, `ExtractTask` |
| `vm.launch` | Guest process spawn/kill, readiness probe | `Launcher`, `GuestProcess`, `VncPortAllocator` |
| `vm.service` | Foreground service for running guest | `VmRuntimeService` |
| `vnc` | RFB 3.8 client, decoders, input mapping | `RfbClient`, `Decoder`, `Framebuffer`, `InputMapper` |
| `nativebridge` | JNI wrappers + first-run binary bootstrap | `Extractor`, `Pty`, `NativeSetup` |
| `data.distro` | Manifests from the update channel | `DistroRepository`, `ManifestFetcher` |
| `data.download` | Resumable HTTP downloads to `.part` files | `ResumableDownloader` |
| `data.backup` | SAF export/import of instance tarballs | `InstanceBackuper` |

### 5.2 Threading model

- **UI:** Compose, main thread.
- **VM/tasks:** a single `ExecutorService` (or `Dispatchers.IO`) per instance operation;
  cancellable Jobs; every long op persists its state before yielding the thread so a
  process kill is resumable (§11).
- **Network:** OkHttp on its own pool; `ResumableDownloader` writes to `cache/<sha>.part`.
- **Guest process:** owned by `GuestProcess` (a `Process` handle + process group); SIGTERM →
  graceful `pvm-entry` shutdown → SIGKILL after 10 s timeout.
- **RFB:** one reader thread (socket → framebuffer), one renderer (SurfaceView),
  input on the UI thread posting to the VNC socket.

### 5.3 Native engine bootstrap

The engine binaries ship as **native library payloads**, never as assets copied to
`filesDir` (ADR-021). On Android 10+, `execve()` of an `app_data_file` (anything under
the app's own data dir) is denied by SELinux — `neverallow { all_untrusted_apps }
{ app_data_file privapp_data_file }:file execute_no_trans` (AOSP `0dd738d8`) — so
`chmod 0700` cannot rescue a `filesDir` copy; exactly `error=13, Permission denied`.

```
app/src/main/jniLibs/<abi>/{libproot.so, libprootloader.so, libtini.so, libtalloc.so, libandroid-shmem.so}
        │ packaged verbatim at APK lib/<abi>/ (AGP only zips *.so from jniLibs,
        │ so the payload uses jniLibs/<abi>/, packaged into lib/<abi>/)
        │ `extractNativeLibs=true` → PackageManager extracts at install
        ▼
/data/app/<pkg>/lib/<abi>/…            SELinux `apk_data_file` (x_file_perms) — exec OK
        │ EngineInstaller.ensureEngine() validates ELF e_machine + PT_INTERP
        ▼
ProcessBuilder proot -r rootfs -0 …     PROOT_LOADER=<payload>/loader
                                        PROOT_TMP_DIR=filesDir/proot-tmp (scratch only)
                                        LD_LIBRARY_PATH=<payload> (bionic .so deps)
```

Why this works: `appdomain` keeps `allow apk_data_file:file { … x_file_perms }`
(`x_file_perms` includes `execute_no_trans`), and `mmap(PROT_EXEC)` of `app_data_file`
stays allowed — that is precisely how PRoot's static loader maps guest ELFs, so the
kernel never `execve`s a guest binary from `filesDir`. A legacy `filesDir/native/<abi>`
engine is therefore **rejected on Android 10+** (even the `system_linker_exec` relay in
`AndroidExecBridge` can't rescue the static loader it must exec) and remains a
JVM/desktop fallback only.

`NativeBridge` (JNI) is **not** required for running the guest — the engine binaries
are executed via `ProcessBuilder`, never linked (`dlopen`) into the app, which is what
keeps GPL components out of the APK's linkage graph (§16.2).

## 6. Native engine (see `NATIVE_BINARIES.md` for the full table)

| Binary | Role | Executed how |
|---|---|---|
| `proot` (proot-rs) | Syscall interposer: emulates root, chroot-like fs view, bind mounts | separate process |
| `tini` | PID 1 inside the guest, zombie reaping | separate process inside guest |
| `busybox` | Bootstrap scripts, early shell before rootfs `sh` | separate process (host-side, bionic) |
| `pvmextract` (libarchive + zstd, JNI) | Streaming extraction with progress callbacks | in-process JNI (BSD dep → OK) |
| `bsdtar` (static, optional fallback) | Debug/offline extract | separate process |
| `qemu-<arch>-static` | Cross-arch guest only (e.g. arm64 rootfs on x86_64 host) | separate process |
| `pvmnative` (`libpvmnative.so`) | `openpty`, proc-group kill, exec helpers, archive API | loaded via JNI |

JNI surface (kept minimal and pod-safe):

```kotlin
object Extractor {
    fun extract(archive: String, dest: String, onProgress: (Long, Long) -> Unit): Int
}
object Pty {
    fun open(): ParcelFileDescriptor            // openpty(3), master fd
}
object Launcher {
    fun spawn(argv: Array<String>, cwd: String, env: Map<String, String>): Long
    fun killGroup(pid: Long, graceMs: Long): Boolean
}
```

## 7. Instance lifecycle

### 7.1 Directory layout (app private, internal storage)

```
<app filesDir>/                                        # /data/user/0/com.pocketvm/files
├── native/<abi>/…                                     # legacy JVM/dev engine copies (rejected on Android 10+)
├── shared/                                            # host↔guest file exchange
│   └── …                                              # bind-mounted into guest at /shared
├── cache/
│   ├── layers/<sha256>.part / <sha256>.tar.zst        # verified layer cache
│   └── manifests/<distro-id>.json                     # pinned version being installed
├── backups/                                           # SAF exports go to user storage instead
└── instances/
    ├── ubuntu-main/
    │   ├── config.json                                # instance record (§7.3)
    │   ├── state.json                                 # runtime state machine (§7.4)
    │   ├── rootfs/                                    # the clone (glibc, full tree)
    │   ├── home/                                      # bind-mounted over /root
    │   ├── etc/                                       # resolv.conf, motd, ssh keys (bind into guest)
    │   ├── logs/                                      # xvnc.log, session.log, crash dumps
    │   ├── vnc/
    │   │   ├── password                                # RFB password, mode 0600, generated at boot
    │   │   └── port                                    # resolved port
    │   └── snapshots/                                 # Phase 5
    └── .tmp/<instance>/rootfs/                        # install staging → atomic rename
```

Instances live on internal `/data` (fast, no FUSE) — the user-facing path
`Android/data/com.pocketvm/files/...` is the same directory when viewed via a file
manager; the app also exposes one-tap **"Open storage"** intents and SAF export.

### 7.2 Launch pipeline

```
[Start pressed]
  │ 1. read config.json; alloc VNC port (5900–5999, probe bind)
  │ 2. generate 12-hex RFB password → instances/<id>/vnc/password (0600)
  │ 3. write resolv.conf (Android DNS from ConnectivityManager) → etc/resolv.conf
  │ 4. VmRuntimeService.startForeground()
  │ 5. spawn: proot -r rootfs -0 -b /dev -b /proc -b /sys \
  │        -b home:/root -b etc/resolv.conf:/etc/resolv.conf \
  │        -b shared:/shared -w /root \
  │        /usr/bin/tini -s -- /usr/local/bin/pvm-entry
  │ 6. pvm-entry (guest script, idempotent):
  │        export DISPLAY=:1
  │        dbus-launch --exit-with-session
  │        Xvnc :1 -localhost -rfbauth /root/.vnc/passwd \
  │              -geometry ${config.resolution} -depth 24 -rfbport ${port} &
  │        openbox-session &        # or lxqt-session / xfce4-session
  │        touch /run/pvm-ready     # readiness marker
  │ 7. app polls RFB: connect 127.0.0.1:port, VNC handshake,
  │    verify challenge → DesktopScreen renders
  │ 8. [Stop pressed] SIGTERM to process group → pvm-entry traps →
  │    clean Xvnc/DE shutdown → SIGKILL after 10s → service stops
```

`pvm-entry`, `pvm-clipboard`, and a small `pvm-xrandr` helper are **our** scripts,
installed into the `desktop` layer at `/usr/local/bin/` — they are part of the image
build, not the APK.

### 7.3 `config.json` (instance record)

```json
{
  "schemaVersion": 1,
  "id": "ubuntu-main",
  "name": "Ubuntu Main",
  "distroRef": { "distro": "ubuntu", "codename": "noble", "arch": "aarch64", "version": "1.2.0" },
  "createdAt": "2026-08-29T12:00:00Z",
  "allocatedBytes": 2147483648,
  "state": "ready",                       // see §7.4
  "launch": {
    "resolution": "1280x720",             // or 1080x1920 portrait, etc.
    "vncbind": "127.0.0.1",
    "vncPort": 5901,
    "desktop": "openbox",                 // openbox | lxqt | xfce
    "features": { "audio": false, "clipboard": true, "ssh": false }
  },
  "hostBindings": { "shared": true }
}
```

### 7.4 Runtime state machine

```
NOT_INSTALLED ─▶ RESERVING ─▶ DOWNLOADING ─▶ VERIFYING ─▶ EXTRACTING
                                                                    │
   READY ◀─ POST_INSTALL ◀─ (idempotent scripts: user setup,       │
     │                        locale, resolv, xvnc test)          │
     ▼                                                             │
  STARTING ─▶ RUNNING ─▶ STOPPING ─▶ STOPPED ─▶ (Start again)      │
     │            │
     ▼            ▼
   ERROR ◀───────┘   (any step: error code, retryable flag, log tail)
```

Each transition is persisted to `state.json` **before** the operation starts. If the
app is killed mid-install, the installer resumes at the last persisted step (download
resumes by byte range; extraction restarts from the `.tmp` staging dir, which is discarded on
any failure so a half-unpacked tree is never committed).

## 8. VNC subsystem

### 8.1 Server side (in guest)

- **TigerVNC `Xvnc`** (Xvfb + RFB server), `-localhost`, `-rfbauth`, `-depth 24`.
- `-geometry` from `config.json`; RandR for dynamic resize where supported, else
  **restart session** with the new resolution (acceptable for v1).
- Software rendering only (no GPU inside guest) — this is fine because the RFB stream
  is what gets rendered, not X11 GL.

### 8.2 Client side (in app)

**Pure-Kotlin RFB 3.8 client** (Apache-2.0, keeps the app out of GPL linkage):

| Layer | Design |
|---|---|
| Handshake | `RFB 003.008`, `NoAuth` or `VNC Auth` challenge against the generated password, then `DesktopSize`/`PixelFormat` negotiation |
| Security | password never stored after boot beyond `vnc/password` 0600; connection only to `127.0.0.1` |
| Encodings | request order: `Tight` → `ZRLE` → `Hextile` → `Raw` (TigerVNC supports all) |
| Tight | zlib via `java.util.zip.Inflater`; JPEG subsamples via `BitmapFactory`; detect JPEG errors → fall back |
| Framebuffer | `IntArray` (ARGB_8888) → `Bitmap` → `SurfaceView`/Compose `Image`; dirty-rect blitting |
| Input | `PointerEvent` (relative drag for mousedown-move-up), `KeyEvent` remap, Ctrl/Alt/Shift/Mod keys as RFB key events |
| Clipboard | RFB `ClientCutText`/`ServerCutText`; guest side uses `xclip`-based bridge (Phase 3) |
| Performance target | 1280×720, ≥ 15 fps on a mid-range 2022 phone, input latency < 120 ms |

### 8.3 Touch → mouse mapping (Phase 3, spec'd now)

| Gesture | Action |
|---|---|
| 1-finger tap | left click |
| 1-finger drag | move / drag (relative mode toggle: pointer-lock style) |
| 2-finger drag | scroll wheel |
| pinch | client-side zoom of the framebuffer |
| long-press | right click |
| 3-finger tap | middle click (opt) |
| toolbar | `CTRL` `ALT` `SHIFT` `TAB` `ESC` `⊞` `⌨` `📋` `🔍` `⏻` |
| hardware kb | Bluetooth/USB keyboards via `KeyEvent` mapping to RFB keycodes (XKB → RFB keysym) |

## 9. Terminal (Phase 1 MVP)

- Native `Pty.open()` → `openpty(3)` master fd → `ParcelFileDescriptor`.
- `vm.launch.Launcher` spawns `proot … /bin/sh` with the pty slave as stdio.
- `ui.terminal` renders via the Apache-2.0 `terminal-emulator` widget
  (jackpal) — NOT Termux code (GPL) — or a custom Compose renderer; both are fine.

## 10. Security model

1. **Loopback-only VNC** with per-boot random password (12 hex chars, `0600`).
2. **App-private storage**: `filesDir` mode 0700 semantics; no `MANAGE_EXTERNAL_STORAGE`.
3. **Signed images**: Ed25519 signature over each manifest's *canonical payload* (the
   manifest without its `signature` member, keys sorted); the trusted keys are minisign
   public key files embedded in the APK (`assets/rootfs/keys/`), and verification happens
   before any download is started — a manifest that fails it never donates a URL, a size or
   a digest (ADR-017). `sha256` per layer is then verified against the signed manifest before
   extraction, and again after (the cache gate).
4. **Untrusted archives are treated as hostile input**: member names that resolve outside the
   RootFS — including through a symlinked parent directory — are refused, setuid/setgid bits
   are dropped, device nodes/FIFOs are skipped rather than created, and the declared
   uncompressed size bounds how far an archive may expand (ADR-018).
5. **No setuid, no capabilities**: guest runs as app uid; PRoot's `-0` only fakes root
   inside the emulated filesystem view.
6. **No host fs escape paths**: only `/proc`, `/dev`, `/sys`, the instance dirs, and
   `shared/` are exposed; `/sdcard` access via a user-granted SAF tree at most (planned).
7. **Update channel over HTTPS** with certificate pinning (app-built channel URL).
7. **Death of the app = death of the guest** unless the user opts into the
   background-service mode (Phase 5) — mitigates "zombie Linux" battery drain.

## 11. Build & CI

| Workflow | Triggers | Outputs |
|---|---|---|
| `native.yml` | push/PR | per-ABI (`arm64-v8a`, `x86_64`) engine zips + `sha256`, NDK + cargo-ndk matrix |
| `rootfs.yml` | push/PR to `dist/rootfs/builder`; cron | `base`/`desktop` layers per distro×arch×DE + signed `manifest.json` (GitHub arm64 runners; x86_64 runner + qemu-user-static for x86_64) |
| `apk.yml` | push/PR | debug + release APK, lint, unit tests |
| `smoke.yml` | nightly | Emulator: install APK → install Alpine (smallest) → probe `uname -a`, `apk`, `Xvnc -version` → RFB connect → assert framebuffer pixels |
| `release.yml` | tag `v*` | signed APK + native zips + layers, release notes |

Toolchain: AGP 9.x (built-in Kotlin), Compose, NDK r27, CMake 3.22+, cargo-ndk
(proot-rs), `mmdebstrap`/`debootstrap`/`apk` in Docker, `minisign` for signing.

## 12. Performance targets (Lite mode)

| Metric | Target |
|---|---|
| Alpine install → shell | < 3 min on 4G/LTE, < 45 s on WiFi |
| Ubuntu desktop boot | < 60 s cold, < 20 s warm (battery-friendly caches) |
| RAM footprint (Alpine + openbox) | ~ 180–300 MB guest |
| RAM footprint (Ubuntu + XFCE) | ~ 500–800 MB guest |
| Disk after install (Alpine desktop) | ~ 900 MB–1.2 GB |
| Disk after install (Ubuntu desktop) | ~ 2.5–3.5 GB (user's 1.2 GB estimate is optimistic for a real XFCE) |
| RFB | 1280×720 ≥ 15 fps |

## 13. Roadmap (mapped from the product brief)

Status as of 2026-08: Phases 0–2 are done and shipped in the app (README, Phases
1–7 of the implementation roadmap); Phase 3 is in progress; 4–5 are not started.

| Phase | Deliverables | Exit criteria | Status |
|---|---|---|---|
| **0 — Foundation** | repo scaffold, dep vendoring (`fetch-deps.sh`), CI skeleton, first native binary (`pvmextract`) builds | `native.yml` green; `pvmextract` unit-tested on a sample archive | done (repackaged as implementation Phases 1–5) |
| **1 — MVP shell** | distro catalog + install (Alpine first), PRoot launch, pty terminal, instance store | `adb`-driven: install Alpine → `uname -a` inside app terminal | done (implementation Phases 6, engine + terminal) |
| **2 — Desktop** | `desktop` layer (Xvnc + openbox), RFB client (Raw/Hextile first, Tight second), SurfaceView renderer | Ubuntu + openbox desktop usable *inside* the app | done (implementation Phase 7, Openbox + VNC) |
| **3 — Mobile UX** | touch toolbar, gestures, clipboard, resolution settings, landscape | 5-min scripted session without hardware keyboard | in progress |
| **4 — VM manager** | clone, backup/restore (SAF), storage management, multi-instance | clone + restore round-trip | not started |
| **5 — Advanced** | snapshots, FGS persistence, file sharing, audio (pulseaudio), SSH server (opt-in), windows/edge-to-edge polish | documented push-notification-free background run | not started |
| **Full VM (later)** | `/dev/kvm`-based QEMU path for devices where root/KVM is available; same UI, different backend | device-lab demo | not started |

## 14. Open questions (decide before Phase 1 code)

1. **proot-rs vs. proot (C):** benchmark on a stock Pixel + a Samsung with One UI.
   Termux is migrating to proot-rs; C proot is battle-tested. Plan: ship both, default
   by measured stability; `config.json` records the engine used per instance.
2. **Xvnc session lifecycle:** `dbus-launch` inside proot vs. session bus via
   `dbus-run-session` — test on Android; document the winner in `DECISIONS.md`.
3. **RFB tight JPEG:** pure-Kotlin decode via `BitmapFactory` vs. JNI `libjpeg-turbo`.
   Benchmark; keep Kotlin if ≥ 15 fps (license stays clean).
4. **Play distribution:** v1 ships as sideload APK + GitHub releases; decide Play
   strategy before Phase 2.
5. **Devices with `ptrace` blocked** (some MIUI/HyperOS, strict enterprise policies):
   detect at boot (`/proc/self/status` tracer test) and surface a friendly
   "enable developer mode" message.

## 15. Risks

| Risk | Mitigation |
|---|---|
| OEM SELinux blocks ptrace | detect + guide to dev-mode/fix; document device matrix |
| No `/dev/ptmx` access on some devices | openpty fallback: use `socketpair` + line-mode shell for the terminal |
| Large downloads fail on flaky mobile networks | resumable Range downloads, `.part` files, retry/backoff, integrity re-check |
| Android kills FGS while guest runs | `specialUse` FGS + persistent notification + `onTaskRemoved` default stop (v1) |
| VNC CPU usage in software rendering | Tight + JPEG (much cheaper than raw), restrict default resolution, dim/refresh on idle |
| Guest network breakage (Android DNS) | write resolv.conf from `ConnectivityManager` at every launch |
| Storage full mid-install | reserve `2.2 ×` image size before downloading; atomic staging aborts cleanly |
| Legal: GPL components | vendored sources + source-offer in release page; GPL runs as separate process; app code stays Apache-2.0 |

## 16. Licensing

### 16.1 In-app code (linked): Apache-2.0 — `libarchive` (BSD), `zstd` (BSD), Compose/Kotlin/OkHttp (Apache-2.0).

### 16.2 Executed, not linked
- `proot` (GPL-2.0+), `busybox` (GPL-2.0+), `tini` (MIT), `qemu` (GPL-2.0+) —
  separate processes; sources vendored; compliance notice + source links shipped.
- Guest packages (`tigervnc`, XFCE/LXQt/openbox, dbus, pulseaudio) are installed by
  the distro's own package manager from its repositories — standard Linux distribution
  compliance applies.

### 16.3 Artifacts
Release page must contain a `LICENSES.md` with vendored source pointers and the
corresponding-source statement for GPL binaries.
