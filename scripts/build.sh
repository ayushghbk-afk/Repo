#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

./gradlew assembleDebug --no-daemon

echo
echo "APK: $ROOT/app/build/outputs/apk/debug/app-debug.apk"
