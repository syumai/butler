#!/usr/bin/env bash
# Fetches the Vosk Japanese small model and the Julius Dictation Kit acoustic model files from
# upstream. Neither is checked into git (too large); Gradle runs this script automatically when either
# is missing (see app/build.gradle.kts's requiredDeps/fetchDeps), or it can be run manually. The Vosk
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
# bundles are fetched here, straight from that commit's raw content -- not the whole dictation-kit repo.
JULIUS_DICTATION_KIT_COMMIT="1ceb4dec245ef482918ca33c55c71d383dce145e"
JULIUS_MODEL_BASE_URL="https://raw.githubusercontent.com/julius-speech/dictation-kit/$JULIUS_DICTATION_KIT_COMMIT/model/phone_m"
JULIUS_CACHE_DIR="$ROOT_DIR/.tools/julius"
declare -A JULIUS_MODEL_SHA256=(
  ["jnas-tri-3k16-gid.binhmm"]="5f427fa11189a4d49de9a9ef51e8b1159971ee1cf5e3656f42081cf69dbf0c98"
  ["logicalTri-3k16-gid.bin"]="bb4673040fd2691b53dea5bc411dead26732336a5c997fb0f86f93818bbfac66"
)

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

mkdir -p "$JULIUS_CACHE_DIR"
JULIUS_ASSETS_DIR="$ROOT_DIR/app/src/main/assets/julius/model"
mkdir -p "$JULIUS_ASSETS_DIR"
for name in "${!JULIUS_MODEL_SHA256[@]}"; do
  fetch "$JULIUS_MODEL_BASE_URL/$name" "$JULIUS_CACHE_DIR/$name" "${JULIUS_MODEL_SHA256[$name]}"
  cp "$JULIUS_CACHE_DIR/$name" "$JULIUS_ASSETS_DIR/$name"
done

cd "$ROOT_DIR"
if command -v shasum >/dev/null 2>&1; then
  shasum -a 256 -c third_party/vosk/SHA256SUMS
  shasum -a 256 -c third_party/julius/SHA256SUMS
else
  sha256sum -c third_party/vosk/SHA256SUMS
  sha256sum -c third_party/julius/SHA256SUMS
fi

echo "fetch-deps: OK - the Vosk and Julius models are present and verified"
