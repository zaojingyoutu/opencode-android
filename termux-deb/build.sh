#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
DEPS_DIR="$REPO_DIR/deps"
OUT_DIR="$REPO_DIR/termux-deb/output"
PKG_DIR="$(mktemp -d)"

ARCHIVE=""
if [ -d "$DEPS_DIR/opencode-linux-arm64-musl/bin" ]; then
    ARCHIVE="$DEPS_DIR/opencode-linux-arm64-musl"
elif [ -f "$DEPS_DIR/opencode-linux-arm64-musl/bin/opencode" ]; then
    ARCHIVE="$DEPS_DIR/opencode-linux-arm64-musl"
fi

if [ -z "$ARCHIVE" ] || [ ! -x "$ARCHIVE/bin/opencode" ]; then
    echo "ERROR: opencode binary not found."
    echo "Run ./scripts/download-deps.sh first."
    exit 1
fi

mkdir -p "$PKG_DIR/data/data/com.termux/files/usr/bin"
cp "$ARCHIVE/bin/opencode" "$PKG_DIR/data/data/com.termux/files/usr/bin/opencode"
chmod +x "$PKG_DIR/data/data/com.termux/files/usr/bin/opencode"
cp -a "$SCRIPT_DIR/DEBIAN/control" "$PKG_DIR/DEBIAN/control"

mkdir -p "$OUT_DIR"
dpkg-deb --build "$PKG_DIR" "$OUT_DIR/opencode-termux.deb"
rm -rf "$PKG_DIR"

echo "✅  Built: $OUT_DIR/opencode-termux.deb"
ls -lh "$OUT_DIR/opencode-termux.deb"