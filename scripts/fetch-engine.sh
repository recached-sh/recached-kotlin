#!/usr/bin/env bash
# Download the recached release pinned in engine.properties, verify it, and
# stage its Android libraries and Kotlin bindings in engine/. A host library
# already staged by scripts/build-engine.sh (HOST_ONLY=1) is kept.
#
# This is what published AARs are built from, so they carry exactly the
# libraries the recached release published.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/engine"
prop() { sed -n "s/^$1=//p" "$ROOT/engine.properties"; }
URL="$(prop url)"
SHA256="$(prop sha256)"
VERSION="$(prop version)"

[[ -n "$URL" && -n "$SHA256" ]] || {
    echo "error: engine.properties pins no release (url/sha256 are empty)." >&2
    echo "       Use scripts/build-engine.sh to build from a checkout instead." >&2
    exit 1
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL "$URL" -o "$TMP/engine.zip"
echo "$SHA256  $TMP/engine.zip" | sha256sum -c - >/dev/null || {
    echo "error: checksum mismatch for $URL" >&2
    exit 1
}

rm -rf "$OUT/jniLibs" "$OUT/kotlin"
mkdir -p "$OUT"
unzip -q "$TMP/engine.zip" -d "$OUT"
echo "release $VERSION ($URL)" > "$OUT/SOURCE"
echo "Staged engine release $VERSION in $OUT"
