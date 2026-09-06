# opencode-android 手机能力扩展路线图

> 目标：让内置 agent 从"只能在容器里写代码"，扩展到"能驱动 Android 真机做更多事"。
> 本文件是**思路/方案文档**，按档位渐进，改动从小到大。已确认仅输出方案，暂不实现。

---

## 0. 现状与边界

```
┌──────────── Android 真机 ────────────┐
│  WebView UI ─ 审批横幅 / 通知按钮      │
│  MainActivity (Java) ─ JS 桥 OcLan   │
│  ServerService (前台服务 + 唤醒锁)     │
│  ServerManager (proot 进程管理)        │
│    └─ proot Alpine 容器 (root)        │
│         ├─ opencode serve :18888      │
│         ├─ /workspace = /sdcard/opencode
│         ├─ /root = files/home (配置)   │
│         └─ 终端 / git / apk add 任意包 │
└──────────────────────────────────────┘
```

**agent 目前能做到**：读写 `/workspace` 代码与文件、终端跑任意命令、装 Alpine 包、
联网 (HTTP/SSE)、弹审批/完成通知、调 MCP。

**agent 目前够不到**：Android API、其它 App 数据、系统 UI、相册/媒体库、
传感器、剪贴板、通讯、无障碍点击、SAF 文件选取回调。

这堵墙是**安全边界**（AGENTS.md 也约定容器不碰系统），扩展 = 在边界上加"受控的桥"，
而不是拆掉边界。

---

## 1. 核心原则（所有档位通用）

1. **跨边界只走显式桥**：容器内 agent 永不直通 Android 系统，全部经本机受控通道。
2. **本机回环 + 认证**：桥服务只绑 `127.0.0.1`，复用现有 `lanPassword` 式随机密码做 Basic 认证。
3. **权限门槛 + 可开关**：敏感能力（剪贴板读、无障碍、位置…）逐个开关，首次使用弹授权。
4. **可审计**：桥的每次调用记日志；不给 agent 盲区命令（能 kill 系统的东西不做）。
5. **优先 WebView 已有通道**：`WebChromeClient.onShowFileChooser`（选文件）、
   `OcLan` JS 桥、通知按钮 PendingIntent 都是现成的扩展点。

---

## 2. 档位总览

| 档 | 内容 | 改动 | 见效 | 敏感度 |
|----|------|------|------|--------|
| T0 | 容器内扩能 + 文件选择喂图 | 极小 | 立即 | 无 |
| T1 | 原生 sidecar HTTP + MCP tools | 中 | 快 | 低 |
| T1.5 | 截图预览 / 媒体枚举 | 中 | 快 | 中 |
| T2 | Accessibility 全屏自动化 | 大 | 慢 | 高 |
| T3 | 定时 / 远程常驻 | 中 | — | 中 |

---

## 3. T0 — 零原生代码，容器内扩能（立即可做）

容器是 Alpine root，`apk add` 即获得一大批真机可用能力：

- **媒体处理**：`ffmpeg`、`imagemagick`、`libvips`
- **OCR / 识别**：`tesseract`（中英）、二维码 `zbar`
- **文档**：`pandoc`、`poppler-utils`(pdf)、`unoconv`
- **数据库**：`sqlite3`、`redis`
- **下载/同步**：`curl`、`wget`、`rclone`(对接网盘)

配合**已有的文件选择器**（`MainActivity.java:234 onShowFileChooser`）：
用户在对话里被 agent 引导"从相册挑一张图"，选完文件进入 `/workspace` 或临时路径，
agent 就能 OCR/压缩/加水印/扫描，再把结果通知给用户。这是"手机拍照→AI 处理"最顺的路。

> 注意：WebView 文件选择器返回的是 content:// URI，需在 Java 侧转存到容器可见路径
> （现有实现可能已是如此，落地时核对 `onActivityResult` 是否落盘）。

---

## 4. T1 — 原生 sidecar HTTP 服务 + MCP（推荐主线）

### 4.1 架构

```
容器 agent ──curl──> 127.0.0.1:18890 ──> ServerDeviceBridge (App 内, 新 Service)
                                          ├─ ClipboardManager
                                          ├─ NotificationManager (Toast/通知)
                                          ├─ Intent ACTION_VIEW (打开 URL/App)
                                          ├─ Intent ACTION_SEND (分享)
                                          ├─ TextToSpeech / SpeechRecognizer
                                          ├─ BatteryManager / ConnectivityManager
                                          └─ (T1.5) MediaStore / 截图
```

- 端口固定（beta 用 18891 错开），**只绑 127.0.0.1**；
- 认证复用 `ServerManager.lanPassword()` 生成的 Basic，与 opencode server 同源；
- 每个能力一个 `GET/POST` 路由，请求/响应都 JSON；失败回 4xx + 原因；
- 服务随 `ServerService` 前台服务同进程/同生命周期启动，`ServerManager` 管起来。

### 4.2 首批工具（封装成 opencode MCP 或配置 tools）

| 能力 | 路由 | 说明 |
|------|------|------|
| Toast | `POST /toast` | 短暂提示 |
| 通知 | `POST /notify` | 带标题/正文/可点开 App |
| 剪贴板写/读 | `POST /clipboard` | 读需开关（敏感） |
| 打开链接 | `POST /open?url=` | `ACTION_VIEW` |
| 分享文本 | `POST /share` | 拉起系统分享面板 |
| TTS 朗读 | `POST /speak?text=` | 读一句话 |
| 设备信息 | `GET /device` | 电量/网络/型号（只读，低敏） |

### 4.3 对接方式（三选一）

1. **容器内跑 MCP server**：在容器里用 `bun`/`node` 起一个 MCP stdio server，
   `fetch` 到 sidecar，注册进 `opencode.jsonc` 的 `mcp`。agent 原生获得 tools，
   最贴合现有 `MCP` 配置，**推荐**。
2. **配置命令 tools**：sidecar 直接当"命令工具"用（agent 跑 `curl`），零 MCP 依赖，
   但无 schema，agent 靠提示词。适合早期验证。
3. **WebView JS 桥扩 OcLan**：仅对"用户在页面上点按钮"的场景，不适合 agent 主动调用。

### 4.4 权限与开关

- Manifest 需要：剪贴板读（Android 10+ 前台限制）、TTS 不需要权限、通知已有；
- 每项在 `opencode_prefs` 存开关，首用弹一次性确认；
- 危险项（读剪贴板、读媒体）默认关，`/device` 等只读默认开。

---

## 5. T1.5 — 让 agent 能"看见"手机

- **截图**：`/screenshot` — 用 `MediaProjection`（需用户授权）截屏，写到容器 `/workspace/.tmp/shot.png`，
  agent 用现有 image 能力分析。最接近"agent 看得见界面"的低成本方案。
- **媒体枚举**：`/media/list`（MediaStore 只读查询，授权后返回最近图片/视频路径列表），
  供 agent 挑"最近一张图"处理，比让用户手选更顺。

> MediaProjection 每次授权只能截一次，Android 14 需 `MediaProjectionManager.createScreenCaptureIntent`；
> 落地时做成"agent 请求 → 用户点允许 → 截一张 → 下次再请求"。

---

## 6. T2 — Accessibility 无障碍全屏操作（重、慎重）

让 agent 真正操作任意 App：

- 申请 `AccessibilityService`，暴露 `dump`（读当前屏幕节点树/文本）与 `click(x,y)/text`；
- sidecar 加 `POST /ui/dump` `POST /ui/click`，agent 循环"看屏→决策→点击"= 真机自动化；
- 典型用例：帮用户在设置里关开关、在浏览器搜关键词、跨 App 搬内容。

**代价与风险**
- 改动最大：无障碍服务生命周期、事件回调、与 WebView 会话的握手、坐标换算；
- 敏感权限：Play/厂商审核风险；用户会看到"无障碍已开启"警告；
- 应做成**显式开关**，不用时服务不常驻，调用前再拉起。

**建议**：T2 依赖 T1 的 sidecar 骨架，先做 4、5 再评估是否上 T2。

---

## 7. T3 — 定时 / 远程常驻

- **定时任务**：`WorkManager` 周期性唤醒，检查 server 是否在跑（已有自愈），
  可加"定时提醒/定时跑命令"（如每天 9 点跑一次数据同步），结果走通知。
- **远程入口**：LAN 已有 Basic 认证 Web UI；进一步可在手机端加"分享给电脑"
  或反向通道，让电脑发指令、手机执行 —— 但 Web UI 已覆盖大部分，优先级低。

---

## 8. 落地顺序建议

```
Phase 1 (零风险, 当天) : T0 核对文件选择器落盘 → 引导"选图→容器处理"示例
Phase 2 (主线)        : T1 sidecar 骨架 + /toast /open /share /device + MCP 对接
Phase 3               : T1.5 截图(MediaProjection 授权流) + 媒体枚举
Phase 4 (评估后)      : T2 无障碍自动化（显式开关）
Phase 5 (按需)        : T3 定时 / 远程
```

每阶段独立可交付、可回退；都遵循"本机回环 + 认证 + 开关 + 日志"四件套。

---

## 9. 相关现有代码锚点

| 位置 | 用途 |
|------|------|
| `MainActivity.java:234` `onShowFileChooser` | T0 选文件入口 |
| `MainActivity.java` `LanBridge`/`OcLan` | JS 桥模式参考（guard + 主线程回跳） |
| `ServerService.java` 前台服务/通道/通知按钮 | T1 桥生命周期挂载点 |
| `ServerManager.java` `startProcess`/`lanPassword`/`basicAuth` | sidecar 认证复用 |
| `opencode.jsonc` (mcp 段) | MCP tools 注册 |

---

*状态：方案文档 v1，仅讨论未实现。*
