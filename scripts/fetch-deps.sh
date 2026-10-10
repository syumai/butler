#!/usr/bin/env bash
# Fetches the Vosk Japanese small model and the Julius Dictation Kit acoustic model files from
# upstream. Neither is checked into git (too large); Gradle runs this script to verify and repair them
# before each build (see app/build.gradle.kts's fetchDeps), or it can be run manually. The Vosk
# and JNA AARs are resolved by Gradle from Maven Central (see app/build.gradle.kts's dependencies
# block) and are not touched by this script. The Julius grammar (wake.dfa/wake.dict) is not fetched
# here either -- it's copied from scripts/julius-wake/ into a generated assets dir by Gradle's
# copyJuliusGrammar task, since scripts/julius-wake/ is already checked into git and is the source of
# truth for it. The Julius *executable* (libjulius-bin.so) is not fetched here either -- see
# scripts/build-julius-android.sh/.md.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VOSK_CACHE_DIR="$ROOT_DIR/.tools/vosk"

VOSK_MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
VOSK_MODEL_SHA256="efa092d280153a77615e9e0c7d7283e93e600de3d19d3bec686c57ef19d52eac"
VOSK_MODEL_NAME="vosk-model-small-ja-0.22.zip"
VOSK_MODEL_DIR="vosk-model-small-ja-0.22"

# Pinned commit of https://github.com/julius-speech/dictation-kit (see scripts/julius-wake/README.md
# for how to fetch it locally with a sparse clone). Only the two acoustic-model files this app actually
# bundles are fetched from GitHub's LFS media endpoint. The raw endpoint returns 133-byte pointers.
JULIUS_DICTATION_KIT_COMMIT="1ceb4dec245ef482918ca33c55c71d383dce145e"
JULIUS_MODEL_BASE_URL="https://media.githubusercontent.com/media/julius-speech/dictation-kit/$JULIUS_DICTATION_KIT_COMMIT/model/phone_m"
JULIUS_CACHE_DIR="$ROOT_DIR/.tools/julius"
# Read hashes from the checked-in manifest below (also works with macOS's Bash 3.2).
DOWNLOAD_TEMP=""
trap 'if [ -n "$DOWNLOAD_TEMP" ]; then rm -f "$DOWNLOAD_TEMP"; fi' EXIT

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

verify_manifest() {
  if command -v shasum >/dev/null 2>&1; then
    (cd "$ROOT_DIR" && tr -d '\r' < "$1" | shasum -a 256 -c -)
  else
    (cd "$ROOT_DIR" && tr -d '\r' < "$1" | sha256sum -c -)
  fi
}

fetch() {
  local url="$1" dest="$2" expected_sha256="$3"
  if [ -f "$dest" ] && verify_sha256 "$dest" "$expected_sha256"; then
    echo "fetch-deps: using cached $(basename "$dest")"
    return
  fi
  if [ -f "$dest" ]; then
    echo "fetch-deps: invalid cached $(basename "$dest"); downloading again" >&2
  fi
  echo "fetch-deps: downloading $(basename "$dest")"
  DOWNLOAD_TEMP="$(mktemp "$dest.download.XXXXXX")"
  if ! curl -fL --show-error --retry 3 --connect-timeout 30 -o "$DOWNLOAD_TEMP" "$url"; then
    echo "fetch-deps: download failed for $(basename "$dest") from $url (HTTP/network error)" >&2
    exit 1
  fi
  if LC_ALL=C grep -aq '^version https://git-lfs.github.com/spec/v1' "$DOWNLOAD_TEMP"; then
    echo "fetch-deps: received a Git LFS pointer instead of $(basename "$dest") from $url" >&2
    exit 1
  fi
  if ! verify_sha256 "$DOWNLOAD_TEMP" "$expected_sha256"; then
    echo "fetch-deps: sha256 mismatch for $dest: expected $expected_sha256, got $(sha256_of "$DOWNLOAD_TEMP") (URL: $url)" >&2
    exit 1
  fi
  mv -f "$DOWNLOAD_TEMP" "$dest"
  DOWNLOAD_TEMP=""
}

mkdir -p "$VOSK_CACHE_DIR"
fetch "$VOSK_MODEL_URL" "$VOSK_CACHE_DIR/$VOSK_MODEL_NAME" "$VOSK_MODEL_SHA256"

VOSK_ASSETS_DIR="$ROOT_DIR/app/src/main/assets/vosk"
mkdir -p "$VOSK_ASSETS_DIR"
if ! verify_manifest third_party/vosk/SHA256SUMS >/dev/null 2>&1; then
  # ROOT_DIR is resolved from this script; the fixed target stays inside this repository's assets.
  rm -rf "$VOSK_ASSETS_DIR/$VOSK_MODEL_DIR"
  unzip -q -o "$VOSK_CACHE_DIR/$VOSK_MODEL_NAME" -d "$VOSK_ASSETS_DIR"
fi

mkdir -p "$JULIUS_CACHE_DIR"
JULIUS_ASSETS_DIR="$ROOT_DIR/app/src/main/assets/julius/model"
mkdir -p "$JULIUS_ASSETS_DIR"
while read -r expected path; do
  [ -n "$expected" ] || continue
  # Git for Windows may check out the manifest with CRLF line endings.
  path="${path%$'\r'}"
  name="$(basename "$path")"
  fetch "$JULIUS_MODEL_BASE_URL/$name" "$JULIUS_CACHE_DIR/$name" "$expected"
  if [ ! -f "$JULIUS_ASSETS_DIR/$name" ] || ! verify_sha256 "$JULIUS_ASSETS_DIR/$name" "$expected"; then
    cp "$JULIUS_CACHE_DIR/$name" "$JULIUS_ASSETS_DIR/$name"
  fi
done < "$ROOT_DIR/third_party/julius/SHA256SUMS"

verify_manifest third_party/vosk/SHA256SUMS
verify_manifest third_party/julius/SHA256SUMS

echo "fetch-deps: OK - the Vosk and Julius models are present and verified"
