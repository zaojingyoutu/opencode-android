# opencode-android

OpenCode 打包成 Android 可用的两个产物：

| 产物 | 用途 | 目标用户 |
|------|------|---------|
| `termux-deb/*.deb` | Termux 终端 AI 编程 | 开发者用命令行 |
| `android-app/app/build/outputs/apk/debug/app-debug.apk` | 手机 WebView APP | 移动端用图形界面 |

---

## 快速构建

```bash
chmod +x scripts/build-all.sh termux-deb/build.sh scripts/download-deps.sh
./scripts/build-all.sh
```

产物位置：
- `termux-deb/output/opencode-termux.deb`
- `android-app/app/build/outputs/apk/debug/app-debug.apk`

---

## 依赖

| 工具 | 用途 |
|------|------|
| Python 3 | 生成图标、CI 脚本 |
| Node.js | Android 资源处理 |
| JDK 17+ | Gradle 编译 APK |
| dpkg | 打包 .deb |
| curl | 下载 opencode 二进制 |

---

## 产物一：Termux .deb（命令行）

**安装：**
```bash
pkg install dpkg
dpkg -i opencode-termux.deb
opencode --version
```

**首次配置：**
```bash
opencode auth login            # 配置 OpenCode Zen
opencode --model deepseek-v4-flash-free   # 使用免费 DeepSeek
```

**依赖的运行时：**
- nodejs（Termux apt 自带）
- python3（Termux apt 自带）
- opencode 二进制（官方 musl 静态编译，自带全部依赖）

---

## 产物二：Android WebView APP

**架构：**
```
Android APP (WebView)
    │ 打开 http://localhost:18888
    ▼
opencode web server (运行在 Termux 或 ADB shell)
    │ 调用 OpenCode Zen API
    ▼
DeepSeek 免费模型
```

**使用流程：**
1. 手机装 Termux，安装 opencode：`dpkg -i opencode-termux.deb`
2. Termux 里启动 server：`opencode web --port 18888 --hostname 0.0.0.0`
3. 装 `app-debug.apk`
4. 打开 APP → WebView 显示 AI 界面

**核心代码：**
```
android-app/app/src/main/java/com/opencode/android/
├── MainActivity.java        # WebView 容器 + 页面加载
└── OpenCodeService.java      # 后台端口检测 + 健康检查
```

---

## 构建命令速查

```bash
# 只打包 deb
./termux-deb/build.sh

# 只编译 APK
cd android-app && ./gradlew assembleDebug --no-daemon

# 全套
./scripts/build-all.sh
```

---

## 技术选型说明

- **opencode 二进制**：用官方 `musl` 静态编译版本，不依赖系统动态库
- **无额外 node/python 打包**：AidLux 大文件下载受 GFW 限速，node/python 走 Termux 自带 apt
- **WebView 而非 native UI**：opencode 自带 Web 界面，WebView 零前端代码即可用
- **GitHub Actions CI**：push 自动构建 deb + apk，Actions artifacts 下载

---

## GitHub Actions

配置好仓库后，push 到 main 会自动：
1. 下载最新 opencode 二进制
2. 打包 `opencode-termux.deb`
3. 编译 `app-debug.apk`
4. 上传为 Actions Artifacts

---

## 开发说明

- **MainActivity**：WebView 加载 opencode web 界面，支持前进/后退
- **OpenCodeService**：后台健康检查，检测端口可达性
- **build-all.sh**：一站式构建脚本，本地一键出两个产物
