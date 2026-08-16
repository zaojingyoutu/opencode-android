#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
DEPS_DIR="$REPO_DIR/deps"
OUT_DIR="$REPO_DIR/termux-deb/output"
PKG_DIR="$(mktemp -d)"

ARCHIVE=""
BINARY=""
if [ -x "$DEPS_DIR/opencode-linux-arm64-musl/bin/opencode" ]; then
    BINARY="$DEPS_DIR/opencode-linux-arm64-musl/bin/opencode"
elif [ -x "$DEPS_DIR/opencode" ]; then
    BINARY="$DEPS_DIR/opencode"
fi

if [ -z "$BINARY" ]; then
    echo "ERROR: opencode binary not found."
    echo "  looked at: $DEPS_DIR/opencode-linux-arm64-musl/bin/opencode"
    echo "  looked at: $DEPS_DIR/opencode"
    echo "  contents of deps/:"
    ls -la "$DEPS_DIR/" 2>/dev/null || echo "  deps/ does not exist"
    echo "Run ./scripts/download-deps.sh first."
    exit 1
fi
chmod +x "$BINARY"

mkdir -p "$PKG_DIR/data/data/com.termux/files/usr/bin"
cp "$BINARY" "$PKG_DIR/data/data/com.termux/files/usr/bin/opencode"
chmod +x "$PKG_DIR/data/data/com.termux/files/usr/bin/opencode"
cp -a "$SCRIPT_DIR/DEBIAN/control" "$PKG_DIR/DEBIAN/control"

mkdir -p "$OUT_DIR"
dpkg-deb --build "$PKG_DIR" "$OUT_DIR/opencode-termux.deb"
rm -rf "$PKG_DIR"

echo "✅  Built: $OUT_DIR/opencode-termux.deb"
ls -lh "$OUT_DIR/opencode-termux.deb"