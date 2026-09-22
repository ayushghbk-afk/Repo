# Building Lenix

## Prerequisites

- **Java Development Kit (JDK) 17** or higher
- **Android SDK** with API level 37
- **Gradle 9.5** (included via wrapper)

## Build Commands

### Debug Build

```bash
./gradlew assembleDebug
```

The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build

```bash
./gradlew assembleRelease
```

The APK will be at: `app/build/outputs/apk/release/app-release-unsigned.apk`

### Clean Build

```bash
./gradlew clean
```

### Build with Dependencies Report

```bash
./gradlew dependencies --configuration debugRuntimeClasspath
```

## PRoot engine payload

The APK's PRoot engine comes from Termux's official packages and is **not committed
to git** (GPL binaries). Fetch and verify it before building:

```bash
./scripts/fetch-engine.sh arm64-v8a    # proot + libtalloc + libandroid-shmem
./scripts/verify-payload.sh arm64-v8a  # hard-fails if any required file is missing
```

`fetch-engine.sh` downloads **three** Termux packages: `proot` itself plus
`libtalloc` and `libandroid-shmem`. The latter two are separate `.deb`s — proot only
*declares them as dependencies* — so a fetch that unpacked just the proot package
produced APKs that failed on-device with
*"payload is present but its shared library dependencies are missing"*.

`assembleDebug` runs the fetch automatically when the payload is incomplete
(`-PskipEngineFetch=true` skips it). After the build, check what actually made it
into the APK:

```bash
./scripts/verify-apk-engine.sh app/build/outputs/apk/debug arm64-v8a
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "lib/arm64-v8a"
```

Expected under `app/src/main/jniLibs/arm64-v8a/`:

```
libproot.so            # required — the engine
libprootloader.so      # required — PRoot's static guest loader
libprootloader32.so    # optional — 32-bit guests only
libtalloc.so           # required — proot's shared-library dependency
libandroid-shmem.so    # required — proot's shared-library dependency
```

**Stale installs:** `adb install -r` can keep an older install's extracted native
payload around, so a fixed engine appears not to take effect. Uninstall the app
before reinstalling a rebuilt APK (`scripts/smoke.sh` does this).

## RootFS Manifest Signing

Every manifest the app reads — including the one bundled in `app/src/main/assets/rootfs/` —
must carry an Ed25519 signature made by a key listed in `app/src/main/assets/rootfs/keys/`
(`docs/ROOTFS_SYSTEM.md` §1, ADR-017). This applies to debug builds too: an unsigned manifest
fails install with `SIGNATURE_FAILED` ("not signed by a key this build trusts") — it is not a
release-only check.

```bash
# one-off: create the release key material (keep the .key.pem OUT of git)
./scripts/gen-rootfs-signing-key.sh ~/lenix-signing
cp ~/lenix-signing/lenix-release.pub app/src/main/assets/rootfs/keys/

# after editing a manifest — rewrites its "signature" member over the canonical payload
./scripts/sign-rootfs-manifest.sh app/src/main/assets/rootfs/debian-bookworm-aarch64.json ~/lenix-signing/lenix-release.key.pem

# what the verifier sees, and what the signature covers
./scripts/canonical-json.py app/src/main/assets/rootfs/debian-bookworm-aarch64.json
```

Signing works with `openssl` + `python3` alone (no minisign binary needed), and is equivalent
to `minisign -S -l -m <canonical-payload>` with the same key. CI re-runs
`BundledRootfsManifestTrustTest`, which verifies the shipped manifest against the shipped key,
so an edit that forgets to re-sign fails the build rather than shipping an uninstallable image.
Rotating a key = add the new `.pub` to the assets directory (both keys are trusted while
manifests are re-signed), then delete the old one.

## CI/CD Build

GitHub Actions workflows:

- `.github/workflows/build-apk.yml` — builds the debug APK and uploads the artifact
- `.github/workflows/lint.yml` — runs `lintDebug` and unit tests
- `.github/workflows/release.yml` — builds release APK on a `v*` tag and publishes it

Build triggers:

1. Push to `main` branch
2. Push to any `arena/**` branch
3. Manual workflow dispatch

Scripts:

```bash
./scripts/build.sh   # assembleDebug
./scripts/lint.sh    # lintDebug + testDebugUnitTest
ANDROID_HOME=$HOME/Android/Sdk ./scripts/smoke.sh  # install and launch on a connected device
```

## Android SDK Setup

If you don't have the Android SDK installed:

```bash
# Download Android command line tools
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
curl -o tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip tools.zip
mv cmdline-tools latest

# Accept licenses and install SDK components
export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
yes | sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

## Troubleshooting

### OutOfMemoryError

Increase JVM heap size in `gradle.properties`:
```
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
```

### Gradle Sync Fails

Try:
```bash
rm -rf ~/.gradle/caches
./gradlew --stop
./gradlew assembleDebug
```

### Missing NDK

NDK is not required for Stage 1. Native code integration comes in Phase 4.
