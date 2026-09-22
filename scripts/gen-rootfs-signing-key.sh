#!/usr/bin/env bash
# Generate a Lenix RootFS signing key pair (Ed25519, minisign-compatible public key).
#
#   scripts/gen-rootfs-signing-key.sh <output-dir>
#
# Produces:
#   <dir>/lenix-release.key.pem   the PRIVATE key — never commit it
#   <dir>/lenix-release.pub       the PUBLIC key in minisign's own file format, which
#                                 is what gets embedded in the APK at
#                                 app/src/main/assets/rootfs/keys/<name>.pub
#
# The key id (the 8 bytes minisign calls "public key <hex>") is derived as the first 8
# bytes of sha256(raw public key) instead of minisign's random serial, so a key can be
# recreated from the private key alone (see scripts/sign-rootfs-manifest.sh).
#
# If you prefer minisign itself, `minisign -G -p lenix-release.pub -s lenix-release.key`
# works too: the app parses minisign public key files verbatim.
set -euo pipefail

OUT_DIR="${1:-}"
if [[ -z "$OUT_DIR" ]]; then
  echo "usage: $0 <output-dir>" >&2
  exit 2
fi

command -v openssl >/dev/null 2>&1 || { echo "openssl is required" >&2; exit 2; }
mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

KEY="$OUT_DIR/lenix-release.key.pem"
PUB="$OUT_DIR/lenix-release.pub"
[[ -e "$KEY" ]] && { echo "$KEY already exists; refusing to overwrite" >&2; exit 1; }

umask 077
openssl genpkey -algorithm ED25519 -out "$KEY"

# minisign public key file: "Ed" || key-id(8) || raw public key(32), base64'd.
openssl pkey -in "$KEY" -pubout -outform DER \
  | tail -c 32 \
  | python3 -c '
import base64, hashlib, sys
raw = sys.stdin.buffer.read()
assert len(raw) == 32, f"unexpected public key length {len(raw)}"
key_id = hashlib.sha256(raw).digest()[:8]
blob = b"Ed" + key_id + raw
print("untrusted comment: minisign public key " + key_id[::-1].hex().upper())
print(base64.b64encode(blob).decode())
' > "$PUB"

echo "private key: $KEY  (keep it secret, keep it safe)"
echo "public key:  $PUB"
echo
echo "Embedding it in the APK:"
echo "  cp $PUB app/src/main/assets/rootfs/keys/lenix-release-2026.pub"
echo "Signing a manifest with it:"
echo "  scripts/sign-rootfs-manifest.sh app/src/main/assets/rootfs/<manifest>.json $KEY"
