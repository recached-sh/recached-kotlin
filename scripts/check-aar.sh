#!/usr/bin/env bash
# Assert the native library in the built :ffi AAR will load on a device.
#
#   - every ABI the package supports is present
#   - every LOAD segment is 16 KB aligned (Google Play, and 16 KB-page devices)
#   - no dependency is recorded as a path: a DT_NEEDED like
#     /home/<builder>/.../libfoo.so links fine and fails on every device
#   - nothing else is packed alongside it
#
# Usage: scripts/check-aar.sh [path/to.aar]
# Needs llvm-readelf (from the NDK) or readelf on PATH, or READELF set.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AAR="${1:-$ROOT/ffi/build/outputs/aar/ffi-release.aar}"
ABIS=(arm64-v8a armeabi-v7a x86_64)
LIB=librecached_mobile.so

READELF="${READELF:-}"
if [[ -z "$READELF" ]]; then
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    READELF="$(ls "$SDK"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | sort -V | tail -1 || true)"
    [[ -n "$READELF" ]] || READELF="$(command -v readelf)"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q "$AAR" -d "$WORK"

status=0
fail() { echo "FAIL: $*"; status=1; }

for abi in "${ABIS[@]}"; do
    so="$WORK/jni/$abi/$LIB"
    [[ -f "$so" ]] || { fail "$abi/$LIB missing"; continue; }
    for align in $("$READELF" -lW "$so" | awk '$1 == "LOAD" { print $NF }'); do
        (( align >= 0x4000 )) || fail "$abi/$LIB has a LOAD segment aligned to $align"
    done
    while read -r needed; do
        [[ "$needed" != */* ]] || fail "$abi/$LIB depends on a path: $needed"
    done < <("$READELF" -d "$so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
done

extra="$(find "$WORK/jni" -name '*.so' ! -name "$LIB")"
[[ -z "$extra" ]] || fail "unexpected libraries: $extra"

[[ $status -eq 0 ]] && echo "OK: $AAR"
exit $status
