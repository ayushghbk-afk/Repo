#!/usr/bin/env bash
# Fetch the real PRoot engine binaries for the signed APK payload.
#
# The engine must land in the APK's lib/<abi>/ so Android's package manager extracts it
# to /data/app/<pkg>/lib/<abi>/ (ApplicationInfo.nativeLibraryDir) — the only
# app-reachable location where SELinux allows execve() on Android 10+ (W^X policy, see
# docs/DECISIONS.md ADR-021). A copy under assets/ -> filesDir/native/ FAILS with
# "error=13, Permission denied".
#
# The payload goes in app/src/main/jniLibs/<abi>/. The old wrap.sh-style route
# (src/main/resources/lib/<abi>/) is no longer packaged into lib/<abi>/ by AGP, which
# produced an APK whose lib/arm64-v8a/ held only dependency .so files and no engine.
# jniLibs is the supported path and packages every *.so it finds — which is exactly
# what the payload is named now.
#
# EVERY staged file is named lib*.so, executables included. Packaging a file into
# lib/<abi>/ is not enough to get it onto the device: for a non-debuggable package the
# installer's NativeLibrariesIterator keeps only entries whose base name starts with
# "lib" and ends with ".so" (frameworks/base, libs/androidfw/ApkParsing.cpp,
# ValidLibraryPathLastSlash()). Anything else — `proot`, `loader`, `libtalloc.so.2`,
# whose ".2" suffix also fails the check — is packaged and then silently dropped, so
# the app reports "No PRoot engine found" on release builds while debug builds work.
# Renaming is safe: PRoot is exec'd by absolute path and its loader is pinned via
# $PROOT_LOADER. The SONAME/DT_NEEDED entries are patched to match the new file names
# so the bionic linker still resolves the deps.
#
# libtalloc / libandroid-shmem are SEPARATE Termux packages, not files inside the
# proot .deb: proot declares them as TERMUX_PKG_DEPENDS, and Termux .debs only ship
# their own files. Fetching just the proot .deb and looking for its lib/ directory is
# the bug that produced APKs whose engine reported
#   "payload is present but its shared library dependencies are missing:
#    libtalloc.so, libandroid-shmem.so"
# This script downloads all three .debs (aarch64 SHA-256 pinned; override
# PROOT_DEB_URL / TALLOC_DEB_URL / SHMEM_DEB_URL, or set the matching *_SHA256
# variables to "" to disable pinning). Works on Ubuntu CI (ar + tar).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ABI="${1:-arm64-v8a}"
TARGET_DIR="$ROOT/app/src/main/jniLibs/$ABI"

# Termux pool layout is pool/main/<prefix4>/<pkg>/<pkg>_<version>_<debarch>.deb.
case "$ABI" in
  arm64-v8a)   DEB_ARCH="${DEB_ARCH:-aarch64}" ;;
  x86_64)      DEB_ARCH="${DEB_ARCH:-x86_64}" ;;
  armeabi-v7a) DEB_ARCH="${DEB_ARCH:-arm}" ;;
  x86)         DEB_ARCH="${DEB_ARCH:-i686}" ;;
  *) echo "ERROR: unsupported ABI $ABI" >&2; exit 1 ;;
esac

PKG_ROOT="https://packages.termux.dev/apt/termux-main/pool/main"

PROOT_VERSION="${PROOT_VERSION:-5.1.107.92}"
TALLOC_VERSION="${TALLOC_VERSION:-2.4.3}"
SHMEM_VERSION="${SHMEM_VERSION:-0.7}"

PROOT_DEB_URL="${PROOT_DEB_URL:-$PKG_ROOT/p/proot/proot_${PROOT_VERSION}_${DEB_ARCH}.deb}"
TALLOC_DEB_URL="${TALLOC_DEB_URL:-$PKG_ROOT/libt/libtalloc/libtalloc_${TALLOC_VERSION}_${DEB_ARCH}.deb}"
SHMEM_DEB_URL="${SHMEM_DEB_URL:-$PKG_ROOT/liba/libandroid-shmem/libandroid-shmem_${SHMEM_VERSION}_${DEB_ARCH}.deb}"

# SHA-256 pins for the aarch64 payloads (the ABI CI and the release APK ship). Pins are
# per-arch; other arches default to unpinned unless overridden. Set the variable to the
# empty string to disable pinning (e.g. when testing a newer Termux build).
if [ "$DEB_ARCH" = "aarch64" ]; then
  PROOT_DEB_SHA256="${PROOT_DEB_SHA256-1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9}"
  TALLOC_DEB_SHA256="${TALLOC_DEB_SHA256-ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da}"
  SHMEM_DEB_SHA256="${SHMEM_DEB_SHA256-0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6}"
else
  PROOT_DEB_SHA256="${PROOT_DEB_SHA256:-}"
  TALLOC_DEB_SHA256="${TALLOC_DEB_SHA256:-}"
  SHMEM_DEB_SHA256="${SHMEM_DEB_SHA256:-}"
fi

# Payload file names — these MUST match com.lenix.nativebridge.NativeSetup.
OUT_PROOT="libproot.so"
OUT_LOADER="libprootloader.so"
OUT_LOADER32="libprootloader32.so"
OUT_TALLOC="libtalloc.so"
OUT_SHMEM="libandroid-shmem.so"

WORK="$(mktemp -d)"
STAGE="$WORK/stage"
trap 'rm -rf "$WORK"' EXIT

# ---- .deb download + unpack ------------------------------------------------------

fetch_deb() {
  local url="$1" out="$2" sha256="$3"
  echo "Fetching $url ..."
  curl -fsSL --retry 3 --retry-delay 2 "$url" -o "$out"
  if [ -n "$sha256" ]; then
    actual="$(sha256sum "$out" | awk '{print $1}')"
    if [ "$actual" != "$sha256" ]; then
      echo "ERROR: $(basename "$out") SHA-256 mismatch (got $actual, want $sha256)." >&2
      exit 1
    fi
    echo "  sha256 OK"
  fi
}

# .deb = ar archive; member may be data.tar.xz / .gz / .zst depending on when Termux
# built the package. GNU ar returns 0 even when the named member is absent, so probe.
unpack_deb() {
  local deb="$1" dest="$2"
  mkdir -p "$dest"
  local member=""
  for candidate in data.tar.xz data.tar.gz data.tar.zst; do
    if ar t "$deb" 2>/dev/null | grep -qx "$candidate"; then
      member="$candidate"
      break
    fi
  done
  if [ -z "$member" ]; then
    echo "ERROR: unsupported .deb payload layout in $(basename "$deb") (no data.tar.{xz,gz,zst})." >&2
    exit 1
  fi
  ( cd "$dest" && ar x "$deb" "$member" )
  case "$member" in
    *.xz) tar -xf "$dest/$member" -C "$dest" ;;
    *.gz) tar -xzf "$dest/$member" -C "$dest" ;;
    *.zst)
      if ! tar --zstd -xf "$dest/$member" -C "$dest" 2>/dev/null; then
        echo "ERROR: this tar build cannot read zstd .deb payloads; use a newer tar." >&2
        exit 1
      fi
      ;;
  esac
}

fetch_deb "$PROOT_DEB_URL"  "$WORK/proot.deb"  "$PROOT_DEB_SHA256"
fetch_deb "$TALLOC_DEB_URL" "$WORK/talloc.deb" "$TALLOC_DEB_SHA256"
fetch_deb "$SHMEM_DEB_URL"  "$WORK/shmem.deb"  "$SHMEM_DEB_SHA256"

unpack_deb "$WORK/proot.deb"  "$WORK/proot"
unpack_deb "$WORK/talloc.deb" "$WORK/talloc"
unpack_deb "$WORK/shmem.deb"  "$WORK/shmem"

# Termux data.tar layout: ./data/data/com.termux/files/usr/{bin,lib,libexec}.
# `-print -quit` yields at most one line, so no pipe needed (and no SIGPIPE race).
termux_prefix() {
  local root="$1"
  find "$root" -type d -path '*/files/usr' -print -quit
}
PPREFIX="$(termux_prefix "$WORK/proot")"
TPREFIX="$(termux_prefix "$WORK/talloc")"
SPREFIX="$(termux_prefix "$WORK/shmem")"

# ---- staging ----------------------------------------------------------------------
# Stage first: only copy into the payload directory once everything validated.
copy_engine() {
  local name="$1" src="$2" required="${3:-0}"
  if [ ! -f "$src" ]; then
    if [ "$required" = "1" ]; then
      echo "ERROR: required payload $name not found at $src — the .deb layout changed?" >&2
      echo "       Re-check the Termux package version/paths or update this script." >&2
      exit 1
    fi
    echo "WARNING: optional payload $name not found at $src — skipping." >&2
    return 0
  fi
  mkdir -p "$STAGE"
  cp -L "$src" "$STAGE/$name"
  chmod 0755 "$STAGE/$name"
  echo "  + $name ($(wc -c < "$STAGE/$name") bytes)"
}

# libtalloc ships as lib/libtalloc.so.2 (a symlink to the versioned real file);
# cp -L dereferences it. Fall back to the first real libtalloc.so* if the layout moves.
# Only probe the dir when it exists — find on a missing dir prints its own error and
# would mask copy_engine's clear required-file diagnostic.
TALLOC_SRC="$TPREFIX/lib/libtalloc.so.2"
if [ ! -e "$TALLOC_SRC" ] && [ -d "$TPREFIX/lib" ]; then
  TALLOC_SRC="$(find "$TPREFIX/lib" -maxdepth 1 -type f -name 'libtalloc.so*' -print -quit)"
fi

echo "Extracting engine payload from the .debs ..."
copy_engine "$OUT_PROOT"    "$PPREFIX/bin/proot"            1
copy_engine "$OUT_LOADER"   "$PPREFIX/libexec/proot/loader" 1
copy_engine "$OUT_LOADER32" "$PPREFIX/libexec/proot/loader32" 0
copy_engine "$OUT_TALLOC"   "$TALLOC_SRC"                   1
copy_engine "$OUT_SHMEM"    "$SPREFIX/lib/libandroid-shmem.so" 1

# ---- ELF name fixups ----------------------------------------------------------
# libtalloc ships as libtalloc.so.2 with SONAME "libtalloc.so.2", and proot's
# DT_NEEDED points at that name. We ship it as libtalloc.so, so both strings have to
# be rewritten or the bionic linker fails with "library libtalloc.so.2 not found".
# patchelf is the clean route; fall back to a fixed-length in-place patch of the
# .dynstr entry ("libtalloc.so.2" -> "libtalloc.so\0\0") which keeps every offset
# stable and is therefore safe without a full ELF rewrite.
patch_soname_and_needed() {
  local file="$1" old="$2" new="$3"
  [ -f "$file" ] || return 0
  if command -v patchelf >/dev/null 2>&1; then
    if [ "$(basename "$file")" = "$OUT_TALLOC" ]; then
      patchelf --set-soname "$new" "$file" 2>/dev/null || true
    fi
    patchelf --replace-needed "$old" "$new" "$file" 2>/dev/null || true
    return 0
  fi
  # Fallback: byte-patch the string table in place. Only valid because
  # len(new) <= len(old); the remainder is NUL-padded so the string ends early.
  python3 - "$file" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
if len(new) > len(old):
    raise SystemExit(f"cannot patch {old} -> {new}: new name is longer")
data = bytearray(open(path, 'rb').read())
needle = old.encode() + b"\0"
repl = new.encode() + b"\0" * (len(needle) - len(new))
n = data.count(needle)
if n:
    data = bytearray(bytes(data).replace(needle, repl))
    open(path, 'wb').write(data)
print(f"    patched {n} occurrence(s) of {old} -> {new} in {path.split('/')[-1]}")
PY
}

if [ -d "$STAGE" ]; then
  echo "Fixing ELF dependency names to match the payload file names ..."
  for f in "$STAGE"/*; do
    patch_soname_and_needed "$f" "libtalloc.so.2" "$OUT_TALLOC"
  done
fi

# ---- sanity checks ----------------------------------------------------------------
# Real ELF, correct machine, for every staged file (the two .so deps included — a
# silently corrupt dependency breaks the guest exactly like a corrupt proot).
is_elf() {
  [ -f "$1" ] && [ "$(od -An -tx1 -N4 "$1" | tr -d ' \n')" = "7f454c46" ]
}

elf_machine() {
  local hex="$(od -An -tx1 -j18 -N2 "$1" | tr -d ' \n')"
  echo $(( 0x${hex:2:2}${hex:0:2} ))
}

case "$ABI" in
  arm64-v8a)      want_machine=183 ;;
  armeabi-v7a)    want_machine=40 ;;
  x86_64)         want_machine=62 ;;
  x86)            want_machine=3 ;;
esac

for name in "$OUT_PROOT" "$OUT_LOADER" "$OUT_TALLOC" "$OUT_SHMEM"; do
  file="$STAGE/$name"
  if ! is_elf "$file"; then
    echo "ERROR: staged $name is not an ELF — refusing to ship it." >&2
    exit 1
  fi
  machine="$(elf_machine "$file")"
  if [ "$machine" != "$want_machine" ]; then
    echo "ERROR: staged $name e_machine=$machine does not match $ABI ($want_machine)." >&2
    exit 1
  fi
done

# Every staged name must survive Android's installer filter, or the file ships in the
# APK and never reaches the device. Catch that here rather than on a user's phone.
bad=0
for f in "$STAGE"/*; do
  base="$(basename "$f")"
  case "$base" in
    lib*.so) ;;
    *) echo "ERROR: '$base' is not named lib*.so — Android will not extract it." >&2
       bad=1 ;;
  esac
done
[ "$bad" -eq 0 ] || exit 1

# A leftover payload under the old names would keep shipping dead weight in the APK
# (and confuse the on-device diagnosis), so clear them out.
if [ -d "$TARGET_DIR" ]; then
  for stale in proot loader loader32 tini busybox libtalloc.so.2; do
    if [ -e "$TARGET_DIR/$stale" ]; then
      echo "  - removing stale payload $stale (never extracted by Android)"
      rm -f "$TARGET_DIR/$stale"
    fi
  done
fi

mkdir -p "$TARGET_DIR"
cp -f "$STAGE"/* "$TARGET_DIR/"
chmod 0755 "$TARGET_DIR"/*

# Final gate: the APK is only complete with ALL FOUR required files. (The pre-fix
# script warned-and-skipped missing deps and still exited 0 — never again.)
missing=0
for name in "$OUT_PROOT" "$OUT_LOADER" "$OUT_TALLOC" "$OUT_SHMEM"; do
  if [ ! -f "$TARGET_DIR/$name" ]; then
    echo "ERROR: $TARGET_DIR/$name is still missing after staging." >&2
    missing=1
  fi
done
[ "$missing" -eq 0 ] || exit 1

echo "Done. Engine payload for $ABI:"
ls -la "$TARGET_DIR"
