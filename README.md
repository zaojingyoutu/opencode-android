# opencode-android

**OpenCode AI 编程助手的 Android 客户端**

- 原生集成: APK 内嵌 opencode 二进制 (linux-arm64-musl), 打开 APP 自动拉起 server, 任何 arm64 手机/平板直接可用
- 保留 AidLux/局域网模式: 检测到已有 server 时直接连, 不用内置二进制
- APP 首次启动自动解压二进制 (~60MB APK, 解压后 ~200MB), 数据/登录态存在 app 私有目录

---

## 架构

```
┌──────────────── Android 侧 (本机) ────────────────┐
│  OpenCode APP                                     │
│  ├─ 内置模式: 子进程拉起 opencode serve            │
│  │   (二进制在 assets/opencode/, HOME/XDG → 私有目录) │
│  └─ WebView 前端 → http://127.0.0.1:18888          │
└───────────────┬────────────────────────────────────┘
                │ 局域网/已有 server 时直接连
┌───────────────▼────────────────────────────────────┐
│  AidLux Linux 侧 / 局域网设备                       │
│  opencode serve --port 18888 --hostname 0.0.0.0     │
└─────────────────────────────────────────────────────┘
```

## 快速开始

1. 构建 APK (gradle 会自动下载 opencode 最新 release 二进制):

```bash
cd android-app
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk (~60MB)
```

2. 安装 APK 打开即可。APP 会自动:
   - 先探测外部 server (AidLux/局域网) → 有就连
   - 没有则自动启动内置 opencode server (首次需几秒解压)

## 内置模式说明

- 只支持 **arm64** 设备 (现代手机/平板基本全是)
- server 只监听 127.0.0.1, 不暴露到局域网 (安全)
- 数据目录: `/data/data/com.opencode.android/files/opencode/`
  - `home/` 配置、`config/`、`data/` 登录态、`server.log` 日志
- 清除 APP 数据 = 重置配置和登录态
- 内存占用约 200~400MB (Bun 运行时)

## AidLux / 局域网模式 (可选)

server 跑在 AidLux Linux 侧或局域网设备上:

```bash
opencode serve --port 18888 --hostname 0.0.0.0
```

APP 内地址存于 SharedPreferences (`server_url`, 默认 `http://127.0.0.1:18888`)。
默认就是本机地址, 内置模式优先; 改成本机不存在的外部地址时 APP 自动退回内置模式。

## 模型配置

默认模型可以在 opencode 网页界面里选 (OpenCode Zen / DeepSeek free 都在列表里),
登录态保存在 app 私有目录, 不会丢失。

## 构建

```bash
cd android-app
./gradlew assembleDebug   # 自动下载 opencode 二进制 (无则下载, 有则跳过)
```

或推到 GitHub 自动构建 (Actions artifact / tag 发布 Release)。

## 目录

```
android-app/          Android APP (WebView 前端 + 内置 server 管理)
android-app/app/src/main/assets/opencode/  二进制 (git 忽略, 构建时下载)
scripts/
  aidlux-start.sh     一键启动 server (AidLux)
  build-all.sh        本地构建
  gen-icons.py        图标生成
termux-deb/           Termux .deb 打包 (可选)
.github/workflows/    CI 自动构建 APK
```

## 常见问题

**Q: 打开 APP 一直显示"启动中"**
等 10~30 秒 (首次解压二进制)。超过 30 秒会显示日志, 点击重试。

**Q: 内置 server 启动失败**
点击状态文字, 屏幕会显示 `server.log` 尾部内容, 据此排查。

**Q: 想用别的设备访问**
用 AidLux/局域网模式: 在别的设备上装 APP 并把地址改成 `http://<设备IP>:18888`。

**Q: 想彻底用 AidLux 模式**
把 `server_url` 改成真实地址, 或删掉 APK 里的内置二进制 (不推荐)。