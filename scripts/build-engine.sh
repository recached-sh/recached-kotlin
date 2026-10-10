#!/usr/bin/env bash
# Build recached-mobile from a local checkout of recached-sh/recached and stage
# it in engine/, where the Gradle build looks for it.
#
#   engine/jniLibs/<abi>/librecached_mobile.so          Android libraries, packed into the :ffi AAR
#   engine/kotlin/dev/recached/ffi/recached_mobile.kt   UniFFI-generated bindings, compiled into :ffi
#   engine/host/librecached_mobile.{so,dylib}           host library the JVM tests load
#   engine/host/recached-server                         server the live tests run against
#   engine/SOURCE                                       where the files came from
#
# Use this to develop against unreleased core changes, and in CI for the host
# library and server, which no release carries. Published AARs are built from
# the release archive instead (scripts/fetch-engine.sh), so what ships is
# byte-for-byte what the recached release published.
#
# Usage:
#   scripts/build-engine.sh [path-to-recached-checkout]    default: ../recached
#   HOST_ONLY=1 scripts/build-engine.sh ...                 skip the Android ABIs
#
# Requires: cargo, and for the Android ABIs cargo-ndk plus an NDK (found via
# ANDROID_NDK_HOME, else the newest under $ANDROID_HOME/ndk).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$(cd "${1:-${RECACHED_SRC:-$ROOT/../recached}}" && pwd)"
OUT="$ROOT/engine"

[[ -f "$SRC/recached-mobile/Cargo.toml" ]] || {
    echo "error: $SRC is not a recached checkout with recached-mobile/" >&2
    exit 1
}

TARGET_DIR="$(cargo metadata --manifest-path "$SRC/Cargo.toml" --no-deps --format-version 1 |
    python3 -c 'import sys, json; print(json.load(sys.stdin)["target_directory"])')"
case "$(uname -s)" in
    Darwin) HOST_LIB=librecached_mobile.dylib ;;
    *)      HOST_LIB=librecached_mobile.so ;;
esac

rm -rf "$OUT"
mkdir -p "$OUT/host"

if [[ "${HOST_ONLY:-0}" != 1 ]]; then
    if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
        SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
        ANDROID_NDK_HOME="$(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
        ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
        export ANDROID_NDK_HOME
    fi
    [[ -d "${ANDROID_NDK_HOME:-}" ]] || { echo "error: no NDK found; set ANDROID_NDK_HOME" >&2; exit 1; }
    echo "Building Android ABIs with NDK $(basename "$ANDROID_NDK_HOME")"
    # The core's own packaging script, so the libraries staged here are built
    # exactly as the release builds them (ABIs, API level, 16 KB alignment).
    PACKAGE="$(mktemp -d)"
    trap 'rm -rf "$PACKAGE"' EXIT
    "$SRC/recached-mobile/scripts/build-android.sh" "$PACKAGE"
    unzip -q "$PACKAGE"/recached-mobile-android-*.zip -d "$OUT"
fi

echo "Building host library and server"
(cd "$SRC" && cargo build --locked -p recached-mobile --lib && cargo build --locked -p recached --bin recached-server)
cp "$TARGET_DIR/debug/$HOST_LIB" "$TARGET_DIR/debug/recached-server" "$OUT/host/"

if [[ ! -d "$OUT/kotlin" ]]; then
    (cd "$SRC" && cargo run --locked -q -p recached-mobile --features cli --bin uniffi-bindgen -- \
        generate --library "$TARGET_DIR/debug/$HOST_LIB" \
        --language kotlin --no-format --out-dir "$OUT/kotlin")
fi

echo "local $(git -C "$SRC" describe --always --dirty) ($SRC)" > "$OUT/SOURCE"
echo "Staged engine from $(cat "$OUT/SOURCE") in $OUT"
