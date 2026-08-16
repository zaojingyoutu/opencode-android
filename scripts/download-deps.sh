# 下载依赖到本地目录
#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="$(cd "$(dirname "$0")/../deps" && pwd)"
mkdir -p "$OUT_DIR"

LATEST_API="https://api.github.com/repos/anomalyco/opencode/releases/latest"
echo "Fetching latest release info..."
RELEASE_JSON=$(curl -sL "$LATEST_API" -H "Accept: application/vnd.github+json")
TAG=$(echo "$RELEASE_JSON" | python3 -c "import sys,json;print(json.load(sys.stdin)['tag_name'])" 2>/dev/null)
echo "Latest tag: $TAG"

# 找 arm64 musl 二进制
DOWNLOAD_URL=$(echo "$RELEASE_JSON" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for a in d.get('assets',[]):
    n=a.get('name','')
    if 'linux' in n and 'arm64' in n and 'musl' in n and 'tar.gz' in n:
        print(a['browser_download_url']); break
")

if [ -z "$DOWNLOAD_URL" ]; then
    echo "ERROR: could not find linux-arm64-musl asset"
    exit 1
fi

echo "Downloading $DOWNLOAD_URL ..."
curl -sL --progress-bar -o "$OUT_DIR/opencode-linux-arm64-musl.tar.gz" "$DOWNLOAD_URL"
tar -xzf "$OUT_DIR/opencode-linux-arm64-musl.tar.gz" -C "$OUT_DIR"
echo "Downloaded and extracted."
echo "Binary at: $OUT_DIR/opencode-linux-arm64-musl/bin/opencode"