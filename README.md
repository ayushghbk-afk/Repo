# Lenix

Lenix is an Android environment that installs and runs a Linux userspace inside a
single APK using **PRoot** (no root required, no Termux, no external VNC app). The
first release is a **Lenix Runtime** on top of PRoot — not a full Android VM.

> v0.1 scope: **Debian + ARM64 only**. The APK builds in CI, installs on Android,
> and shows a VM manager UI with an explicit INSTALL → READY → START flow.

## Status

**Phases 6 + 7 — PRoot engine, terminal, Openbox + VNC**

- Guest launch is real: `ProotCommandBuilder` + `GuestRuntime` exec `proot -r rootfs -0`
  with the documented bind mounts; missing `proot` is `NATIVE_ENGINE_FAILED`, never a fake
  RUNNING demo
- Engine payload ships in `app/src/main/jniLibs/<abi>/` (extracted to
  `/data/app/.../lib/<abi>/`); `NativeSetup`/`EngineInstaller` validate the ELF and
  `execve` it from there — the only app-reachable spot Android 10+ SELinux allows
  (ADR-021; a `filesDir` copy fails with `error=13, Permission denied`);
  `NativeBridge` loads optional `libpvmnative.so` for `openpty` / `killpg`
- Terminal window: one `PtySession` per guest session, owned by `GuestRuntime` for as long
  as the guest runs — so the transcript survives leaving the screen and the guest's stdout
  always has a reader (a full pipe would block the shell). `TerminalBuffer` applies `\r`
  redraws and `ESC[K` erases and drops the rest of the ANSI/OSC traffic instead of
  printing it; input is echoed locally and `END SHELL` closes stdin, because a
  pipe-backed shell can do neither (ADR-024)
- Desktop: allocate loopback RFB port 5901–5999, start `Xvnc` + `openbox-session` inside
  the guest, connect with a pure-Kotlin RFB 3.8 client (Raw encoding, tap → pointer)
- Settings toggles now do what they say: foreground service while running, auto-open Desktop
- Unit tests for argv, native copy, RFB handshake/decode, terminal rendering/session I/O,
  and the launch/stop lifecycle

**Phases 4 + 5 — Manifest signatures and real RootFS extraction**

- Manifest trust gate: every manifest — bundled or fetched — must carry an Ed25519
  signature made by a key embedded in the APK, verified over Lenix Canonical JSON before a
  URL, a size or a digest from that manifest is believed (ADR-017)
- Keys are minisign public key files (`assets/rootfs/keys/*.pub`); a signature is
  `ed25519:` + base64(`key-id || signature`), so `minisign -S -l -m <payload>` output drops
  straight in; an unsigned or foreign-keyed manifest fails as `SIGNATURE_FAILED` with both
  key ids named, and an empty key ring rejects everything rather than skipping the check
- The bundled Debian manifest is really signed: `scripts/sign-rootfs-manifest.sh` (openssl)
  and `scripts/canonical-json.py` (the byte-for-byte mirror of the Kotlin canonicalizer)
  produce it, and `BundledRootfsManifestTrustTest` fails CI if the shipped manifest and the
  shipped key ever stop agreeing
- Real streaming extraction replaces the Phase 3 staging stub: layers are decompressed and
  unpacked into `instances/<id>/.tmp/rootfs/` member by member, then committed with one
  atomic rename (ADR-018)
- Untrusted-archive hardening in the extractor: escaping member names and symlinked parent
  directories are refused (tar-slip), setuid/setgid bits are dropped, device nodes/FIFOs
  are skipped and counted, a layer that expands far past its manifest size is refused, and
  free space is re-probed while writing so a full device fails as `INSUFFICIENT_STORAGE`
- `instances/<id>/rootfs.json` now records the auditable result: per-layer digests, entry
  counts, the signing key id and the hash of the canonical manifest payload
- Progress is honest during extraction (bytes unpacked per layer, current member), the
  Home screen says so, and a cancel/reset discards the staging tree while the verified
  layer cache survives
- New error categories `SIGNATURE_FAILED` and `UNSUPPORTED_COMPRESSION`; new unit tests for
  the canonicalizer, key/signature wire formats, the verifier, the signer and extraction

**Phase 3 — Resumable RootFS downloader (and the settings fix)**

- Real install pipeline: bundled manifest → download → verify → stage → commit,
  driven by `data.download.ResumableDownloader` (ADR-015)
- HTTP `Range` resume from byte-exact `.part` files, with ETag/`If-Range`
  validation so changed upstream content restarts instead of corrupting
- Content-addressed layer cache at `filesDir/cache/layers/<sha256>.layer`,
  shared across instances and retries: a layer is downloaded at most once
- Retry with exponential backoff for transient failures (I/O, short reads,
  408/429/5xx); checksum gate before anything is trusted; 416 and
  range-ignoring servers handled by clean restart
- Interrupted installs really resume: process death or cancel leaves the
  `.part` + per-instance `state.json` checkpoint behind, and RESUME INSTALL
  continues where it stopped (the Home screen shows the interrupted percentage)
- Real Debian bookworm arm64 RootFS: the bundled manifest pins an upstream layer
  hosted on GitHub Releases (`termux/proot-distro`'s bookworm aarch64 tarball,
  Range-supported), downloaded and sha256-verified by the app — INSTALL on a device
  genuinely works end to end
- Fixed: settings now actually save — `filesDir/settings.json` via
  `JsonSettingsStore` (ADR-016), with the storage-care toggle gating a real
  free-space precheck before installs
- Unit tests for the downloader (MockWebServer: resume, ETag, 416, retry,
  cancellation), the full installer pipeline, and the new stores

**Phase 2 — Instance manager and local persistence**

- Multi-instance manager: create / rename / delete instances with slug ids, unique
  names, and a v0.1 instance cap (4)
- Per-instance `config.json` records under `filesDir/instances/<id>/`, written
  atomically (temp file + rename) and reloaded on every app start (ADR-014)
- Every state transition is persisted (ADR-012); instances survive app restarts
- Crash recovery: transient states never survive a process restart — a running guest
  becomes `READY` again, an interrupted install becomes `ERROR` +
  `INSTALL_INTERRUPTED` (retryable; since Phase 3 the retry resumes the download)
- Selected instance is remembered across restarts (`selected_instance` file)
- Functional Instance Manager screen: per-row state, on-disk size, rename, delete,
  create dialog driven by the distro catalog
- Unit tests for the store, selection store, and manager persistence/recovery

**Phase 1 — Android Project Foundation**

- Jetpack Compose UI with a home / settings / instance / terminal / desktop / distro
  screen scaffold
- Gradle 9.5 + AGP 9.3 (built-in Kotlin)
- GitHub Actions APK build, lint, and release workflows
- Explicit VM state machine (`NOT_INSTALLED → DOWNLOADING → VERIFYING → EXTRACTING →
  INSTALLING → READY → STARTING → RUNNING → STOPPING`)
- RootFS manifest parser (JSON schema + field validation)
- RootFS checksum verifier interface
- Local fake installer for UI testing
- Unit tests for package `vm` and `installer`

Extraction is real (streaming, hardened, pure-JVM) and manifests are signature-checked.
The PRoot command line, PTY/pipe terminal and loopback RFB viewer are in the app; a
device still needs the PRoot engine payload under
`app/src/main/jniLibs/arm64-v8a/` before START can spawn a guest — run
`./scripts/fetch-engine.sh` to bundle the official Termux PRoot build. The payload is
staged as `libproot.so` / `libprootloader.so` / `libprootloader32.so` (optional) /
`libtalloc.so` / `libandroid-shmem.so`. The last two come from **separate Termux
packages** (`libtalloc`, `libandroid-shmem`) — proot only declares them as
dependencies, so `fetch-engine.sh` downloads all three `.deb`s; without them the app
reports *"payload is present but its shared library dependencies are missing"* and
refuses to start the guest. Android only extracts `lib*.so` entries from an APK's
`lib/<abi>/` on release builds, so anything else is packaged and then silently
discarded (ADR-022). The Gradle build runs the fetch script for you when the payload
is incomplete — pass `-PskipEngineFetch=true` to build without it. Check the staged
payload with `./scripts/verify-payload.sh` and the built APK with
`./scripts/verify-apk-engine.sh app/build/outputs/apk/debug` (both fail hard in CI).
zstd layers wait on `libpvmnative`; the pinned upstream layer is `tar.xz`.

If a device reports missing engine dependencies after a rebuild: uninstall the app
first — `adb install -r` / Play updates can leave an older install's extracted
native payload behind — then install the fresh APK.

## Runtime model

```
Lenix Runtime (v0.1+)
    Android
      → Lenix Native Engine
      → PRoot
      → Linux RootFS

Future Lenix VM
    Android
      → Virtualization Backend
      → Linux Kernel
      → Virtual Disk
```

## Repository layout

```
lenix/
├── app/                     # Android app (Jetpack Compose)
├── gradle/                  # Gradle wrapper + shared config
├── gradlew, gradlew.bat
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── .github/workflows/       # build, lint, release
├── native/                  # native engine tree (Phase 1+)
├── scripts/                 # build / lint / smoke helpers
├── docs/                    # architecture + decisions
├── LICENSE
├── .gitignore
└── README.md
```

## Quick start

### Prerequisites

- JDK 17
- Android SDK with API 37

### Build locally

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

### Build via GitHub Actions

Push to `main` or any `arena/**` branch. The **Build Lenix APK** workflow runs unit
tests, assembles the debug APK, and uploads the artifact.

### Smoke test on a device

```bash
ANDROID_HOME=~/Android/Sdk ./scripts/smoke.sh
```

### Re-sign a RootFS manifest

Any change to a bundled manifest invalidates its signature, so it must be re-signed before
the app will accept it (CI has a test that enforces this):

```bash
./scripts/sign-rootfs-manifest.sh app/src/main/assets/rootfs/debian-bookworm-aarch64.json ~/lenix-signing/lenix-release.key.pem
```

See `docs/BUILDING.md` for key generation and rotation.

## Product scope (v0.1)

| Item | v0.1 |
|---|---|
| Distro | Debian |
| Architecture | arm64-v8a (`aarch64`) |
| Desktop | Openbox |
| Root access | Not required |
| RootFS install | Download → verify → extract → run |
| First user-visible milestone | APK from CI installs and shows the VM manager UI |

Later releases add Ubuntu, Alpine, LXQt/XFCE, multi-instance, and a built-in VNC
viewer.

## Architecture

The app is organized to avoid a monolithic `MainActivity`:

```
app/src/main/java/com/lenix/
├── ui/                 # Compose screens + navigation
│   ├── App.kt
│   ├── MainActivity.kt
│   ├── HomeViewModel.kt
│   └── screens/
│       ├── HomeScreen.kt
│       ├── InstanceScreen.kt
│       ├── InstallScreen.kt
│       ├── TerminalScreen.kt
│       ├── DesktopScreen.kt
│       └── SettingsScreen.kt
├── vm/                 # state machine, VmManager, instance/process abstractions
├── installer/          # manifest, catalog, signing keys + verifier, installer
│   └── extract/        # streaming tar.{xz,gz} extraction, escape-proofed
├── data/               # local persistence: config.json instance store, selection,
│   ├── SettingsStore.kt      # settings.json (the settings-savings fix)
│   ├── InstallStateStore.kt  # per-instance state.json install checkpoints
│   └── download/             # ResumableDownloader + content-addressed LayerCache
├── nativebridge/       # NativeSetup + optional JNI (openpty)
├── vnc/                # RFB 3.8 client (loopback, Raw)
└── vm/launch + vm/pty + vm/service
```

### State machine

Linux status is never tracked with random booleans. `VmState` is one enum and
`VmStateMachine` rejects illegal transitions:

```
NOT_INSTALLED → DOWNLOADING → VERIFYING → EXTRACTING → INSTALLING → READY
  READY → STARTING → RUNNING → STOPPING → READY
  any → ERROR
```

### Error categories

```
NETWORK_ERROR, INSUFFICIENT_STORAGE, DOWNLOAD_CORRUPTED, CHECKSUM_FAILED,
SIGNATURE_FAILED, ROOTFS_EXTRACTION_FAILED, UNSUPPORTED_COMPRESSION,
UNSUPPORTED_ARCHITECTURE, NATIVE_ENGINE_FAILED, PROCESS_CRASHED,
VNC_CONNECTION_FAILED
```

## Device requirements

| Requirement | Minimum | Recommended |
|---|---|---|
| Android | 10 (API 29) | 12+ |
| RAM | 2 GB | 4 GB+ |
| Storage | 2 GB free | 8 GB+ |
| Architecture | arm64-v8a | arm64-v8a |

`armeabi-v7a`, `x86`, and `x86_64` are explicitly rejected at first.

## Documents

| Document | Contents |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Product goals, app architecture, native engine, security |
| [`docs/BUILDING.md`](docs/BUILDING.md) | Build instructions |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | Architecture decision record |
| [`docs/DEVICE_COMPATIBILITY.md`](docs/DEVICE_COMPATIBILITY.md) | Device support matrix |
| [`docs/NATIVE_BINARIES.md`](docs/NATIVE_BINARIES.md) | Native binary inventory |
| [`docs/ROOTFS_SYSTEM.md`](docs/ROOTFS_SYSTEM.md) | RootFS distribution and install pipeline |

## Roadmap

- [x] **Phase 0** — design docs and repo structure
- [x] **Phase 1** — Android project foundation, Compose UI, state machine, manifest parser
- [x] **Phase 2** — instance manager and local persistence
- [x] **Phase 3** — resumable RootFS downloader
- [x] **Phase 4** — checksum + signature verification
- [x] **Phase 5** — RootFS extraction
- [x] **Phase 6** — native engine (PRoot) + terminal
- [x] **Phase 7** — Openbox desktop + built-in VNC viewer

## License

Apache-2.0 for Lenix app code (see [`LICENSE`](LICENSE)). PRoot, BusyBox, TigerVNC,
and other GPL components are executed as separate processes and vendored separately
for license compliance; native asset licensing and source-offer metadata are tracked
in `docs/NATIVE_BINARIES.md`.
