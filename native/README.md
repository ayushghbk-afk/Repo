# Lenix Native

Host-side engine (PRoot, BusyBox, PTY helper, extractor). GPL binaries are
**executed**, never linked (ADR-001). `libpvmnative.so` is Apache-2.0 JNI for
`openpty` and process-group kill.

## Layout

```
native/
├── CMakeLists.txt      # libpvmnative (pty + launcher)
├── pty/pty.c
├── launcher/launcher.c
└── README.md
```

## Engine binaries (proot / loader / tini / busybox / libtalloc…)

Drop arm64-v8a builds into `app/src/main/jniLibs/arm64-v8a/` (not `jniLibs/`:
Android Studio only packages `*.so` from there; `jniLibs/<abi>/` is the
documented wrap.sh-style route into the APK's `lib/<abi>/`) and `scripts/fetch-engine.sh`
can fetch+bundle the official Termux PRoot package for you:

```bash
./scripts/fetch-engine.sh
```

The engine is **not** copied to `filesDir/native/` at runtime: Android 10+ SELinux
denies `execve` of app-data files (`app_data_file` lacks `execute_no_trans`), so a
filesDir copy fails with `error=13, Permission denied` even at mode 0700. Shipping the
binaries as native library payloads (`jniLibs/<abi>/` → `/data/app/<pkg>/lib/<abi>/`,
SELinux `apk_data_file` with `x_file_perms`) keeps exec legal; PRoot's static `loader`
must ship there too because `$PROOT_LOADER` is pinned to it. See docs/DECISIONS.md
ADR-021.

Required payload for `arm64-v8a` (next to any libs you already ship):

```
app/src/main/jniLibs/arm64-v8a/
├── libproot.so            # bionic-linked PRoot (termux/proot fork)
├── libprootloader.so      # PRoot static runtime loader
├── libtalloc.so           # PRoot bionic dependency
├── libandroid-shmem.so    # PRoot bionic dependency
├── libtini.so             # optional, static (desktop mode)
└── libbusybox.so          # optional, bionic
```

**Every file must be named `lib*.so`, executables included.** Getting a file into the
APK's `lib/<abi>/` is only half the job: on a non-debuggable build the installer's
`NativeLibrariesIterator` extracts only entries whose base name starts with `lib` and
ends with `.so` (`frameworks/base` → `libs/androidfw/ApkParsing.cpp`,
`ValidLibraryPathLastSlash()`). A payload named `proot`, `loader` or `libtalloc.so.2`
(the `.2` suffix fails the check too) is packaged into the APK and then **silently
never extracted**, so `nativeLibraryDir` is empty and the app reports
`NATIVE_ENGINE_FAILED`. Debug builds skip that filter, which is why the bug only shows
up in release APKs. The names are arbitrary to PRoot — it is exec'd by absolute path
and its loader is pinned via `$PROOT_LOADER` — but `libtalloc`'s `SONAME`/`DT_NEEDED`
must be rewritten to match; `scripts/fetch-engine.sh` does that for you.

Until those files exist, START fails as `NATIVE_ENGINE_FAILED` with an actionable
message instead of pretending a guest is running.

Phase 6: PRoot argv + PTY/pipe terminal + JNI stubs.
Phase 7: Openbox + Xvnc launch line + loopback RFB client (Raw encoding).
