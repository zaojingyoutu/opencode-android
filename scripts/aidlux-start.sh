#!/usr/bin/env bash
# AidLux 一键启动 OpenCode server
# 用法: bash scripts/aidlux-start.sh
set -euo pipefail

PORT="${PORT:-18888}"
HOST="${HOST:-0.0.0.0}"   # 0.0.0.0 允许局域网访问; 想只本机用可改 127.0.0.1

echo "=========================================="
echo "  OpenCode server (AidLux) 一键启动"
echo "=========================================="

# 1. 检查/安装 opencode
if ! command -v opencode >/dev/null 2>&1; then
    echo "[1/3] 未找到 opencode，正在安装..."
    if command -v npm >/dev/null 2>&1; then
        npm install -g opencode-ai
    else
        echo "❌ 没有 npm，请先安装 Node.js:"
        echo "   apt install -y nodejs npm"
        exit 1
    fi
else
    echo "[1/3] opencode 已安装: $(opencode --version)"
fi

# 2. 检查端口占用
echo "[2/3] 检查端口 $PORT ..."
if ss -tlnp 2>/dev/null | grep -q ":$PORT "; then
    echo "⚠️  端口 $PORT 已被占用，可能已有 server 在跑"
fi

# 3. 启动 server
echo "[3/3] 启动 opencode serve --port $PORT --hostname $HOST ..."
echo "      访问: http://本机ip:$PORT  (Android APP 或浏览器)"
echo "      停止: kill \$(pgrep -f 'opencode serve')"
echo "------------------------------------------"
exec opencode serve --port "$PORT" --hostname "$HOST" --print-logs