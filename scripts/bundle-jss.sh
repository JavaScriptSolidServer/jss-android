#!/usr/bin/env bash
#
# bundle-jss.sh — populate assets/nodejs-project/ with a JSS bundle
#
# Pulls the published `javascript-solid-server` tarball from npm, installs
# its production dependencies, and copies the result to assets/nodejs-project/.
# That directory is then bundled into the APK as nodejs-mobile's project root.
#
# Usage:
#   ./scripts/bundle-jss.sh                  # latest published version
#   ./scripts/bundle-jss.sh 0.0.169          # a specific version
#   ./scripts/bundle-jss.sh --source ../JavaScriptSolidServer
#                                            # bundle from a local checkout (dev)
#
# Aborts if any *.node binaries or binding.gyp files turn up in the tree —
# nodejs-mobile can't load native addons unless they're cross-compiled for
# Android, and we explicitly want to keep the bundle pure-JS.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_ROOT/assets/nodejs-project"

SOURCE_DIR=""
VERSION="latest"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source)
      SOURCE_DIR="$2"
      shift 2
      ;;
    -h|--help)
      sed -n '2,15p' "$0"
      exit 0
      ;;
    *)
      VERSION="$1"
      shift
      ;;
  esac
done

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

if [[ -n "$SOURCE_DIR" ]]; then
  if [[ ! -f "$SOURCE_DIR/package.json" ]]; then
    echo "ERROR: --source path '$SOURCE_DIR' has no package.json" >&2
    exit 1
  fi
  echo "==> Packing local source: $SOURCE_DIR"
  ( cd "$SOURCE_DIR" && npm pack --pack-destination "$TMPDIR" >/dev/null )
else
  echo "==> Pulling javascript-solid-server@$VERSION from npm"
  ( cd "$TMPDIR" && npm pack "javascript-solid-server@$VERSION" >/dev/null )
fi

cd "$TMPDIR"
TARBALL="$(ls javascript-solid-server-*.tgz | head -1)"
tar -xzf "$TARBALL"
cd package

echo "==> Installing production dependencies"
npm install --omit=dev --no-package-lock --no-audit --no-fund >/dev/null

echo "==> Sanity check: no native modules"
NATIVE_HITS="$(find . \( -name "*.node" -o -name "binding.gyp" \) 2>/dev/null || true)"
if [[ -n "$NATIVE_HITS" ]]; then
  echo "ERROR: native modules detected — nodejs-mobile won't load these" >&2
  echo "$NATIVE_HITS" >&2
  exit 1
fi

PKG_VERSION="$(node -p "require('./package.json').version")"
FILE_COUNT="$(find . -type f | wc -l | tr -d ' ')"
SIZE="$(du -sh . | cut -f1)"

echo "==> Copying to $DEST"
mkdir -p "$DEST"
rm -rf "${DEST:?}"/*
cp -r bin src package.json node_modules "$DEST/"

cat > "$DEST/.bundle-info" <<EOF
javascript-solid-server@$PKG_VERSION
bundled at $(date -u +%Y-%m-%dT%H:%M:%SZ)
files: $FILE_COUNT
size: $SIZE
source: ${SOURCE_DIR:-npm:$VERSION}
EOF

echo
echo "Done."
echo "  Version: $PKG_VERSION"
echo "  Files:   $FILE_COUNT"
echo "  Size:    $SIZE"
echo "  Path:    $DEST"
