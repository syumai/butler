#!/usr/bin/env bash
# Fetches the Vosk Japanese small model from upstream. This binary is not
# checked into git (too large); Gradle runs this script automatically when
# it is missing (see app/build.gradle.kts), or it can be run manually. The
# Vosk and JNA AARs themselves are resolved by Gradle from Maven Central
# (see app/build.gradle.kts's dependencies block) and are not touched by
# this script - only the Vosk model zip is fetched and unpacked here.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VOSK_CACHE_DIR="$ROOT_DIR/.tools/vosk"

VOSK_MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
VOSK_MODEL_SHA256="efa092d280153a77615e9e0c7d7283e93e600de3d19d3bec686c57ef19d52eac"
VOSK_MODEL_NAME="vosk-model-small-ja-0.22.zip"
VOSK_MODEL_DIR="vosk-model-small-ja-0.22"

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

verify_sha256() {
  local file="$1" expected="$2"
  local actual
  actual="$(sha256_of "$file")"
  [ "$actual" = "$expected" ]
}

fetch() {
  local url="$1" dest="$2" expected_sha256="$3"
  if [ -f "$dest" ] && verify_sha256 "$dest" "$expected_sha256"; then
    echo "fetch-deps: using cached $(basename "$dest")"
    return
  fi
  echo "fetch-deps: downloading $(basename "$dest")"
  curl -fL --retry 3 -o "$dest" "$url"
  if ! verify_sha256 "$dest" "$expected_sha256"; then
    echo "fetch-deps: sha256 mismatch for $dest" >&2
    exit 1
  fi
}

mkdir -p "$VOSK_CACHE_DIR"
fetch "$VOSK_MODEL_URL" "$VOSK_CACHE_DIR/$VOSK_MODEL_NAME" "$VOSK_MODEL_SHA256"

VOSK_ASSETS_DIR="$ROOT_DIR/app/src/main/assets/vosk"
mkdir -p "$VOSK_ASSETS_DIR"
rm -rf "$VOSK_ASSETS_DIR/$VOSK_MODEL_DIR"
unzip -q -o "$VOSK_CACHE_DIR/$VOSK_MODEL_NAME" -d "$VOSK_ASSETS_DIR"

cd "$ROOT_DIR"
if command -v shasum >/dev/null 2>&1; then
  shasum -a 256 -c third_party/vosk/SHA256SUMS
else
  sha256sum -c third_party/vosk/SHA256SUMS
fi

echo "fetch-deps: OK - the Vosk model is present and verified"
