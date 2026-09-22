# DECISIONS — ADR log

Every decision that shapes the architecture, recorded in one place. Status:
`accepted` / `proposed` / `open`. Revisit entries via a new ADR, never by editing an
old one (append-only).

---

## ADR-001 — Isolation engine: PRoot userspace (accepted)

**Context:** No root; Android app sandbox; must run glibc rootfs.
**Decision:** Use a PRoot-style ptrace userspace interposer as the v1 engine (Lite
mode). QEMU full virtualization is a separate, later backend (Full mode) only where
root/KVM is available.
**Consequences:** No kernel-level isolation; guest processes share Android's kernel
and network; `/proc` is partially emulated; correct for the "feels like a VM" goal
without root.

## ADR-002 — Which proot: C `proot` vs `proot-rs` (open → decide in Phase 1)

**Context:** Termux is migrating to proot-rs; C proot is battle-tested.
**Decision:** Benchmark both on a stock Pixel and a One UI device; ship whichever
wins on stability/speed, record it per instance in `config.json`. Both are scheduled
for Phase 1.

## ADR-003 — Desktop server: TigerVNC `Xvnc` in guest, RFB client in app (accepted)

**Context:** Must display a real X11 desktop inside the app with no external viewer.
**Decision:** Run `Xvnc` (X server + RFB server) inside the guest on loopback with a
per-boot password; render RFB with a bundled pure-Kotlin 3.8 client to a
Surface/Compose canvas. No hardware-accelerated X11 (software rendering); performance
comes from Tight/JPEG and resolution limits, not guest GPU.

## ADR-004 — RFB client: pure Kotlin, Apache-2.0 (accepted)

**Context:** Tight decoder can use zlib (JDK `Inflater`) and JPEG (`BitmapFactory`);
a JNI libjpeg-turbo path is possible.
**Decision:** Pure-Kotlin client; JNI decoder is a benchmarked fallback only. Keeps
the APK's compiled code licensed Apache-2.0 and avoids GPL linkage questions.

## ADR-005 — RootFS distribution: layers + signed manifest (accepted)

**Context:** Multiple distros, archs, and DE flavors; flaky mobile networks; must be
verifiable.
**Decision:** `base` + `desktop` tar.zst layers, content-addressed by sha256, with an
Ed25519-signed `manifest.json`; immutable releases; resumable downloads; atomic
install; content-addressed layer cache shared by instances.

## ADR-006 — Guest user: faked root, real app uid (accepted)

**Context:** Android's uid model, PRoot's `-0`.
**Decision:** Guest runs as the app uid; PRoot's `-0` only emulates UID 0 inside the
emulated filesystem. No setuid binaries, no capabilities, no host filesystem escape.

## ADR-007 — Storage: app-private internal storage only (accepted)

**Context:** FUSE `/sdcard` is slow; file manager visibility is a nice-to-have.
**Decision:** Instances in `filesDir/instances/<id>/`; SAF only for user-initiated
backup export/import and (later) optional file sharing. Internal storage path is
device-PRIVATE-by-design; the app shows "Open storage" intents for browsing.

## ADR-008 — Desktop flavor default: Openbox for v1 (accepted)

**Context:** Phones have 3–8 GB RAM; GNOME/KDE are out.
**Decision:** Ship three flavors (openbox, lxqt, xfce). Default = openbox (lowest
RAM, fastest cold boot). XFCE offered on the installer screen for users who want
more polish; they are three `desktop` layers over the same `base`.

## ADR-009 — Terminal: pty via JNI `openpty(3)` (accepted)

**Context:** Phase 1 MVP is a shell; Android lacks `/dev/ptmx` guarantees on some
devices.
**Decision:** Use `openpty(3)` through `libpvmnative.so`; render with an
Apache-2.0 terminal widget (jackpal `terminal-emulator`) or custom Compose renderer.
Fallback: `socketpair` + line-mode shell if `/dev/ptmx` is unavailable.

## ADR-010 — Update model: new instances, not in-place upgrades (accepted)

**Context:** Upgrading a rootfs in place risks breaking a running environment and is
hard to make atomic on Android.
**Decision:** v1 installs are versioned per instance; new versions create new
instances. In-place upgrade + data migration is a Phase 4 feature.

## ADR-011 — Distribution channel (proposed)

**Context:** Play policy for FGS "specialUse" + user-installable environments.
**Decision (proposed):** v1 ships as signed sideload APK via GitHub Releases;
Play Store submission investigated in parallel (specialUse FGS declaration +
disclosure). RootFS layers hosted on GitHub Releases (Range-supported) for v1.

## ADR-012 — Threading model (accepted)

**Context:** Long-running guest, foreground service, progress UI.
**Decision:** Single install-task executor, cancellable jobs, state persisted before
every transition; guest owned by `GuestProcess` (process group, SIGTERM→SIGKILL);
RFB reader thread + SurfaceView renderer; input from UI thread.

## ADR-013 — Jetifier disabled (accepted)

**Context:** `android.enableJetifier=true` came with the project template, but every
dependency Lenix uses is already AndroidX/Jetpack, Kotlin or plain Java, so nothing
needs the legacy support-library rewrite. Jetifier still ran a bytecode pass over
every jar in the graph, and that pass was not merely wasted: Jetifier's bundled ASM
cannot read class file major version 65 (Java 21), and jackson-core 2.16+ ships Java
21 classes under `META-INF/versions/21` (shaded FastDoubleParser; the jar is
`Multi-Release: true`). With Jetifier on, resolution of `debugCompileClasspath` died
in `JetifyTransform` — `IllegalArgumentException: Unsupported class file major
version 65` — before Kotlin was ever compiled.
**Decision:** Set `android.enableJetifier=false` and keep it off. If a future
dependency still needs jetification, upgrade or replace that dependency rather than
flipping the flag back.
**Consequences:** Configuration and dexing get faster (one less whole-graph bytecode
transform), and we drop a flag that AGP 9 removes outright. Multi-release jars such
as jackson-core now reach D8 untouched and Android resolves their base (Java 8)
entries, which is the only variant the runtime would ever load.

## ADR-014 — Instance persistence: per-instance `config.json`, no Room (accepted)

**Context:** Phase 2 needs instances to survive app restarts. `ARCHITECTURE.md` §7.3
already specifies a `config.json` instance record per instance directory, and ADR-012
requires state to be persisted on every transition. The obvious "just add Room"
path would pull in the Room runtime, KSP, and a schema-migration story for what is
currently a handful of tiny records with no relational queries.

**Decision:** Persist each `VmInstance` as
`filesDir/instances/<id>/config.json` via `data.JsonInstanceStore` (Jackson, the JSON
stack already shipped for manifests). Records are written atomically (temp file +
`Files.move(ATOMIC_MOVE)`), carry a `schemaVersion` gate (newer schema ⇒ record
skipped, never mangled), and are skipped — not deleted — when corrupt or when the id
does not match its directory. On manager construction, persisted transient states are
normalized for process death (`RUNNING/STARTING/STOPPING → READY`,
`DOWNLOADING/VERIFYING/EXTRACTING/INSTALLING → ERROR + INSTALL_INTERRUPTED`), and any
normalization is re-persisted. The last-selected instance is remembered in a one-line
`selected_instance` file. Room stays out until a real query need appears (e.g. install
event history).

**Consequences:** The store is plain-JVM unit-testable (constructor takes a `File`
root, not a `Context`); no new Gradle dependencies; the installer's own provenance
snapshot was renamed to `rootfs.json` so `config.json` unambiguously means the
instance record from §7.3. The per-step `state.json` staging detail from
`ROOTFS_SYSTEM.md` remains a Phase 3 concern layered on top of this record.

## ADR-015 — Resumable downloader: OkHttp + `.part`/`.etag` sidecars + content-addressed cache (accepted)

**Context:** Phase 3 needs the download step of `ROOTFS_SYSTEM.md` §2 to work on
flaky mobile networks and to survive process death. `ARCHITECTURE.md` already pins
"OkHttp on its own pool; `ResumableDownloader` writes to `cache/<sha>.part`".

**Decision:** `data.download.ResumableDownloader` (OkHttp 4, no whole-call timeout)
streams each layer into `filesDir/cache/layers/<sha256>.layer.part` and renames it
to `<sha256>.layer` only after the full `sha256` matches the manifest. A partial is
resumed with `Range: bytes=<n>-`; the ETag captured beside the `.part` is replayed
as `If-Range`, so a changed upstream restarts from zero instead of concatenating
mismatched bytes (the completion checksum is the second gate). Servers that ignore
Range (plain 200), reject it (416), or resume at a wrong offset make the attempt
restart cleanly; transient failures (I/O, short reads, 408/429/5xx) retry with
exponential backoff (max 3 attempts) always resuming from disk. Coroutine
cancellation is not an error: it leaves the `.part` in place — that is the resume
point for the next install — and always surfaces as `CancellationException`, never
a wrapped `VmError`. Explicit user cancel deletes in-flight `.part`s but keeps
completed layers (immutable, content-addressed, shared across instances).

The cache key is the bare sha256 with a fixed `.layer` suffix (not
`<sha>.tar.zst`): the digest is validated before it is used as a filename, and one
canonical name per digest means two URLs serving the same content share one cache
entry. Per-instance progress lives in `instances/<id>/state.json`
(`data.JsonInstallStateStore`, the checkpoint `ROOTFS_SYSTEM.md` §2 assigns to
Phase 3), refreshed at phase changes and at least every 5 s, cleared on commit.

v0.1 layer sourcing: no Lenix-owned layer host exists yet, so the bundled manifest
(`assets/rootfs/debian-bookworm-aarch64.json`) pins a real Debian bookworm arm64
rootfs published on GitHub Releases by `termux/proot-distro`
(`debian-bookworm-aarch64-pd-v4.7.0.tar.xz`, ~43 MB). Its sha256 is pinned in the
manifest — the same digest upstream verifies on every install — and the app
re-verifies it over the downloaded bytes before anything is staged, so a swapped
or corrupted asset can never pass. The layer is *downloaded at runtime*, not
vendored into the APK (the guest is Debian's own licensed content plus proot-distro
setup bits; attribution lives in `docs/ROOTFS_SYSTEM.md` §7). The manifest
signature field is a documented `unsigned:phase-4` placeholder until Ed25519
verification lands. `uncompressedBytes` for the borrowed layer is a conservative
estimate used only for the storage precheck. Real streaming extraction is Phase 5 —
until then verified layers are staged into the instance directory.

**Consequences:** A completed layer is downloaded at most once per device, an
interrupted install resumes at the exact byte it stopped, and tampered or truncated
transfers can never reach extraction. The installer is file-based (no `Context`)
and therefore fully JVM-testable against MockWebServer; OkHttp is the first new
runtime dependency since Jackson. Building and hosting Lenix's own layers (the
`rootfs.yml` builder — docker-export `debian:bookworm-slim`, xz, publish to this
repo's Releases, regenerate the manifest) is the next step once workflow-file
permissions allow it.

## ADR-016 — App settings: one `settings.json`, owned by the root view model (accepted)

**Context:** The Settings screen kept its three toggles in
`remember { mutableStateOf(...) }` — local UI state — so every value was lost on
navigation or process death ("settings not saving"). The obvious fixes are
`SharedPreferences`/DataStore, but the settings are three booleans with no
cross-process access and no observed types.

**Decision:** Persist a versioned `SettingsRecord` to `filesDir/settings.json` via
`data.JsonSettingsStore` — the same wire-format rules as the other JSON stores
(atomic temp-file + rename, unknown fields ignored, corrupt/newer-schema loads as
documented defaults, plain `File` constructor for JVM tests). One owner:
`HomeViewModel` loads the settings at construction, updates its state flow
optimistically, and serializes disk writes on the manager dispatcher. Screens stay
stateless and receive `(LenixSettings, onUpdate)` — the same
single-source-of-truth pattern as the instance list. The storage-care toggle is
wired to a real free-space precheck before installs (`StatFs`; required ≈ Σ
compressed + 1.2 × Σ uncompressed, `ROOTFS_SYSTEM.md` §4); the background-runtime
and desktop auto-start toggles are consumed by the Phase 6/7 features they name.

**Consequences:** Settings survive restarts by construction and are unit-testable
without Android. DataStore/SharedPreferences stay out until a real need appears
(observed types, cross-process access); migrating a 3-field JSON record then is
trivial.

---

## ADR-017 — Manifest signatures: minisign keys over a canonical JSON payload (accepted)

**Context:** `ARCHITECTURE.md` §3 requires an APK-pinned Ed25519 signature on the manifest,
and ADR-005 makes that signature the *only* root of trust: layer sha256s are trusted because
the manifest that names them was signed, so nothing else needs a signature of its own. Phase
3 shipped the schema, the parser, the digest gate and a `RootfsManifest.signature` field that
was checked for non-blankness and nothing else. Signing an arbitrary JSON document is not a
matter of hashing its bytes: two encodings of the same manifest are equally valid to a parser
and would produce different signatures, so signers and verifiers must agree on the bytes.
Android also has no pre-hashed-Ed25519 primitive, and the JDK's `Ed25519` `Signature` has no
way to feed it the BLAKE2b-512 prefix minisign uses for text files.

**Decision:** Sign a *canonical payload*, and keep the wire formats of the key and the
signature compatible with the ecosystem tools people already know:

- **Lenix Canonical JSON** (`RootfsManifestCanonicalizer`, mirrored byte-for-byte by
  `scripts/canonical-json.py`): the top-level `signature` member is dropped, object keys are
  sorted by UTF-16 code unit recursively, everything is compact, and numbers keep the exact
  source token Jackson produced (no float round-trip). The canonical form of the shipped
  manifest is a fixture in `RootfsManifestCanonicalizerTest`, and the two
  `RootfsManifestCanonicalizerTest` vectors are `cmp`-checked against the Python mirror, so
  the Kotlin and the signing script cannot drift apart silently.
- **Keys** are minisign public key files (`untrusted comment` + base64(`"Ed"` ‖ key-id ‖
  raw Ed25519 key)), so `minisign -G` output and `gen-rootfs-signing-key.sh` both drop
  straight into `assets/rootfs/keys/*.pub`, which is the only place the app looks.
- **Signatures** are `ed25519:` + base64(key-id ‖ 64-byte raw Ed25519 signature) verified
  with the JDK's `Signature.getInstance("Ed25519")` — i.e. minisign's *legacy* raw mode,
  `minisign -S -l -m <payload-file>`. That is why the signature covers canonical bytes and
  not the file's bytes: `-l` is what makes pre-hashing unnecessary. The key id in the
  signature must be one the ring trusts, and it is reported in errors.
- **Placement:** verification runs before the download gate — in `HomeViewModel.install()`
  (so a rejected manifest is an error state, never a stuck idle card) and again at the top of
  `RootfsInstaller` (so the library cannot be used without it). Layer digests are then
  re-checked after each download (`RootfsVerifier.verifyLayer`, ADR-015's cache gate).

**Consequences:** Trust is enforceable, and "unsigned" is a failure rather than a mode: an
empty ring rejects, so a build that forgets to ship a key ships nothing installable. Key
rotation is file-based — drop a second `.pub` in and revoke by removing the old one, no code
change; `RootfsSigningKeysTest` covers both paths. Re-signing is one script, and CI proves
the shipped pair is consistent on every build. The cost: a manifest fetched from a CDN must be
signed *before* publication (no "download, tweak, install" path). Numbers are the fragile part
of any canonicalization scheme — a bare `double` would let `1E+8` and `100000000` sign
differently — which is why the canonical form keeps source tokens and Jackson is configured
with big-number nodes. A manifest that writes `1E+8` where the model reads `100000000` is
therefore not a hole — the signature is over the bytes, so it simply fails to verify, which is
the safe direction.

---

## ADR-018 — Extraction: pure-JVM streaming unpack now, native fast path in Phase 6 (accepted)

**Context:** Phase 5 has to turn verified archives into a RootFS tree. `ARCHITECTURE.md` §6
plans a native `libpvmextract` (libarchive + zstd + hardlink/ownership handling) for the
release pipeline's `tar.zst` layers, and `NATIVE_BINARIES.md` lists its `.so` as needed from
Phase 5 — but no native binary is built in this repository, the engine itself is Phase 6, and
a Debian RootFS is ~40 k members: a DOM-style or non-streaming unpack would OOM on a phone, and
waiting on the native path would block Phase 5 on Phase 6.

**Decision:** Extract in the JVM now, streaming, and treat the archive as hostile input:

- Apache `commons-compress` `TarArchiveInputStream` in **strict** mode (`longFileMode=POSIX`
  so a GNU long-name extension member cannot silently mis-frame the entry that follows) inside
  `XZ`/`GZIP` input streams, writing straight into `instances/<id>/.tmp/rootfs/` and committing
  with one rename (ADR-015's staging rule). `org.tukaani:xz` is the XZ implementation — pure
  Java, no JNI.
- Escape-proofing: each member name is normalized segment-wise (drop `.`/empty, refuse `..`),
  rejected for control characters or >255-char components, and its target must be the RootFS
  root or below a *lexically canonicalized* parent — the symlink case `ROOTFS_SYSTEM.md` §2
  calls out — while dangling member symlinks stay allowed because a real Debian rootfs has them.
- Permission model: only the owner bits survive (group/other zeroed, setuid/setgid/sticky
  dropped — the app user owns everything anyway, `ARCHITECTURE.md` §6), directories are always
  writable so cleanup can proceed, and a member whose declared size exceeds what the stream
  delivered fails as `ROOTFS_EXTRACTION_FAILED` instead of writing a truncated file.
- Device nodes, FIFOs and sockets are skipped and counted, not failed on (upstream rootfs
  tarballs contain them and `/dev` is PRoot's job); hard links are recreated with
  `Files.createLink` and counted.
- Two runtime guards, because the archive is untrusted even though it is signed: an expansion
  cap (`max(4 × declared uncompressed size + 64 MiB, 1 MiB)`, or a 32 GiB ceiling when the
  manifest gives no hint) plus a 2 M-entry cap, and a `StatFs` re-probe every 32 entries with a
  32 MiB floor so a full device fails as `INSUFFICIENT_STORAGE` rather than mid-write `EIO`.
- `zstd` is a *known* format that this extractor refuses with `UNSUPPORTED_COMPRESSION`, because
  commons-compress' `ZstdCompressorInputStream` needs the `zstd-jni` native library — the exact
  dependency the app does not have. The bundled manifest therefore pins the upstream Debian
  layer as `tar.xz`, which the builder will keep producing until Phase 6 lands the native
  reader (H4).
- Progress and cancellation: unpacked bytes per layer with the current member name, throttled
  to ~1 MiB, `ensureActive()` per entry; `ExtractionReport` (files/dirs/symlinks/hardlinks/
  skipped/bytes) is persisted to `rootfs.json` as the install's audit record.

**Consequences:** Phase 5 is testable on the JVM (`RootfsExtractorTest` builds real tarballs —
escaping paths, symlinked parents, dangling and self-referencing links, long names, sparse
members, garbage streams — and asserts each outcome), and the native `libpvmextract` in Phase 6
becomes a *performance and format* upgrade behind the same contract instead of a prerequisite.
The costs are honest ones: `tar.zst` install is not available yet, xattrs/ACLs are ignored (an
app-uid rootfs needs none), and 40 k members through the JVM are slower than the native path —
which is why progress reporting was built first. `commons-compress` and `xz` join Jackson and
OkHttp as pure-JVM dependencies (≈1.2 MB before R8), and `app/proguard-rules.pro` now keeps
annotation attributes so a release build still parses manifests (ADR-009's rule, newly
load-bearing).

---

## ADR-019 — Guest launch: ProcessBuilder PRoot, JNI optional (accepted)

**Context:** Phase 6 has to start a Linux userspace. Linking `proot` into the APK would
GPL-taint the app (ARCHITECTURE.md §16.2). `/dev/ptmx` is not guaranteed.

**Decision:** `ProotGuestEngine` execs `filesDir/native/<abi>/proot` via `ProcessBuilder`
with the documented `-r -0 -b` argv (`ProotCommandBuilder`). `NativeSetup` copies
assets there on first launch. `libpvmnative.so` is optional (`NativeBridge.tryLoad`);
the terminal uses pipe-backed stdio (`PtySession`) until `openpty` is present.
Missing `proot` is `NATIVE_ENGINE_FAILED`, never a fake RUNNING state. A
`VmRuntimeService` specialUse FGS is started when the background-runtime setting is on.

**Consequences:** Unit tests inject a `GuestEngine`. Shipping a real guest still needs
the arm64 `proot` binary dropped into assets; the command line, session lifecycle and
terminal I/O are otherwise complete.

## ADR-020 — Desktop: Openbox + loopback RFB 3.8 Raw client (accepted)

**Context:** Phase 7 must show a desktop inside the app (ADR-003/004/008). Tight/ZRLE
can wait; a handshake that cannot be tested on the JVM cannot ship.

**Decision:** Desktop launch is the same PRoot tree with an inner `sh -c` that starts
`Xvnc -localhost -rfbport <port> -SecurityTypes None` then `openbox-session`. The app
allocates 5901–5999, writes a 12-hex password file (for later VNC-Auth), and
`RfbClient` speaks RFB 3.8 on `127.0.0.1` only, decoding Raw 32-bpp into an ARGB
`Bitmap` shown by `DesktopScreen`. Auto-start desktop (ADR-016) navigates there after
START. Tight/JPEG is a later encoding behind the same client.

**Consequences:** The protocol is JVM-tested with a scripted server. A Debian layer
without Xvnc/Openbox will retry then surface `VNC_CONNECTION_FAILED` instead of hanging.

**Amendment (fix):** the client now sends `SetPixelFormat` (32 bpp, depth 24,
little-endian, true colour, shifts 16/8/0) right after `ServerInit`, before
`SetEncodings`. The Raw decoder hard-assumes that layout, and the 4th byte of each
pixel is RFB *padding* — not alpha — so decoded pixels are forced opaque. Reading it
as alpha yielded a fully transparent (blank) framebuffer against `Xvnc -depth 24`.

## ADR-021 — Engine exec: signed APK native payload, not filesDir (accepted)

**Context:** On-device START failed with `Cannot run program
"/data/user/0/com.lenix/files/native/arm64-v8a/proot": error=13, Permission denied`.
ADR-019 said "copy assets to filesDir + chmod 0700", but that is not what Android 10+'s
W^X restriction is: since AOSP commit `0dd738d8` (Android 10, targetSdk ≥ 29),
SELinux *neverallows* `{ all_untrusted_apps } { app_data_file privapp_data_file }:file
execute_no_trans`. `execve()` of a 0700 file under `/data/user/0/<pkg>/` fails EACCES
regardless of mode bits; only `mmap(PROT_EXEC)` (dlopen, and PRoot's own loader mapping
guest ELFs) stays allowed there.

**Decision:** Engine binaries ship as **native library payloads** — drop
`proot` (+ static `loader`, `tini`, `libtalloc.so.2`, `libandroid-shmem.so`) into
`app/src/main/resources/lib/<abi>/` (not `jniLibs/`: AGP only packages `*.so` from
there; `resources/lib/<abi>/` was the documented wrap.sh route into the APK's
`lib/<abi>/`). **Superseded by ADR-022**, which moves the payload to `jniLibs/`.
With `useLegacyPackaging = true` / `extractNativeLibs=true` the package manager
extracts the whole `lib/<abi>/` to `/data/app/<pkg>/lib/<abi>/`
(`ApplicationInfo.nativeLibraryDir`), which is labelled `apk_data_file`; app.te keeps
`allow appdomain apk_data_file:file { ... x_file_perms }` there, so direct exec is
legal (Google's own response on this says exactly this: package the binaries in the
app's native libs directory, enable `extractNativeLibs`, exec the `/data/app`
artifacts). The app:

- resolves the engine at `ApplicationInfo.nativeLibraryDir` via
  `EngineInstaller.ensureEngine()`, validating `e_machine`/`PT_INTERP` with
  `NativeSetup.probe()` instead of trusting a filename;
- pins `PROOT_LOADER` to the payload's static loader (PRoot must never extract+exec a
  loader into app temp — that exec is denied too) and leaves `PROOT_TMP_DIR` in
  `filesDir` (scratch only, never exec'd);
- sets `LD_LIBRARY_PATH` to the payload dir for PRoot's bionic `.so` deps;
- for a legacy `filesDir/native/<abi>` install **fails fast on Android 10+** with an
  actionable message instead of half-working: even a bionic engine would run, but the
  static `loader` it execs for every guest binary can't be relayed through
  `/system/bin/linker64` (`system_linker_exec` has no `execute_no_trans` for app data
  either). Off-Android (JVM/dev setups) the filesDir engine still works directly.
  `AndroidExecBridge` keeps the linker relay as a safety net for bionic host helpers
  that must run from app data (the Termux termux-exec mechanism);
- refuses to auto-download "engines": the old `DEFAULT_ENGINE_URLS` were 404s and an
  unsigned binary download is a supply-chain risk. `scripts/fetch-engine.sh` now
  unpacks the real Termux PRoot `.deb` into the payload dir at build time and
  validates the ELF machine;

**Consequences:** The bundled `assets/native/arm64-v8a/proot` (a 770-byte shell
launcher, not PRoot) is gone; a build without the payload fails explicitly
(`NATIVE_ENGINE_FAILED` with an actionable message) instead of pretending the engine
exists. APK size grows by the payload (~0.5 MB + deps). Unit tests cover ELF probing,
payload-vs-legacy resolution and the linker relay; the real binary still cannot be
JVM-tested.

---

## ADR-022 — Engine payload: `jniLibs/`, named `lib*.so`, fetched by the build (accepted)

**Context:** ADR-021 put the engine in `app/src/main/resources/lib/<abi>/`, but START
still failed with `No PRoot engine for 'arm64-v8a' was found` and AUTOFIX ENGINE could
never clear it. Three independent defects, each fatal on its own:

1. **Nothing ever ran `scripts/fetch-engine.sh`.** The directory held only `.gitkeep`
   (the binaries are GPL and deliberately untracked) and no workflow fetched them, so
   every CI APK — debug *and* release — shipped an empty `lib/arm64-v8a/`. AUTOFIX only
   re-validates the payload; it cannot download an engine (by design, see ADR-021), so
   the button was guaranteed to fail.

2. **`resources/lib/<abi>/` was no longer packaged at all.** AGP does not copy that
   directory into the APK's `lib/<abi>/` any more, so the wrap.sh-style route ADR-021
   chose produced an APK whose `lib/arm64-v8a/` held only a dependency's
   `libandroidx.graphics.path.so` — and no engine. Verified by inspecting the built
   artifact in CI, not by reading the config.

3. **The payload names could never have been extracted.** Landing a file in the APK's
   `lib/<abi>/` is not enough. For a non-debuggable package the installer's
   `NativeLibrariesIterator` keeps only entries whose base name starts with `lib` and
   ends with `.so` (`frameworks/base` → `libs/androidfw/ApkParsing.cpp`,
   `ValidLibraryPathLastSlash()`, plus the `isFilenameSafe()` charset). `proot`,
   `loader` and even `libtalloc.so.2` all fail it, so they were packaged and then
   silently dropped — `nativeLibraryDir` stayed empty. `debuggable` bypasses the
   filter, so a locally-built debug APK would have looked fine and hidden the bug.

**Decision:**

- Ship the payload from `app/src/main/jniLibs/<abi>/`, the supported path, which
  packages every `*.so` it finds. Avoiding `jniLibs` was only ever necessary because
  AGP drops non-`.so` files there — which no longer applies once the executables are
  named `lib*.so`.
- Ship every payload file as `lib*.so`: `libproot.so`, `libprootloader.so`,
  `libtalloc.so`, `libandroid-shmem.so`, `libtini.so`, `libbusybox.so`. The names are
  arbitrary to PRoot (exec'd by absolute path, loader pinned via `$PROOT_LOADER`), but
  `libtalloc`'s `SONAME`/`DT_NEEDED` are rewritten to match so the bionic linker still
  resolves them — `scripts/fetch-engine.sh` does this with `patchelf`, falling back to
  an in-place, length-preserving `.dynstr` patch.
- The **build itself** fetches the payload: a `fetchEnginePayload` Gradle task runs
  `scripts/fetch-engine.sh` when no `lib*.so` is present, so a plain
  `./gradlew assembleDebug` on a fresh clone produces a working APK instead of relying
  on a CI-only step someone forgot to add. It is skipped once the payload exists (no
  network on rebuilds) and can be bypassed with `-PskipEngineFetch=true` /
  `SKIP_ENGINE_FETCH=1`. The script refuses to stage anything not named `lib*.so`.
- `assembleRelease`/`bundleRelease` depend on a `verifyEnginePayload` task that fails
  the build on an empty or misnamed payload; debug builds only warn, so UI work does
  not require fetching GPL binaries.
- `EngineInstaller` resolves both the new and historic names (debug builds and old
  `filesDir` dev installs still carry the latter) and, when nothing is found, reports
  any files present that are *not* `lib*.so` — the directory looks correct on disk, so
  the message has to name the real cause.

- `scripts/verify-apk-engine.sh` opens the **built APK** after `assembleDebug` and
  asserts `lib/<abi>/libproot.so` is present and that nothing there would be skipped by
  the installer, emitting `::error::` so CI annotations carry the reason. The
  source-tree checks alone would have passed for all three defects above.

**Consequences:** Release APKs now actually contain a runnable engine, and all three
failure modes are caught at build time instead of on a user's device. `NativeSetup`
constants are the single source of truth for the names, shared by the installer, the
fetch script (by convention) and the tests that assert every shipped name survives the
installer filter.

---

## ADR-023 — Engine dependencies ship from their own Termux packages, fetch fails hard (accepted)

**Context:** Devices still refused to start the guest with

> PRoot 'arm64-v8a' payload is present but its shared library dependencies are
> missing: libtalloc.so, libandroid-shmem.so

`EngineInstaller` was doing its job (ADR-022's on-device validation), which meant the
APK really did carry `libproot.so` + `libprootloader.so` but not the two `.so` deps.
The cause was in `scripts/fetch-engine.sh`: Termux's `proot` package declares
`TERMUX_PKG_DEPENDS="libandroid-shmem, libtalloc"` — the deps are **separate
packages** (`libtalloc_2.4.3`, `libandroid-shmem_0.7`), and Termux `.deb`s only ship
their own files. The script looked for `lib/libtalloc.so.2` and
`lib/libandroid-shmem.so` *inside the proot `.deb`*, found neither, printed a
WARNING, skipped them and exited 0. The payload directory was non-empty, so the
Gradle "is there any `lib*.so`" fetch-gate and the APK check (which only asserted
`libproot.so`) both passed. Every APK built from that script was broken, and CI had
no way to notice.

**Decision:**

- `fetch-engine.sh` downloads **three** `.deb`s — `proot`, `libtalloc`,
  `libandroid-shmem` — extracts each payload from its own package, and stages
  `libproot.so`, `libprootloader.so`, `libprootloader32.so` (optional),
  `libtalloc.so` (patched from `libtalloc.so.2`), `libandroid-shmem.so`. The
  aarch64 `.deb`s are SHA-256 pinned by default, overridable via
  `PROOT_DEB_URL`/`TALLOC_DEB_URL`/`SHMEM_DEB_URL` and the matching `*_SHA256`
  variables (empty string disables pinning).
- Missing **required** files now abort the script (exit 1) instead of
  warn-and-skip; the ELF magic/`e_machine` sanity check covers every staged file,
  dependencies included.
- The Gradle fetch gate re-runs `fetch-engine.sh` until the **required set** is
  complete, not merely until *some* `lib*.so` exists — a half-staged payload no
  longer skips the fetch forever.
- `scripts/verify-payload.sh` (new) hard-fails when any required file is missing in
  `jniLibs/<abi>/`; `scripts/verify-apk-engine.sh` now asserts all four required
  entries inside the built APK, for both debug and release outputs. CI workflows run
  fetch → payload check → build → APK check explicitly, so a broken engine pipeline
  fails the job instead of shipping.
- Device-side guidance: uninstall before reinstalling a rebuilt APK —
  `adb install -r` can keep an older install's extracted payload, masking the fix.

**Consequences:** An APK can no longer be produced with a half-present engine. The
four-stage pipeline (fetch → `jniLibs` gate → APK gate → on-device `EngineInstaller`
check) catches the failure at the earliest stage where it can still be fixed
automatically, and CI fails loudly at every later stage. Future Termux version bumps
need only update the three version variables (the SHA-256 pins force the bump to be
deliberate).

---

*Open items from `ARCHITECTURE.md` §14 are tracked here as they resolve.*

---

## ADR-024 — Terminal window: session-owned transcript, VT-lite rendering, honest line mode (accepted)

**Context:** The terminal screen (ADR-009's pipe-backed fallback) was unusable in five
independent ways, all visible on a real device:

1. **The screen owned the reader.** `TerminalScreen` built its own
   `remember(session) { PtySession(it, scope) }` on a `rememberCoroutineScope()`, so
   navigating away disposed the only reader of the guest's stdout. Output produced while
   the window was closed was lost, and once the 64 KiB pipe filled the shell itself
   blocked. Coming back built a fresh session with an empty buffer, so the scrollback
   vanished on every navigation.
2. **Control traffic was rendered as text.** `Text(output)` printed the raw byte stream:
   every `\r` frame of an `apt` progress bar, `ESC [ … m` colour codes and `ESC [ K`
   erases appeared literally, and a shorter redraw left the previous frame's tail behind.
3. **Nothing was echoed.** The guest's stdio is a pipe, not a PTY, so bash reads whole
   lines without readline: it never echoes what it is given, and it prints no prompt
   either (`PS1` belongs to interactive shells). The window showed command output with no
   command line and no prompt anywhere.
4. **Input and scrolling misbehaved.** `send()` wrote to a closed pipe from a UI
   coroutine (uncaught `IOException` once the guest exited);
   `LaunchedEffect(output) { scroll.animateScrollTo(scroll.maxValue) }` read `maxValue`
   before the new text was measured, so it always stopped one chunk short, and it yanked
   the reader back to the bottom on every chunk. The title bar read `session?.isAlive()`
   during composition, which observes nothing.
5. **A restart could not restart.** `GuestRuntime.start()` killed the previous process
   but left the instance `RUNNING`, so `manager.start()` died on
   `Illegal VmState transition: RUNNING -> STARTING` and leaked the old terminal.

**Decision:**

- **One `PtySession` per guest session, owned by `GuestRuntime`** — attached before the
  session is handed out, detached in `stop()` — mirrored into
  `HomeViewModel.terminalState: StateFlow<TerminalSnapshot>`. `TerminalScreen` is a pure
  view of that snapshot: it can be opened, closed and reopened without touching the
  guest, and the stdout pipe always has a reader (desktop sessions included).
- **`TerminalBuffer`** is a pure-JVM screen + scrollback: LF, `\r` overwrite, `\b`, tab
  stops, `ESC [ K` / `ESC [ J` erases, and every other CSI/OSC/charset sequence dropped.
  It is bounded (1000 lines × 1000 columns, oldest lines and the tail of over-long lines
  kept) and caches its rendered string until the next mutation.
- **Incremental UTF-8 decoding** with the partial trailing sequence carried to the next
  read, so a multi-byte character split across two reads is not replaced by two `?`.
- **Local echo** (`"$ " + line`) because a pipe-backed shell cannot echo, and
  **`END SHELL` closes stdin** — the only end-of-input a pipe-backed bash honors. There is
  deliberately no `^C` key: with no line discipline a control byte is data, so it would be
  inserted into the pending command line. The window says what it is
  (`Line mode: no PTY, so commands run on Send and ^C cannot interrupt`) instead of
  pretending to be an interactive terminal.
- **Scrolling follows the tail only while the window is at the bottom**, and waits one
  frame before reading `maxValue` so the new text is measured first.
- **A restart goes through `GuestRuntime.stop(id)`**, so the state machine, the process
  and the terminal all see it.

**Consequences:** The rendering rules are unit-testable without Android: 18 tests for the
buffer, 9 for the session (including the split multi-byte read and the dead-shell paths)
and 2 more in `GuestRuntimeTest` for the ownership and restart rules. The reader is a
daemon thread behind an injectable factory, so no coroutine scope has to be threaded
through `GuestRuntime`. Moving to a real PTY (`libpvmnative`'s `openpty`) later means
passing `echoInput = false` and forwarding control bytes — nothing in the UI changes.
