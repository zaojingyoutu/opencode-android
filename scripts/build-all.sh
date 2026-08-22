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
# 可选第3参数: 并存包后缀 (如 beta), 生成的 APK 包名带 .beta 后缀,
# 可与正式包同时安装在手机上; 端口自动改用 18889 避免冲突
APP_ID_SUFFIX="${3:-}"

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
if [ -n "$APP_ID_SUFFIX" ]; then
    echo "    并存包: com.opencode.android.$APP_ID_SUFFIX (端口 18889)"
    GRADLE_ARGS="$GRADLE_ARGS -PappIdSuffix=$APP_ID_SUFFIX -Pport=18889"
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

# musl JDK 修复: musl libc 的 remove() 不像 glibc 那样对目录回落 rmdir,
# OpenJDK 的 java.io.File.delete() 因此删不掉任何目录 → AGP(apkzlib) 清理
# /tmp/tempdir_* 失败 → :app:mergeDebugJavaResource 抛 "Failed to delete ..."。
# 探测到该缺陷时, 用 NIO 实现替换 gradle 缓存里 apkzlib 的 TemporaryFile 类。
# 正常 JDK (glibc/macOS/Windows) 探测通过, 直接跳过, 行为不变。
fix_apkzlib_for_musl_jdk() {
    command -v javac >/dev/null 2>&1 || return 0

    probe_dir=$(mktemp -d "${TMPDIR:-/tmp}/musl-probe.XXXXXX") || return 0
    trap 'rm -rf "$probe_dir"' EXIT INT TERM
    cat > "$probe_dir/Probe.java" <<'EOF'
import java.io.File;
public class Probe {
    public static void main(String[] a) throws Exception {
        File d = new File(a[0], "x");
        d.mkdirs();
        System.exit(d.delete() ? 0 : 1);
    }
}
EOF
    if ! javac -d "$probe_dir" "$probe_dir/Probe.java" 2>/dev/null \
            || java -cp "$probe_dir" Probe "$probe_dir" 2>/dev/null; then
        rm -rf "$probe_dir"; trap - EXIT INT TERM
        return 0   # JDK 可正常删目录, 无需修补
    fi
    rm -rf "$probe_dir"; trap - EXIT INT TERM
    echo "    检测到 musl JDK 缺陷 (File.delete 无法删除目录), 打 apkzlib 补丁..."

    gradle_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
    jar_file=$(find "$gradle_home/caches/modules-2/files-2.1/com.android.tools.build/apkzlib" \
        -name 'apkzlib-*.jar' 2>/dev/null | head -n 1)
    [ -n "$jar_file" ] || { echo "⚠️  未找到缓存中的 apkzlib jar, 跳过补丁"; return 0; }

    # 幂等: 已打过补丁则跳过 (标记文件与 jar 同目录)
    if [ -f "$jar_file.musl-patched" ]; then
        echo "    apkzlib 补丁已存在: $jar_file"
        return 0
    fi

    classes="$probe_dir/classes"
    mkdir -p "$classes"
    if ! javac --release 11 -d "$classes" \
            "$SCRIPT_DIR/apkzlib-musl-patch/TemporaryFile.java"; then
        echo "⚠️  apkzlib 补丁编译失败, 构建可能因临时目录无法清理而失败"
        return 0
    fi
    jar --update --file "$jar_file" -C "$classes" \
        com/android/tools/build/apkzlib/bytestorage/TemporaryFile.class \
        && touch "$jar_file.musl-patched"

    # 清掉基于旧 jar 的字节码插桩缓存, 让 Gradle 用新 jar 重新生成
    find "$gradle_home/caches" -type f -name 'instrumented-apkzlib*.jar' 2>/dev/null |
        while IFS= read -r f; do rm -rf "$(dirname "$f")"; done
    echo "    apkzlib 已修补: $jar_file"

    # 清理之前失败构建遗留的空壳临时目录
    find "${TMPDIR:-/tmp}" -maxdepth 1 -name 'tempdir_*' -type d -exec rm -rf {} + 2>/dev/null || :
}

fix_apkzlib_for_musl_jdk

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