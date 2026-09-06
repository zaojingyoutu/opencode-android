# opencode-android

**OpenCode AI 编程助手的 Android 客户端**

> **免责声明 / Disclaimer**: 本应用由开源社区开发者独立构建的 OpenCode Android 客户端，
> 并非由 OpenCode 官方团队开发，与 OpenCode 官方团队无任何从属关联。
> This app is an independent OpenCode Android client built by open-source community developers.
> It is not built by the OpenCode team and is not affiliated with the OpenCode project in any way.

- 内嵌完整 Alpine Linux (arm64) 容器 (PRoot 实现): 打开 APP 自动拉起 opencode server, 任何 arm64 手机/平板直接可用
- 打开即显示加载界面, 后端异步启动, 就绪后自动载入 opencode Web UI
- proot + loader 以 native lib 名义打包 (`libproot*.so`), 由系统解压到可执行位置, 兼容各厂商 ROM
- 数据/登录态存在 app 私有目录; 容器内可用 `apk add` 安装 nodejs/python/git 等任意工具
- **任务事件横幅**: AI 回复完成 / 等待批准工具调用时弹系统通知, 带"批准/拒绝"按钮直接回复;
  opencode 按目录隔离会话, 横幅跨子项目聚合(轮询 + 全局 SSE), 点横幅直达对应目录/会话
- **局域网访问** (可选): 同 Wi-Fi 下电脑/平板打开手机上的 Web UI, 带 HTTP Basic 认证, 默认关闭
- **看护自愈**: 息屏后台保活 + 空闲自动停 + 待批持锁防 Doze 漏通知 + server 挂死自动重启

---

## 架构

```
┌──────────────── Android 侧 (本机) ────────────────────┐
│  OpenCode APP                                         │
│  ├─ 打开 → 加载界面 + 异步解压 rootfs 并启动容器      │
│  │   proot -0 -r files/opencode/rootfs                │
│  │        -b /sdcard/opencode:/workspace              │
│  │        -b home:/root                               │
│  │        -b resolv.conf:/etc/resolv.conf             │
│  │        -b /dev -b /proc -b /sys -w /workspace      │
│  │        /bin/sh -c "LD_PRELOAD=/lib/libc.so.6:...   │
│  │          opencode serve --port 18888 --hostname ...│
│  │   (gcompat 垫片 + busybox/apk + opencode + git)    │
│  ├─ server 就绪 → WebView 载入 http://127.0.0.1:18888 │
└───────────────────────────────────────────────────────┘
```

## 快速开始

1. 构建 APK (gradle 会自动下载 opencode 最新 release + alpine minirootfs + proot):

```bash
cd android-app
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk (~75MB, 含 rootfs)
```

2. 安装 APK 打开即可。APP 自动解压内置 rootfs 并启动 opencode server, 就绪后自动展示 Web UI。

## 内置模式说明 (PRoot 容器)

- 只支持 **arm64** 设备 (现代手机/平板基本全是)
- **proot 本体 + 其 loader** 以 `libproot.so` / `libproot-loader*.so` 名义由系统解压到
  `/data/app/<pkg>/lib/arm64/` (SELinux 允许 app 执行)。
  容器内所有二进制 (opencode/busybox/git) 都由 proot 的 loader 直接 mmap 加载,
  **只需读权限**, 因此规避了部分 ROM 禁止 `files/` 目录 exec 的 `error=13` 问题。
- proot 依赖的 termux 共享库 (`libtalloc.so.2` / `libandroid-shmem.so`) 在 assets,
  启动时提取到 `files/opencode/proot/` 并通过 `LD_LIBRARY_PATH` 加载
- server 默认只监听 127.0.0.1, 不暴露到局域网; 需要大屏使用可在通知栏/设置面板
  开启局域网访问 (此时绑 0.0.0.0, 带 HTTP Basic 认证, 密码首启随机生成)
- 容器运行时拆成两份 (增量升级): `base.tar` (minirootfs+git+libs+certs, ~26MB) +
  `opencode-bin` (独立二进制, ~190MB)。升级时只有二进制变化则原位覆盖, 用户在容器内
  `apk add` 的工具全部保留; 基础系统变化才整删重装
- 容器目录映射:
  - `/workspace` = 手机存储 `/sdcard/opencode`
    (容器内的工作/项目目录, opencode Web UI 的文件浏览根目录;
    公开目录, 卸载 App 不丢失, 文件管理器可直接访问)
  - `/root` = `files/home` (配置/登录态)
  - `/etc/resolv.conf` = 启动时写入的真实 DNS (musl getaddrinfo 从这里读)
  - `/dev` `/proc` `/sys` = 绑定宿主
- 首次启动会弹窗申请「所有文件访问」权限 (写 `/sdcard/opencode` 需要):
  授权后项目目录固定在 `/sdcard/opencode`; 点「暂不」则回退到 App 专属目录
  `Android/data/com.opencode.android/files/Projects` (卸载会删除)
- 息屏/退出 App 后继续后台运行 (长任务不中断):
  - 前台服务 (`ServerService`) 保活进程 + 常驻通知栏 (可一键"停止")
  - `PARTIAL_WAKE_LOCK` 让息屏后 CPU 不休眠, server 持续响应
  - 首次启动会引导关闭电池优化 (防 Doze 打断); 小米/OPPO 建议在
    「设置 → 应用 → 电池/自启动」允许后台运行, 效果最好
  - 重开 App 自动连回正在运行的内置 server, 无需等待重启
- 容器内已预装: `opencode` (usr/local/bin)、`git` (含 https 远程所需 libcurl 全家桶)、
  busybox/apk、CA 证书。**其余工具可在 Web UI 终端里安装**:

```sh
apk add nodejs python3 gcc     # 或任意 alpine 包
```

- 清除 APP 数据 = 重置配置/登录态/容器 (重装 APK 不影响 rootfs, 解压按版本指纹自动更新)
- 内存占用约 200~400MB (Bun 运行时)

## 模型配置

默认使用 opencode 内置免费模型 (如 `opencode/mimo-v2.5-free`, 无需 API Key),
可在 Web UI 里改; 登录态/API Key 保存在 app 私有目录, 不会丢失。
免费模型被下架时, 启动会自动探测并替换为可用的免费模型 (只动自动管理的默认模型,
用户手动选的模型不碰)。自定义 provider 的 key 建议走 opencode `/connect` 认证,
存 `~/.local/share/opencode/auth.json` (app 私有, 0600)。

## 密钥存放建议

- provider key → `opencode.jsonc` 的 `{env:VAR}` 引用或 `auth.json` (app 私有, 不落 /workspace)
- git 推送 → `~/.git-credentials` (0600, `credential.helper=store`), 不注入环境变量
- 绝对不要放 `/workspace` (即 `/sdcard/opencode`, 文件管理器可见)

## 构建

```bash
cd android-app
./gradlew assembleDebug   # 自动下载并组装: opencode + minirootfs + git/libs + proot
```

构建任务:
- `downloadRootfs`: 下载 opencode release、alpine-minirootfs、git + libcurl 依赖闭包 (alpine v3.24),
  用 `scripts/build_rootfs.py` 流式组装成 `assets/opencode/base.tar` (纯 tar, 不落盘宿主文件系统,
  无需本地工具链) + 独立 `opencode-bin`, 并写 `version.txt` (base/bin 指纹)。已有产物时跳过 (不联网)
- `downloadProot`: 下载 termux 的 proot 及依赖 deb, 用 `scripts/prepare_proot.py` 解出
  `jniLibs/arm64-v8a/libproot*.so` 与 `assets/opencode/proot/*.so`

或推到 GitHub 自动构建 (Actions artifact), 打 tag `vX.Y.Z` 自动发布 GitHub Release。

### 本地打包 (scripts/build-all.sh)

```sh
sh scripts/build-all.sh "" "" beta   # 并存 beta 包 (包名 .beta, 端口 18889), 可同时装正式+beta
sh scripts/build-all.sh              # 正式 debug 包
# 输出 android-app/app/build/outputs/apk/debug/app-debug.apk, 拷到 /workspace/app-beta.apk 即可在手机上装
```

- 版本号自动推导 (与 CI 同算法): versionCode = 2000000 + 总提交数 (每次递增);
  versionName = HEAD 所在/最近 vX.Y.Z tag (tag 构建用 tag 名, 保证 tag==versionName)
- Alpine 上自动注入 aarch64 aapt2 + musl JDK apkzlib 补丁; 持久化 Gradle/SDK 缓存到 /workspace,
  容器重启后不再重复下载 (首次构建约几分钟, 之后 20-30s)

## 目录

```
android-app/          Android APP (WebView 前端 + 内置容器管理)
android-app/app/src/main/jniLibs/arm64-v8a/libproot*.so  proot + loader (git 忽略, 构建时下载)
android-app/app/src/main/assets/opencode/                 base.tar + opencode-bin + version.txt + proot 库 (git 忽略)
android-app/app/src/main/assets/inject/lan.js            Web UI 热注入 (局域网访问设置节)
 (终端 ghostty 光标残留补丁在 MainActivity shouldInterceptRequest 运行时拦截改写)
scripts/
  build_rootfs.py      组装 Alpine rootfs (minirootfs + opencode + git/libs + CA)
  prepare_proot.py     从 termux deb 解出 proot/loader/依赖库
  build-all.sh         本地构建 (版本推导 + aapt2/apkzlib/持久化环境)
  apkzlib-musl-patch/  musl JDK File.delete 删目录缺陷的 NIO 补丁源 (TemporaryFile.java)
.github/workflows/    CI 自动构建 APK + tag 发布 Release
```

## 常见问题

**Q: 打开 APP 一直显示"启动中"**
首次启动需解压 base (~26MB) + 装载 opencode 二进制 (~190MB), 约 30 秒~1 分钟。
超过 2 分钟会显示日志, 点击重试。

**Q: 内置 server 启动失败**
点击状态文字, 屏幕会显示 `server.log` 尾部内容, 据此排查
(常见: 机型 ROM 限制 ptrace/seccomp, 可用 `adb shell am set-debug-app` 场景验证)。

<!-- CI touch -->
