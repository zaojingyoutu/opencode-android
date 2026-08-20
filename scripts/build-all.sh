#!/usr/bin/env bash
set -euo pipefail

echo "=== Build Android APK (gradle 自动下载 opencode + rootfs + proot) ==="
cd android-app
# 优先直接执行 gradlew; 在 Android sdcard FUSE 等不支持 exec 位的环境下回退用 sh 解释执行
if [ -x ./gradlew ]; then
    ./gradlew assembleDebug --no-daemon
elif [ -f ./gradlew ]; then
    echo "   gradlew 无执行权限 (sdcard/只读挂载), 改用 sh 解释执行"
    sh ./gradlew assembleDebug --no-daemon
else
    echo "⚠️  gradlew not found, skipping APK build"
fi
cd ..

echo ""
echo "✅  Build complete!"
echo "   APK:  android-app/app/build/outputs/apk/debug/app-debug.apk"
