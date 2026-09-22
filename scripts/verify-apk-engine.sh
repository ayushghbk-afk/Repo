#!/usr/bin/env bash
# Verify a built APK actually carries a usable PRoot engine.
#
# The source-tree checks in app/build.gradle.kts can pass while the APK still ends up
# without an engine (a packaging rule drops the payload, a file gets renamed, the
# fetch runs after packaging). This opens the real archive and asserts that
# lib/<abi>/ holds every entry Android will actually extract AND the app needs —
# the property the whole fix depends on (docs/DECISIONS.md ADR-022).
#
# Required: libproot.so, libprootloader.so, libtalloc.so, libandroid-shmem.so
#           (libtalloc/libandroid-shmem come from SEPARATE Termux packages; an APK
#            without them installs fine and fails on-device with
#            "dependencies are missing: libtalloc.so, libandroid-shmem.so").
# Optional: libprootloader32.so
#
# Usage: scripts/verify-apk-engine.sh <apk-dir-or-file> [abi]
set -euo pipefail

TARGET="${1:?usage: verify-apk-engine.sh <apk-dir-or-file> [abi]}"
ABI="${2:-arm64-v8a}"

# Keep in sync with scripts/verify-payload.sh and NativeSetup.
REQUIRED="libproot.so libprootloader.so libtalloc.so libandroid-shmem.so"
OPTIONAL="libprootloader32.so"

if [ -d "$TARGET" ]; then
  mapfile -t APKS < <(find "$TARGET" -maxdepth 1 -name '*.apk' -type f | sort)
else
  APKS=("$TARGET")
fi

if [ "${#APKS[@]}" -eq 0 ]; then
  echo "ERROR: no APK found in $TARGET" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_PAYLOAD="$ROOT/app/src/main/jniLibs/$ABI"
# `|| true`: an absent source payload dir is a finding the checks below report, not a
# reason to die here (ls exits 2 on a missing dir and would kill the script under set -e).
staged="$(ls -A "$SRC_PAYLOAD" 2>/dev/null | tr '\n' ' ' || true)"
echo "Source payload ($SRC_PAYLOAD): ${staged:-(empty)}"

status=0
for apk in "${APKS[@]}"; do
  echo "Checking $(basename "$apk") for the $ABI engine payload ..."
  names="$(python3 - "$apk" "$ABI" <<'PY'
import sys, zipfile
apk, abi = sys.argv[1], sys.argv[2]
prefix = "lib/%s/" % abi
with zipfile.ZipFile(apk) as z:
    for n in z.namelist():
        if n.startswith(prefix) and not n.endswith("/"):
            print(n[len(prefix):])
PY
)"

  # Always report what the archive really holds — "no engine" has very different causes
  # depending on whether lib/<abi>/ is empty or full of the wrong names.
  listing="$(echo "$names" | tr '\n' ' ')"
  echo "  lib/$ABI/: ${listing:-(empty)}"

  if [ -z "$names" ]; then
    echo "::error::$(basename "$apk") has no entries under lib/$ABI/ at all — jniLibs/$ABI/ never made it into the APK." >&2
    status=1
    continue
  fi

  for file in $REQUIRED; do
    if ! echo "$names" | grep -qx "$file"; then
      echo "::error::$(basename "$apk") has no lib/$ABI/$file — the app will fail at START with NATIVE_ENGINE_FAILED. lib/$ABI/ contains: ${listing}" >&2
      status=1
    fi
  done

  for file in $OPTIONAL; do
    if ! echo "$names" | grep -qx "$file"; then
      echo "::warning::$(basename "$apk") has no lib/$ABI/$file (optional — only needed for 32-bit guests)." >&2
    fi
  done

  # Anything not named lib*.so is packaged but never extracted on a release build.
  stray="$(echo "$names" | grep -v '^lib.*\.so$' || true)"
  if [ -n "$stray" ]; then
    echo "::error::$(basename "$apk") packages entries under lib/$ABI/ that Android will not extract: $(echo "$stray" | tr '\n' ' ')" >&2
    status=1
  fi
done

if [ "$status" -eq 0 ]; then
  echo "Engine payload verified."
fi
exit "$status"
