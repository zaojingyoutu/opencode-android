(function () {
  if (window.__ocChatImages) return;
  window.__ocChatImages = 1;
  if (location.origin !== '__SERVER_URL__') return;

  var rendered = {};   // partId -> true, 已插过图的不再重复
  var dirCache = {};   // sessionId -> directory

  function sessionIdFromUrl() {
    // 新版 Web UI 用 path 路由 (/server/<b64>/session/<id> 或 /<b64dir>/session/<id>), 无 hash
    var m = (location.pathname || '').match(/\/session\/([A-Za-z0-9_]+)/);
    return m ? m[1] : null;
  }

  function api(path) {
    // 同源 fetch, 走 WebView 的 onReceivedHttpAuthRequest 自动带 Basic 认证
    return fetch(path, { credentials: 'same-origin' }).then(function (r) {
      if (!r.ok) throw new Error('http ' + r.status);
      return r.json();
    });
  }

  function sessionDir(sid) {
    if (dirCache[sid]) return Promise.resolve(dirCache[sid]);
    return api('/session').then(function (list) {
      for (var i = 0; i < list.length; i++) {
        if (list[i].id === sid && list[i].directory) {
          dirCache[sid] = list[i].directory;
          return list[i].directory;
        }
      }
      return '';
    });
  }

  function insertImage(partId, url, label) {
    if (rendered[partId]) return;
    var anchor = document.querySelector('[data-timeline-part-id="' + partId + '"]');
    if (!anchor) return;
    // 已有图则跳过 (防止重复插入)
    if (anchor.querySelector('img[data-oc-shot="1"]')) {
      rendered[partId] = true;
      return;
    }
    var wrap = document.createElement('div');
    wrap.setAttribute('data-oc-shot-wrap', '1');
    wrap.style.cssText = 'margin:8px 0;max-width:100%;';
    var img = document.createElement('img');
    img.setAttribute('data-oc-shot', '1');
    img.src = url;
    img.alt = label || 'screenshot';
    img.style.cssText = 'max-width:100%;border-radius:8px;border:1px solid rgba(128,128,128,.35);cursor:zoom-in;';
    img.addEventListener('click', function () {
      window.open(url, '_blank');
    });
    wrap.appendChild(img);
    anchor.appendChild(wrap);
    rendered[partId] = true;
  }

  function scanOnce() {
    var sid = sessionIdFromUrl();
    if (!sid) return Promise.resolve();
    return sessionDir(sid).then(function (dir) {
      var url = '/session/' + encodeURIComponent(sid) + '/message';
      if (dir) url += '?directory=' + encodeURIComponent(dir);
      return api(url);
    }).then(function (msgs) {
      if (!msgs || !msgs.length) return;
      for (var i = 0; i < msgs.length; i++) {
        var parts = msgs[i].parts || [];
        for (var j = 0; j < parts.length; j++) {
          var p = parts[j];
          if (!p || p.type !== 'tool' || !p.id) continue;
          var atts = (p.state && p.state.attachments) || [];
          for (var k = 0; k < atts.length; k++) {
            var a = atts[k];
            if (!a || !a.mime || a.mime.indexOf('image/') !== 0 || !a.url) continue;
            // data URL 或 http(s) 才允许, 防 file:// 等危险 scheme
            if (a.url.indexOf('data:image/') !== 0 && a.url.indexOf('http://') !== 0 && a.url.indexOf('https://') !== 0) continue;
            insertImage(p.id, a.url, a.filename || 'screenshot');
          }
        }
      }
    }).catch(function () { /* 静默: 会话切换/网络抖动时下轮重试 */ });
  }

  function start() {
    scanOnce();
    setInterval(scanOnce, 2500);
    // hash 变化 (切会话) 即扫一次, 不必等轮询
    window.addEventListener('hashchange', function () { scanOnce(); });
    window.__ocChatImagesScan = scanOnce;
  }

  if (document.body) start();
  else document.addEventListener('DOMContentLoaded', start);
})();
