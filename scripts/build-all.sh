#!/usr/bin/env bash
set -euo pipefail

echo "=== Step 1: Generate Android launcher icons ==="
python3 ./scripts/gen-icons.py

echo "=== Step 2: Build Android APK (gradle 自动下载 opencode + rootfs + proot) ==="
cd android-app
if [ -x ./gradlew ]; then
    ./gradlew assembleDebug --no-daemon
else
    echo "⚠️  gradlew not executable, skipping APK build"
    echo "   Run: chmod +x gradlew && ./gradlew assembleDebug"
fi
cd ..

echo ""
echo "✅  Build complete!"
echo "   APK:  android-app/app/build/outputs/apk/debug/app-debug.apk"
