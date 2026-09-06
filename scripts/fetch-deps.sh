#!/usr/bin/env bash
# Fetches the sherpa-onnx AAR and wake-word ONNX models from upstream GitHub
# Releases. These binaries are not checked into git (too large); Gradle runs
# this script automatically when they are missing (see app/build.gradle.kts),
# or it can be run manually.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_DIR="$ROOT_DIR/.tools/sherpa-onnx"

AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.14/sherpa-onnx-1.12.14.aar"
AAR_SHA256="5a629a899888cb2760e245d9d5340858b15591aee7fa13644cf57199ff2829b9"
AAR_NAME="sherpa-onnx-1.12.14.aar"

MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
MODEL_SHA256="f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a"
MODEL_NAME="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
MODEL_DIR_IN_TAR="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"

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

mkdir -p "$CACHE_DIR"

fetch "$AAR_URL" "$CACHE_DIR/$AAR_NAME" "$AAR_SHA256"
fetch "$MODEL_URL" "$CACHE_DIR/$MODEL_NAME" "$MODEL_SHA256"

mkdir -p "$ROOT_DIR/app/libs"
cp "$CACHE_DIR/$AAR_NAME" "$ROOT_DIR/app/libs/$AAR_NAME"

WAKE_DIR="$ROOT_DIR/app/src/main/assets/wake"
mkdir -p "$WAKE_DIR"

TMP_EXTRACT="$(mktemp -d "${TMPDIR:-/tmp}/fetch-deps-XXXXXX")"
trap 'rm -rf "$TMP_EXTRACT"' EXIT

tar -xjf "$CACHE_DIR/$MODEL_NAME" -C "$TMP_EXTRACT" \
  "$MODEL_DIR_IN_TAR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
  "$MODEL_DIR_IN_TAR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx" \
  "$MODEL_DIR_IN_TAR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
  "$MODEL_DIR_IN_TAR/tokens.txt"

mv "$TMP_EXTRACT/$MODEL_DIR_IN_TAR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$WAKE_DIR/encoder.onnx"
mv "$TMP_EXTRACT/$MODEL_DIR_IN_TAR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx" "$WAKE_DIR/decoder.onnx"
mv "$TMP_EXTRACT/$MODEL_DIR_IN_TAR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$WAKE_DIR/joiner.onnx"
mv "$TMP_EXTRACT/$MODEL_DIR_IN_TAR/tokens.txt" "$WAKE_DIR/tokens.txt"

cd "$ROOT_DIR"
if command -v shasum >/dev/null 2>&1; then
  shasum -a 256 -c third_party/sherpa-onnx/SHA256SUMS
else
  sha256sum -c third_party/sherpa-onnx/SHA256SUMS
fi

echo "fetch-deps: OK - AAR and wake-word models are present and verified"
