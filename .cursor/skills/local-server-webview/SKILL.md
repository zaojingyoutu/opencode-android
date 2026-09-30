---
name: local-server-webview
description: >-
  Keeps a WebView responsive when it shares a single-threaded local server
  with native watchers. Use when a page is slow to render after a streamed
  reply, stale after returning from background, or when adding SSE, status
  polling, or DOM probes next to an embedded server.
---

# 单线程本地 server 与 WebView

页面和原生看护打的是同一个进程里的同一个 server。这个 server 只有一条事件循环时, 谁占着它, 页面就画不出来。

## 事件流

- 读事件的线程只负责把字节读走。不要在这条线程上同步再请求同一个 server。
- 流式增量交给页面渲染。原生侧只在会话收尾 (空闲、完成) 时再查状态, 并且放到别的线程, 多次收尾并成一次查询。
- 收尾查询晚一点做, 先把 server 让给页面画最终内容。完成通知可以晚一秒, 页面不能被卡住。

## 回前台

- 先让页面自己重连并绘制, 再查 server。立刻并行做全量状态查询, 页面请求会排在后面, 看起来像没有返回数据。
- 判断页面是否已经跟上时, 读 `textContent`。不要用 `innerText`: 它会强制整页布局, 长会话上绘制会停住。
- 探针失败再考虑整页重载。重载是掉线后的补救, 不是对齐的默认动作。
- 只靠实时事件才出现的界面, 回前台用 server 快照对一下页面。快照有、页面没有, 先留一小段时间让还活着的连接自己画出来; 刷新前再向 server 确认一次, 刚处理完的请求不要拿过期快照去刷新。
- 短时间切到后台再回来也要唤醒并对齐正文。已知当前会话目录时只查该目录, 不要为一次对齐扫遍所有工作区。
- 拿来和页面比对的文本必须是页面里真实存在的子串。通知摘要上的省略号不能带进比对。
