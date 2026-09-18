#!/usr/bin/env bash
# Fetches the two Julius Dictation Kit acoustic model files the wake word needs. They are 12 MB and
# not ours, so they are not in git; Gradle runs this automatically when they are missing (see
# app/build.gradle.kts's requiredDeps/fetchDeps), or run it by hand.
#
# Nothing else is fetched here. The Julius executable (app/src/main/jniLibs/armeabi-v7a/libjulius-bin.so)
# is committed -- it was cross-compiled for this device in the butler repo, see third_party/julius/README.md.
# The grammar lives in scripts/julius-wake/ and is copied into the assets by Gradle.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="$ROOT_DIR/.tools/julius"
ASSETS_DIR="$ROOT_DIR/app/src/main/assets/julius/model"

# Pinned commit of https://github.com/julius-speech/dictation-kit. These two files are Git LFS objects,
# and raw.githubusercontent.com serves the ~130-byte LFS *pointer* instead of the object, which then
# fails the sha256 check; media.githubusercontent.com/media/ resolves them to their real content.
COMMIT="1ceb4dec245ef482918ca33c55c71d383dce145e"
BASE_URL="https://media.githubusercontent.com/media/julius-speech/dictation-kit/$COMMIT/model/phone_m"
declare -A SHA256=(
  ["jnas-tri-3k16-gid.binhmm"]="5f427fa11189a4d49de9a9ef51e8b1159971ee1cf5e3656f42081cf69dbf0c98"
  ["logicalTri-3k16-gid.bin"]="bb4673040fd2691b53dea5bc411dead26732336a5c997fb0f86f93818bbfac66"
)

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else sha256sum "$1" | awk '{print $1}'; fi
}

mkdir -p "$CACHE_DIR" "$ASSETS_DIR"
for name in "${!SHA256[@]}"; do
  dest="$CACHE_DIR/$name"
  if [ ! -f "$dest" ] || [ "$(sha256_of "$dest")" != "${SHA256[$name]}" ]; then
    echo "fetch-deps: downloading $name"
    curl -fL --retry 3 -o "$dest" "$BASE_URL/$name"
    if [ "$(sha256_of "$dest")" != "${SHA256[$name]}" ]; then
      echo "fetch-deps: sha256 mismatch for $dest" >&2
      exit 1
    fi
  else
    echo "fetch-deps: using cached $name"
  fi
  cp "$dest" "$ASSETS_DIR/$name"
done

cd "$ROOT_DIR"
if command -v shasum >/dev/null 2>&1; then shasum -a 256 -c third_party/julius/SHA256SUMS
else sha256sum -c third_party/julius/SHA256SUMS; fi

echo "fetch-deps: OK - the Julius acoustic model is present and verified"
