# opencode-android

**OpenCode AI 编程助手的 Android 客户端（AidLux 方案）**

- 单个轻量 APK（~400KB）——纯 WebView 前端
- opencode server 跑在 **AidLux Linux 侧**（本机 localhost），不用打包 200MB 二进制
- 首次使用只需在 AidLux 终端跑一次 `bash scripts/aidlux-start.sh`

---

## 架构

```
┌────────────── Android 侧 ──────────────┐
│  OpenCode APP (WebView 前端, ~400KB)    │
│  http://127.0.0.1:18888                 │
└──────────────┬──────────────────────────┘
               │ localhost 直连
┌──────────────▼──────────────────────────┐
│  AidLux Linux 侧                        │
│  opencode serve --port 18888            │
│  (DeepSeek free / OpenCode Zen)         │
└─────────────────────────────────────────┘
```

## 快速开始（AidLux 用户）

### 第 1 步：在 AidLux 终端启动 server

```bash
cd ~/opencode-android
bash scripts/aidlux-start.sh
```

脚本会自动：检查 opencode →（没有就 npm 安装）→ 启动 serve。

如果要在后台常驻：

```bash
nohup bash scripts/aidlux-start.sh > ~/opencode-server.log 2>&1 &
echo "已启动, 日志: ~/opencode-server.log"
```

### 第 2 步：装 APP

下载 APK 安装即可。打开后自动连 `127.0.0.1:18888`。
如果连不上，屏幕会显示提示，点一下重试。

### 局域网使用（可选）

手机/平板连同一 Wi-Fi，在 APP 内把地址改成 `http://AidLux设备IP:18888`。
（server 已默认监听 0.0.0.0）

## 手动启动 server（不用脚本）

```bash
opencode serve --port 18888 --hostname 0.0.0.0
```

只本机用：`--hostname 127.0.0.1`

## 模型配置

默认模型可以直接在 opencode 网页界面里选（OpenCode Zen / DeepSeek free 都在列表里）。
也可以在启动前设置环境变量指定模型：

```bash
OPENCODE_MODEL=opencode/deepseek-v4-flash-free opencode serve --port 18888 --hostname 0.0.0.0
```

## 构建 APP

```bash
cd android-app
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

或推到 GitHub 自动构建（Actions artifact）。

## 目录

```
android-app/          Android APP (WebView 前端)
scripts/
  aidlux-start.sh     一键启动 server（AidLux）
  build-all.sh        本地构建
  gen-icons.py        图标生成
.github/workflows/    CI 自动构建 APK
```

## 常见问题

**Q: 打开 APP 显示连不上**
确认 AidLux 侧 server 在跑：`curl http://127.0.0.1:18888/api/health` 应返回 `{"healthy":true}`

**Q: 想用别的设备访问**
APP 内改地址为 `http://<AidLux-IP>:18888`，确认 server 用 `--hostname 0.0.0.0` 启动

**Q: opencode 没装**
`npm install -g opencode-ai` 或用脚本自动装