(function () {
  if (window.__ocPermHeal) return;
  window.__ocPermHeal = 1;
  if (location.origin !== '__SERVER_URL__') return;

  // ---- 僵尸审批卡自愈 ----
  // 上游 Web UI 的审批卡只靠 `permission.asked` 显示 / `permission.replied` 移除。
  // server 重启 (看护自愈/进程被杀/手机重启) 会清空内存里的待批请求, 且没有任何事件
  // 通知页面 → 卡永远挂着, 按钮点什么都 404 → "没办法点击、一直遮挡"。
  // 本脚本: 卡出现 + 原生轮询确认 server 无待批 (连续 2 次) → 浮一个刷新 pill,
  // 用户点一下整页 reload (同步 store 是内存态, reload 后僵尸必消失, 聊天记录无损)。
  // fail-closed: 桥不可用/状态未知/检查异常一律藏 pill, 绝不误伤活着的审批。

  var CARD_SEL = '[data-kind="permission"]';
  var CHECK_MS = 5000;
  var NEED_EMPTY = 2;
  var emptyStreak = 0;
  var dismissed = false;
  var pill = null;

  function cardVisible() {
    try { return !!document.querySelector(CARD_SEL); } catch (e) { return false; }
  }

  function permState() {
    // -1 未知 / 0 确认无待批 / 1 有待批 (原生 ServerService.lastPermState 三态快照)
    try {
      if (!window.OcLan || !window.OcLan.pendingPermissionState) return -1;
      var s = window.OcLan.pendingPermissionState();
      return (s === 0 || s === 1) ? s : -1;
    } catch (e) { return -1; }
  }

  function hidePill() {
    try {
      if (pill && pill.parentNode) pill.parentNode.removeChild(pill);
    } catch (e) {}
    pill = null;
  }

  function showPill() {
    if (pill || dismissed) return;
    try {
      pill = document.createElement('div');
      pill.setAttribute('data-oc-permheal', '1');
      pill.style.cssText = 'position:fixed;left:12px;right:12px;bottom:86px;z-index:100002;'
        + 'display:flex;align-items:center;gap:8px;'
        + 'padding:10px 12px;border-radius:12px;font-size:13px;line-height:1.5;'
        + 'color:var(--v2-text-text-base,#1a1a1a);'
        + 'background:var(--v2-background-bg-base,#ffffff);'
        + 'border:1px solid var(--v2-border-border-weak,#e4e4e4);'
        + 'box-shadow:0 8px 32px rgba(0,0,0,.25);';
      var msg = document.createElement('span');
      msg.style.cssText = 'flex:1;';
      msg.textContent = '审批已失效 (服务曾重启), 点我刷新页面；任务若中断请重发消息';
      var btn = document.createElement('span');
      btn.textContent = '刷新';
      btn.style.cssText = 'flex:none;padding:5px 14px;border-radius:8px;cursor:pointer;'
        + 'color:#fff;background:#1f6feb;';
      btn.addEventListener('click', function (ev) {
        ev.stopPropagation();
        try { location.reload(); } catch (e) {}
      });
      var x = document.createElement('span');
      x.textContent = '✕';
      x.style.cssText = 'flex:none;padding:5px 10px;border-radius:8px;cursor:pointer;'
        + 'color:var(--v2-text-text-muted,#888);';
      x.addEventListener('click', function (ev) {
        ev.stopPropagation();
        dismissed = true;
        hidePill();
      });
      pill.appendChild(msg);
      pill.appendChild(btn);
      pill.appendChild(x);
      document.body.appendChild(pill);
    } catch (e) { pill = null; }
  }

  function tick() {
    try {
      if (!cardVisible()) {
        emptyStreak = 0;
        hidePill();
        return;
      }
      if (dismissed) return;
      var s = permState();
      if (s === 0) {
        emptyStreak++;
        if (emptyStreak >= NEED_EMPTY) showPill();
      } else {
        emptyStreak = 0;
        hidePill();
      }
    } catch (e) {}
  }

  function start() {
    try { setInterval(tick, CHECK_MS); } catch (e) {}
    try { tick(); } catch (e) {}
  }

  if (document.body) start();
  else document.addEventListener('DOMContentLoaded', start);
})();
