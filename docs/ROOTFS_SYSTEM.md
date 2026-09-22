# ROOTFS SYSTEM — Distribution Format & Install Pipeline

How a Linux root filesystem gets from the builder to a running instance inside the
app — signed, resumable, atomic, and installable on a phone without root.

---

## 1. Image model: layered, content-addressed, signed

```
dist/rootfs/manifests/ubuntu-noble-aarch64-v1.2.0.json      (signed)
dist/rootfs/layers/
├── ubuntu-noble-aarch64-base-v1.tar.zst
├── ubuntu-noble-aarch64-base-v1.tar.zst.sha256
├── ubuntu-noble-aarch64-desktop-openbox-v1.tar.zst
└── ubuntu-noble-aarch64-desktop-openbox-v1.tar.zst.sha256
```

- **Two layers per distro × arch × DE:** `base` (userspace + package manager) and
  `desktop` (Xvnc + DE + our scripts). This lets us rebuild the desktop flavor without
  re-downloading base, and swap DEs (openbox ↔ lxqt ↔ xfce) with one small layer.
- Layers are **immutable and content-addressed** by `sha256`. The app caches them in
  `cache/layers/<sha256>.tar.zst` and reuses them across instances and re-installs.
- Every manifest is **Ed25519-signed**, with the public keys embedded in the APK as
  minisign key files (`assets/rootfs/keys/*.pub`; a directory, so rotation is "add a file,
  drop a file"). The signature covers the **canonical payload** — the whole manifest body
  with the `signature` member removed and object keys sorted — including each layer's
  `sha256`, so a tampered digest, URL or size fails verification *before* a byte is
  downloaded or extracted (ADR-017). Keys are minisign-format and signatures are minisign's
  raw (non-pre-hashed) mode, i.e. `minisign -S -l -m <payload>`; the `assets` location and
  the injected key ring are what make the check unit-testable without a `Context`.

---

## 2. Installer pipeline (end to end)

```
InstallerViewModel / InstallTask  (single state machine, resumable)
│
├─[1] RESERVING     fetch manifest (HTTPS + pinning), verify signature,
│                   re-check storage: require free ≥ 2.2 × total uncompressed size,
│                   create instance dir skeleton, persist state.json
│
├─[2] DOWNLOADING   for each layer, in order:
│                   cache/layers/<sha256>.tar.zst.part
│                     ├─ OkHttp GET, Accept-Ranges resume from .part size
│                     ├─ 4 MiB chunks, ETag/If-Range validation, retry/backoff
│                     ├─ progress = bytesDone / bytesTotal (persisted every 5 s)
│                     └─ on complete: sha256 == manifest ? rename to .tar.zst
│                                             : DELETE .part, requeue (max 3)
│
├─[3] VERIFYING     recompute sha256 of cached layers + check manifest signature
│
├─[4] EXTRACTING    streaming into instances/<id>/.tmp/rootfs/
│                     └─ JNI: libarchive read(zstd stream) → write_disk
│                        - preserves permissions/symlinks/mtimes
│                        - owner normalized to app uid (PRoot fakes root anyway)
│                        - ignores xattrs Android can't set (security.selinux etc.)
│                        - progress callback: bytesIn / archiveSize
│                   base first, then desktop (desktop overlays base)
│
├─[5] POST_INSTALL  idempotent guest bootstrap, run inside proot:
│                     /usr/local/bin/pvm-bootstrap (from the desktop layer)
│                       - set locale/LC_ALL, timezone from device
│                       - create /root/.vnc/passwd placeholder, dotfiles, .bashrc
│                       - fix /etc/resolv.conf via bound host file
│                       - verify: `Xvnc -version`, `openbox --version`
│                       - write /run/pvm-bootstrap-ok marker
│
├─[6] COMMIT        atomic: rename .tmp/rootfs → rootfs/
│                   write config.json (allocatedBytes, size, state=ready)
│                   delete .part files; log layer -> instance mapping
│
└─[7] READY         HomeScreen shows [ Launch Desktop ]
```

**As implemented (Phases 3–5).** `[1]` verifies the manifest signature *first* — before the
storage precheck, so a rejected manifest never reserves anything (`HomeViewModel` calls the
same verifier, so a bad manifest is an error state rather than a silent no-op). `[2]`–`[3]`
are `data.download.ResumableDownloader` + `LayerCache` (ADR-015) plus a digest recompute on
the cached file. `[4]` is `installer.extract.RootfsExtractor` — pure JVM
(`commons-compress` + `org.tukaani:xz`), no JNI yet, and only `tar.xz`/`tar.gz`/`tar`;
`tar.zst` is refused with `UNSUPPORTED_COMPRESSION` until `libpvmextract` lands in Phase 6
(ADR-018). It keeps modes/symlinks/mtimes, normalizes ownership to the app uid implicitly,
drops setuid/setgid, ignores xattrs, skips and counts device nodes/FIFOs, enforces a
tar-slip-proof path policy, and caps both expansion and entry count against the manifest's
declared size; progress is bytes written per layer, cancellation is checked per entry. It
also lifts a single top-level wrapper directory to the root — proot-distro's layer tars the
build dir by name (`debian-aarch64/`), and without that step every member lands one level
deep so the instance has nothing at `/` to boot (no `/bin/sh`, no `/etc`). `[6]`
is the rename in `RootfsInstaller`, which also writes `rootfs.json` (per-layer
digest/counts/signing key id) and clears the interrupted-install checkpoint. `[5]`
(post-install guest bootstrap) belongs to the Phase 6 engine and is not run yet. Kill during
`[4]`: the staging tree is discarded on any failure, so the next attempt re-extracts from the
still-cached layers — download state survives, extraction state does not.

**Failure semantics:** every step is idempotent. Killed during download → resume at
byte X. Killed during extraction → staging dir is cleaned on next run, extraction
restarts (cost: a few minutes worst-case; the alternative — per-file resume of tar —
is not worth the complexity). Killed during commit → the atomic rename guarantees
either a complete rootfs or nothing.

**Cancel/delete:** user cancels → delete `.tmp`, `.part`, release reservation.
Completed layers stay in the content-addressed cache — they are immutable and
verified, so the next attempt reuses them.

**Implementation status (Phase 3):** steps [1]–[3] and [6]–[7] are implemented in
`installer.RootfsInstaller` + `data.download.ResumableDownloader` (ADR-015).
Differences from the sketch above, all recorded in ADR-015: cache files are named
`cache/layers/<sha256>.layer` (one canonical name per digest), ETags are persisted
as `<sha256>.layer.etag` sidecars for `If-Range`-validated resume across process
death, download progress checkpoints live in `instances/<id>/state.json`
(`data.JsonInstallStateStore`), and steps [4]–[5] are a staging stub — verified
layers are copied into `instances/<id>/rootfs/` until real streaming extraction
(Phase 5) and the guest bootstrap (later phases) land. The v0.1 layer source is
pinned in the bundled manifest (see §7).

---

## 3. Manifest schema (`manifest.json`, signed)

```json
{
  "schemaVersion": 1,
  "id": "ubuntu-noble-aarch64",
  "distro": "ubuntu",
  "codename": "noble",
  "arch": "aarch64",
  "version": "1.2.0",
  "channel": "stable",
  "releasedAt": "2026-08-29T00:00:00Z",
  "compatibility": {
    "minAndroidSdk": 29,
    "minRamMb": 3072,
    "recommendedRamMb": 6144
  },
  "desktop": {
    "default": "openbox",
    "flavors": ["openbox", "lxqt", "xfce"]
  },
  "layers": [
    {
      "id": "base",
      "url": "https://releases.pocketvm.app/rootfs/ubuntu-noble-aarch64-base-v1.tar.zst",
      "sizeBytes": 182000000,
      "uncompressedBytes": 1020000000,
      "sha256": "…",
      "compression": "zstd",
      "zstdLevel": 19
    },
    {
      "id": "desktop-openbox",
      "url": "https://releases.pocketvm.app/rootfs/ubuntu-noble-aarch64-desktop-openbox-v1.tar.zst",
      "sizeBytes": 140000000,
      "uncompressedBytes": 980000000,
      "sha256": "…",
      "compression": "zstd",
      "zstdLevel": 19
    }
  ],
  "install": {
    "estimatedFreeGb": 4.5,
    "bootCommand": "/usr/bin/tini -s -- /usr/local/bin/pvm-entry"
  },
  "buildinfoUrl": "https://releases.pocketvm.app/rootfs/ubuntu-noble-aarch64-v1.2.0.buildinfo",
  "signature": "ed25519:base64…"
}
```

The app pins the channel URL + public key; the manifest tells it everything else
(URLs, sizes, hashes, DE options, RAM requirements).

**As implemented (Phases 3–5).** `RootfsManifestParser` rejects anything the installer
would have to guess about: `schemaVersion` must be the supported one,
`id`/`distro`/`version` and `install.bootCommand` must be non-blank, 1–8 layers with
unique ids, and a `https:` URL (`http:` only for a loopback host — how the JVM tests
serve archives and how a developer mirrors a candidate RootFS locally), a normalized
64-hex `sha256` (lowercase accepted, `Digests.isSha256Hex`), a compression the layer's
suffix agrees with, and `sizeBytes > 0` with `uncompressedBytes ≥ sizeBytes`.
`signingKeyVersion` is advisory — trust comes from the key id inside `signature`, which
must name a key in the ring (a mismatch is reported with both key ids, never silently).
`signature` is `ed25519:` + base64(`key-id ‖ signature`) over the canonical payload
(`RootfsManifestCanonicalizer`, byte-for-byte mirrored by `scripts/canonical-json.py`),
and a document that is unsigned, placeholder-signed, signed by an untrusted key or whose
canonical form was altered verifies as `SIGNATURE_FAILED`. The `signature` member is
excluded from the payload, so re-signing never invalidates itself.

## 4. Storage layout & accounting

```
Android/data/com.pocketvm/files/            (app-private; also plain filesDir)
├── cache/layers/                content-addressed, shared across instances
├── instances/<id>/
│   ├── rootfs/                  fully materialized clone
│   ├── home/  etc/  vnc/  logs/
│   └── config.json  state.json
└── shared/                      host↔guest exchange (bind → /shared)
```

- **Install sizing:** `requiredFree = Σ uncompressedBytes × 1.2` (extraction headroom)
  + instance home/overhead. Checked twice (reserve + before commit).
- **Instance creation = fast clone** (not re-download): `rootfs/` is materialized from
  cached layers via `copy_file_range(2)`/`fallocate` (kernel side — fast on ext4/f2fs).
  Note: without reflink/COW filesystems this duplicates storage, so storage accounting
  must count all instances; do NOT hardlink (PRoot writes real files, a hardlink would
  corrupt the layer cache).
- **Cache eviction:** LRU per layer; an in-use layer is never evicted.
- **Backup:** `InstanceBackuper` streams `rootfs/` + `config.json` to a tarball via
  libarchive, exported with the Storage Access Framework (`ACTION_CREATE_DOCUMENT`)
  so the user owns the file; restore imports via `ACTION_OPEN_DOCUMENT`, verifies
  embedded `sha256`, and re-imports into a new instance. No backup is kept inside app
  storage (it would be deleted on uninstall).

## 5. Update channel & distro catalog

```
https://releases.pocketvm.app/channel.json   →  list of signed manifests (stable/beta)
```

- `DistroCatalog` fetches on app open (cache TTL 24 h; offline = last known catalog).
- Each distro card shows: name, DE options, download size, uncompressed size, min RAM,
  recommended RAM (from manifest), so the Install screen numbers are **always real**.
- Version pinning: an installed instance keeps its manifest version; updates are
  **new instances**, not in-place upgrades of a running rootfs (v1). Migration hooks
  come in Phase 4.

## 6. What the app downloads on first install

| Step | Download |
|---|---|
| Catalog | `channel.json` + pinned `manifest.json` (~2 KB) |
| Alpine (smallest) | base ~3 MB + desktop ~55 MB zstd — total ~60 MB |
| Debian + openbox | base ~70 MB + desktop ~65 MB — total ~135 MB |
| Ubuntu + XFCE | base ~95 MB + desktop ~85 MB — total ~180 MB |

Note: these are **compressed layer** sizes. The user-visible "install size" on the
card is the uncompressed guest fs, which is 10–20× larger (Alpine ~0.9 GB,
Ubuntu+XFCE ~3.0 GB) — the UI shows both numbers.

## 7. Layer build pipeline (CI)

```
rootfs.yml  (on push to dist/rootfs/builder, cron weekly, manual dispatch)
│
├─ ubuntu-latest (arm64):  Dockerfile.debian-arm64 / ubuntu-arm64
│    mmdebstrap --variant=minbase --arch=arm64 noble
│        → install base packages → tar --zstd → base layer
│    chroot + apt install desktop packages (tigervnc, openbox, xterm, dbus, fonts…)
│        → bake pvm-entry/pvm-bootstrap → tar → desktop layer
├─ ubuntu-latest (x86_64): same with --arch=amd64 (or qemu-user-static binfmt)
├─ Alpine: fetch minirootfs → apk add … → layers
│
├─ for each layer:
│    .buildinfo: builder commit, complete dpkg -l / apk list, SOURCE_DATE_EPOCH, zstd --long=27
│    sha256sum → *.sha256
├─ assemble manifest.json → minisign -S (release key, CI secret)
└─ upload to releases.pocketvm.app (or GitHub Releases for v1) + channel.json update
```

Reproducibility goal: same builder commit ⇒ identical layer `sha256` for the same
package versions (achieved with pinning + `SOURCE_DATE_EPOCH`; exact-identical is
nice-to-have, verify-by-hash is mandatory either way).

**Implementation status (v0.1):** no Lenix-built layer exists yet, so the bundled
manifest pins a real Debian bookworm arm64 rootfs published on GitHub Releases by
`termux/proot-distro`
(`debian-bookworm-aarch64-pd-v4.7.0.tar.xz`, sha256
`4baa3228…b8df8` — the same digest upstream pins in its own installer). The app
re-verifies that digest over the bytes it downloads; the layer is fetched at
runtime and never vendored into the APK (Debian's and proot-distro's own licenses
govern the guest content — GPL components are executed/downloaded as separate
artifacts, per `docs/NATIVE_BINARIES.md`). `uncompressedBytes` for this borrowed
layer is a conservative estimate feeding the storage precheck only. The pipeline
above (docker-export `debian:bookworm-slim` → xz → this repo's Releases →
regenerated manifest) replaces the pinned upstream layer once workflow-file
permissions allow committing the builder.

## 8. Offline / "Install from file" (Phase 4)

SAF-import a `.pvmimage` bundle (manifest + layers) so users on metered networks can
carry images on SD/USB. The verification path is identical (hash + signature) — the
file is just another source for the same layer cache.

## 9. Hosting notes (v1)

GitHub Releases for v1 (no infra cost): layers are immutable assets, URLs are
`releases/{tag}/…`, channel.json committed by `rootfs.yml`. Range support is required
for resumable downloads — GitHub Releases supports Range; if we move to our own
bucket, use CloudFront/S3 (Range natively supported).
