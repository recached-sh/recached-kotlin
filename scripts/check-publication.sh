#!/usr/bin/env bash
# Publish both artifacts to a throwaway local Maven repository and check them
# against what Maven Central requires, so a release tag never discovers a
# broken POM, a missing jar, or an API artifact that lost its dependency on
# the native one. Nothing is signed or uploaded.
#
# Usage: scripts/check-publication.sh [version]      default: 0.0.0-dryrun
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-0.0.0-dryrun}"
REPO="$(mktemp -d)"
trap 'rm -rf "$REPO"' EXIT

cd "$ROOT"
./gradlew publishToMavenLocal -PVERSION_NAME="$VERSION" -Dmaven.repo.local="$REPO" \
    --console=plain --no-configuration-cache

fail() { echo "error: $*" >&2; exit 1; }
# Listings go through variables: `unzip -l | grep -q` fails under pipefail
# when grep exits early and unzip gets SIGPIPE.
has() { local listing; listing="$(unzip -l "$1")"; grep -q "$2" <<<"$listing"; }

for artifact in recached-android recached-android-ffi; do
    BASE="$REPO/dev/recached/$artifact/$VERSION/$artifact-$VERSION"
    for suffix in .aar .pom .module -sources.jar -javadoc.jar; do
        [[ -s "$BASE$suffix" ]] || fail "missing $artifact-$VERSION$suffix"
    done
    for element in name description url licenses developers scm; do
        grep -q "<$element>" "$BASE.pom" || fail "$artifact POM has no <$element>, which Maven Central requires"
    done
    grep -q "<version>$VERSION</version>" "$BASE.pom" || fail "$artifact POM version is not $VERSION"
done

API="$REPO/dev/recached/recached-android/$VERSION/recached-android-$VERSION"
FFI="$REPO/dev/recached/recached-android-ffi/$VERSION/recached-android-ffi-$VERSION"
grep -q "<artifactId>recached-android-ffi</artifactId>" "$API.pom" ||
    fail "recached-android does not depend on recached-android-ffi"
has "$API-sources.jar" 'dev/recached/Recached.kt' || fail "sources jar has no dev/recached/Recached.kt"
for abi in arm64-v8a armeabi-v7a x86_64; do
    has "$FFI.aar" "jni/$abi/librecached_mobile.so" || fail "FFI AAR has no jni/$abi/librecached_mobile.so"
done
has "$FFI.aar" 'proguard.txt' || fail "FFI AAR carries no consumer ProGuard rules for JNA"

echo "OK: dev.recached:{recached-android,recached-android-ffi}:$VERSION would publish:"
ls -1 "$REPO/dev/recached"/*/"$VERSION" | sed 's/^/  /'
