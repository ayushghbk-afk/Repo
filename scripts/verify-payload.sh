#!/usr/bin/env bash
# Hard gate on the source-tree engine payload, for CI and local pre-build checks.
#
# A build whose jniLibs/<abi>/ is missing any required engine file produces an APK
# that installs fine and then refuses to start the Linux guest with
#   "payload is present but its shared library dependencies are missing:
#    libtalloc.so, libandroid-shmem.so"
# so this check runs *before* Gradle. scripts/fetch-engine.sh fetches what's missing.
#
# Usage: scripts/verify-payload.sh [abi]     (default: arm64-v8a)
#        PAYLOAD_DIR=/path/to/jniLibs/abi scripts/verify-payload.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ABI="${1:-arm64-v8a}"
ENGINE_DIR="${PAYLOAD_DIR:-$ROOT/app/src/main/jniLibs/$ABI}"

# Required payload files (docs/NATIVE_BINARIES.md H1–H1c). Keep in sync with
# com.lenix.nativebridge.NativeSetup and scripts/verify-apk-engine.sh.
REQUIRED_FILES="libproot.so libprootloader.so libtalloc.so libandroid-shmem.so"
OPTIONAL_FILES="libprootloader32.so"

echo "Engine payload dir: $ENGINE_DIR"
ls -la "$ENGINE_DIR" 2>/dev/null || echo "(directory does not exist yet)"

status=0
for file in $REQUIRED_FILES; do
  if [ ! -f "$ENGINE_DIR/$file" ]; then
    echo "ERROR: Missing engine file: $file" >&2
    status=1
  fi
done

if [ "$status" -ne 0 ]; then
  echo "" >&2
  echo "The PRoot engine payload is incomplete. Termux's proot build depends on" >&2
  echo "libtalloc and libandroid-shmem, which ship in SEPARATE Termux packages —" >&2
  echo "an APK built now would install and then fail to start the guest." >&2
  echo "Fix: ./scripts/fetch-engine.sh $ABI" >&2
  exit 1
fi

# Non-fatal checks that still matter for diagnosis.
for file in $REQUIRED_FILES; do
  magic="$(od -An -tx1 -N4 "$ENGINE_DIR/$file" | tr -d ' \n')"
  if [ "$magic" != "7f454c46" ]; then
    echo "WARNING: $file is not an ELF binary (magic $magic) — the guest will not start." >&2
    status=1
  fi
done
for file in $OPTIONAL_FILES; do
  if [ ! -f "$ENGINE_DIR/$file" ]; then
    echo "INFO: optional $file absent (fine for $ABI guests)."
  fi
done

if [ "$status" -eq 0 ]; then
  echo "All PRoot engine dependencies are present."
fi
exit "$status"
