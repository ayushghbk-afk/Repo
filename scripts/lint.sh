#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

./gradlew lintDebug testDebugUnitTest --no-daemon
echo "Lint and unit tests passed."
