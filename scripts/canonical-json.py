#!/usr/bin/env python3
r"""Lenix Canonical JSON (v1) for RootFS manifest signing.

This is the byte-for-byte mirror of
``app/src/main/java/com/lenix/installer/RootfsManifestCanonicalizer.kt`` — the two
implementations MUST agree, because a manifest is signed by this script (CI, or a
maintainer re-pinning a layer) and verified by the Kotlin one (on every install).
That agreement is pinned by unit tests: ``RootfsManifestCanonicalizerTest`` fixes the
exact canonical bytes of a fixture, and ``BundledRootfsManifestTrustTest`` verifies the
shipped manifest against the shipped public key, so a drift cannot ship silently.

Rules
-----
* the top-level ``signature`` member is dropped before serialization — it holds the
  signature this tool produces;
* object keys are emitted sorted ascending by UTF-16 code unit; no insignificant
  whitespace anywhere, no trailing newline, UTF-8 output;
* numbers keep their source token: they are parsed as ``Decimal`` so ``0.30`` stays
  ``0.30`` and ``1.0e7`` becomes ``1.0E+7``, which is exactly what Java's
  ``BigDecimal.toString()`` writes on the Kotlin side;
* strings escape only ``"``, ``\``, the C0 control characters (``\b \t \n \f \r``
  short forms, otherwise ``\u00xx``) and non-ASCII code points (``\uXXXX``, lowercase
  hex) — the same table as ``json.dumps(..., ensure_ascii=True)``.

Usage:
    python3 scripts/canonical-json.py app/src/main/assets/rootfs/<manifest>.json
"""

import json
import sys
from decimal import Decimal

SIGNATURE_MEMBER = "signature"


def canonical(value) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return "null"
    if isinstance(value, Decimal):
        # Both writers keep the source token (0.30 stays 0.30) and both spell an
        # exponent as 1.0E+7 / 1E-7, so no normalisation is needed — that symmetry
        # is why numbers are parsed as decimals in the first place.
        return str(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=True)
    if isinstance(value, list):
        return "[" + ",".join(canonical(item) for item in value) + "]"
    if isinstance(value, dict):
        return "{" + ",".join(
            json.dumps(key, ensure_ascii=True) + ":" + canonical(value[key])
            # Sorted by UTF-16 code unit — the exact order Java's String.compareTo
            # (and therefore the Kotlin side) produces, which only differs from
            # plain code-point order for surrogate pairs.
            for key in sorted(value.keys(), key=lambda k: k.encode("utf-16-be"))
        ) + "}"
    raise TypeError(f"unsupported JSON value: {type(value).__name__}")


def canonical_bytes(raw: bytes) -> bytes:
    document = json.loads(
        raw.decode("utf-8"),
        parse_float=Decimal,
        parse_int=Decimal,
    )
    if not isinstance(document, dict):
        raise ValueError("a RootFS manifest must be a JSON object")
    document.pop(SIGNATURE_MEMBER, None)
    return canonical(document).encode("utf-8")


def main(argv) -> int:
    if len(argv) != 2:
        print(f"usage: {argv[0]} <manifest.json> > payload", file=sys.stderr)
        return 2
    with open(argv[1], "rb") as handle:
        sys.stdout.buffer.write(canonical_bytes(handle.read()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
