#!/usr/bin/env bash
# Sign a Lenix RootFS manifest with an Ed25519 release key (docs/ROOTFS_SYSTEM.md §3).
#
#   scripts/sign-rootfs-manifest.sh <manifest.json> <private-key.pem>
#
# Rewrites the manifest's "signature" member in place (all other bytes, including
# formatting, are preserved) to:
#
#   "signature": "ed25519:" + base64( key-id(8) || Ed25519 signature(64) )
#
# where the signed message is the manifest's Lenix Canonical JSON (v1) — the document
# with "signature" removed, keys sorted, no whitespace: see scripts/canonical-json.py,
# which is the mirror of the Kotlin verifier.
#
# The key id is the first 8 bytes of sha256(raw public key). Equivalent minisign flow:
#   python3 scripts/canonical-json.py manifest.json > payload
#   minisign -S -l -m payload -s ~/.minisign/minisign.key     # -l = raw, not pre-hashed
#   printf 'ed25519:%s\n' "$(sed -n 2p payload.minisig)"     # key-id || signature
set -euo pipefail

MANIFEST="${1:-}"
PRIVATE_KEY="${2:-${LENIX_ROOTFS_SIGNING_KEY:-}}"
if [[ -z "$MANIFEST" || -z "$PRIVATE_KEY" ]]; then
  echo "usage: $0 <manifest.json> <private-key.pem>" >&2
  echo "       (the private key may also come from \$LENIX_ROOTFS_SIGNING_KEY)" >&2
  exit 2
fi
for tool in openssl python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$tool is required" >&2; exit 2; }
done
[[ -f "$MANIFEST" ]] || { echo "no such manifest: $MANIFEST" >&2; exit 1; }
[[ -f "$PRIVATE_KEY" ]] || { echo "no such private key: $PRIVATE_KEY" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

python3 "$SCRIPT_DIR/canonical-json.py" "$MANIFEST" > "$WORK/payload"

openssl pkeyutl -sign -inkey "$PRIVATE_KEY" -rawin \
  -in "$WORK/payload" -out "$WORK/signature"

openssl pkey -in "$PRIVATE_KEY" -pubout -outform DER | tail -c 32 > "$WORK/pub"

python3 - "$MANIFEST" "$WORK/signature" "$WORK/pub" <<'PY'
import base64, hashlib, json, re, sys

manifest_path, signature_path, pub_path = sys.argv[1:4]
raw_public = open(pub_path, "rb").read()
signature = open(signature_path, "rb").read()
assert len(raw_public) == 32 and len(signature) == 64, "unexpected key/signature sizes"

key_id = hashlib.sha256(raw_public).digest()[:8]
field = "ed25519:" + base64.b64encode(key_id + signature).decode()

text = open(manifest_path, encoding="utf-8").read()
if '"signature"' not in text:
    sys.exit('the manifest has no "signature" member to write into')
# Replace only the value of the signature member; the payload excludes it, so the
# signed bytes are unaffected by whatever formatting the file uses.
updated, count = re.subn(
    r'("signature"\s*:\s*")([^"]*)(")',
    lambda m: m.group(1) + field + m.group(3),
    text,
    count=1,
)
if count != 1:
    sys.exit('could not rewrite the "signature" member')
open(manifest_path, "w", encoding="utf-8").write(updated)

# The document must still parse, and the payload must be byte-identical.
json.loads(updated)
print(f"signed key id {key_id[::-1].hex().upper()}")
PY

python3 "$SCRIPT_DIR/canonical-json.py" "$MANIFEST" > "$WORK/payload-after"
cmp "$WORK/payload" "$WORK/payload-after" >/dev/null \
  || { echo "internal error: signing changed the canonical payload" >&2; exit 1; }

echo "wrote signature into $MANIFEST"
