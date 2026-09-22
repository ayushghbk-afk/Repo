#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found; install Android platform-tools before running the smoke test." >&2
  exit 2
fi

./scripts/verify-payload.sh arm64-v8a
./gradlew assembleDebug --no-daemon
./scripts/verify-apk-engine.sh app/build/outputs/apk/debug arm64-v8a

# Uninstall FIRST: `adb install -r` can keep an older install's extracted native
# payload around, which is exactly how a fixed engine "doesn't take effect" on a
# device that has seen previous broken builds.
adb uninstall com.lenix >/dev/null 2>&1 || true
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.lenix/com.lenix.ui.MainActivity

echo "Smoke test: launch command sent. Verify the Lenix home screen on the device."
