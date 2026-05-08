#!/usr/bin/env bash
#
# fetch-libnode.sh — vendor libnode.so + headers from nodejs-mobile releases
#
# Downloads the prebuilt Android binaries from
# https://github.com/nodejs-mobile/nodejs-mobile/releases and unpacks them
# into app/libnode/. Both bin/<abi>/libnode.so and include/ are referenced
# by app/src/main/cpp/CMakeLists.txt; gradle's externalNativeBuild reads them
# from there.
#
# Usage:
#   ./scripts/fetch-libnode.sh                  # latest release
#   ./scripts/fetch-libnode.sh v18.20.4         # pinned version
#
# The libnode/ directory is gitignored; CI and dev boxes run this once per
# version pin. ABI filter (arm64-v8a, armeabi-v7a) is applied at the gradle
# layer; this script unpacks all four ABIs from the upstream zip.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_ROOT/app/libnode"

VERSION="${1:-v18.20.4}"
RELEASE_URL="https://github.com/nodejs-mobile/nodejs-mobile/releases/download/$VERSION/nodejs-mobile-${VERSION}-android.zip"

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

echo "==> Downloading $VERSION from $RELEASE_URL"
curl -fL --progress-bar "$RELEASE_URL" -o "$TMPDIR/libnode.zip"

echo "==> Unpacking to $DEST"
mkdir -p "$DEST"
rm -rf "${DEST:?}"/*
unzip -q "$TMPDIR/libnode.zip" -d "$DEST"

# The release ships a top-level dir; flatten it so app/libnode/{bin,include}
# matches the CMakeLists path expectation.
shopt -s dotglob nullglob
for inner in "$DEST"/nodejs-mobile-*; do
  if [ -d "$inner" ]; then
    mv "$inner"/* "$DEST/"
    rmdir "$inner"
  fi
done

# Sanity: at least the arm64-v8a and armeabi-v7a libnode.so should exist
for abi in arm64-v8a armeabi-v7a; do
  so="$DEST/bin/$abi/libnode.so"
  if [ ! -f "$so" ]; then
    echo "ERROR: missing $so after unpack" >&2
    exit 1
  fi
  size=$(stat -c%s "$so")
  printf "  %-15s %s (%d bytes)\n" "$abi" "$so" "$size"
done

cat > "$DEST/.libnode-info" <<EOF
nodejs-mobile $VERSION
fetched at $(date -u +%Y-%m-%dT%H:%M:%SZ)
source: $RELEASE_URL
EOF

echo
echo "Done."
echo "  Version: $VERSION"
echo "  Path:    $DEST"
