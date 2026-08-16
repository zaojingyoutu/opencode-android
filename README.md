# opencode-android

**单个 APK，完全自包含**，内置 opencode 引擎 + musl libc，无需 Termux。

- 首次启动 → 填 API Key（一次性）
- 自动解压引擎 → 自动启动 `opencode serve` → WebView 直连

---

## 快速构建

```bash
./scripts/build-all.sh
```

产物：`android-app/app/build/outputs/apk/debug/app-debug.apk`

---

## 架构

```
┌──────────────────────────────────┐
│  OpenCode APP (单个 APK)         │
│                                  │
│  assets/bin/opencode  (192MB)    │
│  assets/lib/*.so  (musl 三件套)   │
│                                  │
│  首次启动 → 拷贝到 filesDir      │
│  → LD_LIBRARY_PATH=... opencode  │
│    serve --port 18888            │
│  → WebView 连 localhost:18888   │
└──────────────────────────────────┘
```

## 核心代码

```
android-app/app/src/main/java/com/opencode/android/
└── MainActivity.java              ← 自包含入口
    - API Key 设置界面（首次启动）
    - assets → filesDir 解压
    - Runtime.exec 启动 opencode serve
    - WebView 连接健康检查 → 显示 UI
```

## 依赖

| 文件 | 来源 |
|------|------|
| `assets/bin/opencode` | GitHub Release `opencode-linux-arm64-musl.tar.gz` |
| `assets/lib/libc.musl-aarch64.so.1` | Alpine musl-dev |
| `assets/lib/libstdc++.so.6` | Alpine GCC / musl.cc toolchain |
| `assets/lib/libgcc_s.so.1` | Alpine GCC |

## GitHub Actions

Push 到 main 自动：
1. 下载 opencode ARM64 musl 二进制
2. 下载 musl libc + libstdc++ + libgcc_s 三个 .so
3. 打包进 APK assets
4. 编译 + 上传 APK

## 使用

1. 手机装 APK
2. 打开 → 填 API Key
3. 自动启动，WebView 显示 AI 界面

## 注意事项

- API Key 仅存于本设备，`allowBackup=false` 防止备份泄露
- APK 大小约 200MB（含 opencode 二进制 + 库）
- 仅支持 ARM64 设备（Android 7.0+）
