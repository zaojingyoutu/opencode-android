#!/usr/bin/env sh
# Build Android APK (gradle 自动下载 opencode + rootfs + proot)
#
# 融合本地打包 + 通用构建, 本地沙盒/普通机器/CI 均可使用:
#   - 版本号可选:  sh build-all.sh [versionName] [versionCode]
#     (省略时用 app/build.gradle.kts 的默认值; 安装到已装 App 需更高版本号, 否则 VERSION_DOWNGRADE)
#   - aarch64/arm64 主机自动注入 aapt2 override (AGP 官方只有 x86_64 版 aapt2):
#     默认找 /usr/local/bin/aapt2, 可用环境变量 AAPT2_PATH 覆盖
#   - gradlew 有 exec 位则直接执行; 无 exec 位 (如 Android sdcard FUSE 挂载) 用 sh 解释执行
#   - 设 BUILD_OUTPUT=<路径> 时构建完成后把 APK 复制到该路径 (如 /workspace/app-debug.apk)
set -e

VERSION_NAME="${1:-}"
VERSION_CODE="${2:-}"

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_DIR="$SCRIPT_DIR/../android-app"
OUT_APK="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"

echo "=== Build Android APK ==="
if [ -n "$VERSION_NAME" ]; then
    echo "    versionName=$VERSION_NAME versionCode=$VERSION_CODE"
fi

cd "$APP_DIR"

# 可选版本参数
GRADLE_ARGS="assembleDebug --no-daemon"
if [ -n "$VERSION_NAME" ]; then
    GRADLE_ARGS="$GRADLE_ARGS -PversionName=$VERSION_NAME -PversionCode=$VERSION_CODE"
fi

# aarch64/arm64: 注入 aapt2 override (dash 的 export 不支持带点变量名, 用 env 注入)
AAPT2_OVERRIDE_ENV=""
case "$( uname -m )" in
    aarch64|arm64)
        aapt2_path="${AAPT2_PATH:-/usr/local/bin/aapt2}"
        if [ -x "$aapt2_path" ]; then
            AAPT2_OVERRIDE_ENV="ORG_GRADLE_PROJECT_android.aapt2FromMavenOverride=$aapt2_path"
            echo "    注入 aapt2 override: $aapt2_path"
        fi
        ;;
esac

run_gradle() {
    if [ -n "$AAPT2_OVERRIDE_ENV" ]; then
        env "$AAPT2_OVERRIDE_ENV" "$@"
    else
        "$@"
    fi
}

if [ -x ./gradlew ]; then
    echo "==> ./gradlew $GRADLE_ARGS"
    run_gradle ./gradlew $GRADLE_ARGS
elif [ -f ./gradlew ]; then
    echo "   gradlew 无执行权限 (sdcard/只读挂载), 改用 sh 解释执行"
    echo "==> sh ./gradlew $GRADLE_ARGS"
    run_gradle sh ./gradlew $GRADLE_ARGS
else
    echo "⚠️  gradlew not found, skipping APK build"
    exit 1
fi

if [ -f "$OUT_APK" ]; then
    echo "   APK: $OUT_APK"
    if [ -n "${BUILD_OUTPUT:-}" ]; then
        cp "$OUT_APK" "$BUILD_OUTPUT"
        echo "   copied to: $BUILD_OUTPUT"
    fi
    md5sum "$OUT_APK"
fi
echo ""
echo "✅  Build complete!"