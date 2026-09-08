(function () {
  if (window.__ocChatImages) return;
  window.__ocChatImages = 1;
  if (location.origin !== '__SERVER_URL__') return;

  function api(path) {
    // 同源 fetch, 走 WebView 的 onReceivedHttpAuthRequest 自动带 Basic 认证
    return fetch(path, { credentials: 'same-origin' }).then(function (r) {
      if (!r.ok) throw new Error('http ' + r.status);
      return r.json();
    });
  }

  // ---- 文件类型判定 (只收录常见格式, 非常见格式不拦截点击) ----
  var IMG_EXT = ['png', 'jpg', 'jpeg', 'gif', 'webp'];
  var AUDIO_EXT = ['mp3', 'wav', 'ogg', 'm4a'];
  var VIDEO_EXT = ['mp4', 'webm'];
  var TEXT_EXT = ['txt', 'md', 'json', 'js', 'ts', 'jsx', 'tsx',
    'py', 'sh', 'log', 'yaml', 'yml', 'xml', 'html', 'css', 'java',
    'c', 'h', 'cpp', 'go', 'sql'];
  var ALL_EXT = IMG_EXT.concat(AUDIO_EXT, VIDEO_EXT, TEXT_EXT).join('|');

  function extOf(path) {
    return (path.split('.').pop() || '').toLowerCase();
  }

  function kindOf(path) {
    var e = extOf(path);
    if (IMG_EXT.indexOf(e) >= 0) return 'image';
    if (AUDIO_EXT.indexOf(e) >= 0) return 'audio';
    if (VIDEO_EXT.indexOf(e) >= 0) return 'video';
    if (TEXT_EXT.indexOf(e) >= 0) return 'text';
    return 'other';
  }

  function mimeOf(path) {
    var e = extOf(path);
    if (e === 'png') return 'image/png';
    if (e === 'jpg' || e === 'jpeg') return 'image/jpeg';
    if (e === 'gif') return 'image/gif';
    if (e === 'webp') return 'image/webp';
    if (e === 'mp3') return 'audio/mpeg';
    if (e === 'wav') return 'audio/wav';
    if (e === 'ogg') return 'audio/ogg';
    if (e === 'm4a') return 'audio/mp4';
    if (e === 'mp4') return 'video/mp4';
    if (e === 'webm') return 'video/webm';
    return 'application/octet-stream';
  }

  function baseName(path) {
    var i = path.lastIndexOf('/');
    return i >= 0 ? path.slice(i + 1) : path;
  }

  // ---- 天窗: 点聊天里的文件路径, 按格式渲染弹层 ----
  var skylight = null;

  function findFilePath(el) {
    try {
      if (!el || !el.closest) return null;
      // 自己家的浮层不拦截
      if (el.closest('[data-oc-skylight]')) return null;
      // 交互控件一律放行: 审批按钮/开关/输入框等 (之前误拦导致审批点不了)
      if (el.closest('button,input,select,textarea,[role="button"],[role="switch"],'
          + '[role="checkbox"],[role="radio"],[role="combobox"],[contenteditable="true"]')) return null;
      // 任意绝对路径 (不限 /workspace): /file/content 配合 directory=/ 可读全盘,
      // 本 WebView 只加载本机 opencode, 点的又是 agent 刚输出的路径, 无越权问题
      var re = new RegExp('(\\/[^\\s"\'`<\\]>\\]\\)]+?\\.(' + ALL_EXT + '))', 'i');
      var node = el;
      // 只往上找 3 层 (之前 6 层会误伤祖先消息块里的路径文本)
      for (var d = 0; d < 3 && node && node !== document.body; d++) {
        if (node.tagName === 'A' && node.getAttribute) {
          var h = node.getAttribute('href') || '';
          var m2 = h.match(re);
          if (m2) return { path: m2[1], kind: kindOf(m2[1]) };
          // 占位链接 (空/#) 继续往上找; 真链接交还给页面自己处理
          if (h && h !== '#' && h.indexOf('javascript:') !== 0) return null;
        }
        var t = node.textContent || '';
        // 元素自身文本短才算 (整段消息不算, 只认链接/行内短块)
        if (t.length < 300) {
          var m = t.match(re);
          if (m) return { path: m[1], kind: kindOf(m[1]) };
        }
        node = node.parentNode;
      }
    } catch (e) {}
    return null;
  }

  function closeSkylight() {
    try {
      if (skylight && skylight.parentNode) skylight.parentNode.removeChild(skylight);
    } catch (e) {}
    skylight = null;
  }

  // 项目 UI 风格: 用 --v2-* 主题变量 (深浅色自动跟随), 拿不到时回退浅色值
  function themeCss() {
    return 'position:fixed;left:0;top:0;right:0;bottom:0;z-index:100001;'
      + 'background:rgba(0,0,0,.55);overflow-y:auto;padding:16px 12px;';
  }

  function openSkylight(path, kind) {
    try {
      closeSkylight();
      skylight = document.createElement('div');
      skylight.setAttribute('data-oc-skylight', '1');
      skylight.style.cssText = themeCss();
      // 卡片
      var card = document.createElement('div');
      card.style.cssText = 'max-width:640px;margin:24px auto;overflow:hidden;'
        + 'background:var(--v2-background-bg-base,#ffffff);'
        + 'border:1px solid var(--v2-border-border-weak,#e4e4e4);'
        + 'border-radius:var(--radius-lg,12px);'
        + 'box-shadow:0 8px 32px rgba(0,0,0,.25);';
      // 头部: 文件名 + 关闭
      var bar = document.createElement('div');
      bar.style.cssText = 'display:flex;justify-content:space-between;align-items:center;'
        + 'padding:10px 12px;gap:8px;'
        + 'border-bottom:1px solid var(--v2-border-border-weak,#ececec);';
      var t = document.createElement('span');
      var name = baseName(path);
      t.textContent = name.length > 32 ? '…' + name.slice(-31) : name;
      t.style.cssText = 'font-size:13px;font-weight:600;'
        + 'color:var(--v2-text-text-base,#1a1a1a);'
        + 'overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
      var x = document.createElement('span');
      x.textContent = '✕';
      x.style.cssText = 'flex:none;font-size:13px;padding:5px 14px;border-radius:8px;cursor:pointer;'
        + 'color:var(--v2-text-text-base,#1a1a1a);'
        + 'background:var(--v2-background-bg-button-neutral,#f0f0f0);';
      x.addEventListener('click', function (ev) { ev.stopPropagation(); closeSkylight(); });
      bar.appendChild(t);
      bar.appendChild(x);
      card.appendChild(bar);
      // 内容区
      var body = document.createElement('div');
      body.style.cssText = 'padding:12px;min-height:120px;';
      var loading = document.createElement('div');
      loading.style.cssText = 'text-align:center;padding:36px 0;'
        + 'color:var(--v2-text-text-muted,#888);font-size:13px;';
      loading.textContent = '加载中…';
      body.appendChild(loading);
      card.appendChild(body);
      skylight.appendChild(card);
      skylight.addEventListener('click', function (ev) {
        if (ev.target === skylight) closeSkylight();
      });
      document.body.appendChild(skylight);

      // directory=/ : workspace 内外文件都能读 (默认只认项目目录, /etc 等会 500)
      api('/file/content?path=' + encodeURIComponent(path) + '&directory=' + encodeURIComponent('/')).then(function (data) {
        if (!skylight) return;
        try { body.removeChild(loading); } catch (e) {}
        if (!skylight) return;
        try {
          renderBody(body, path, kind, data);
        } catch (e) {
          failBody(body, '渲染失败: ' + ((e && e.message) || e));
        }
      }).catch(function () {
        if (!skylight) return;
        try { loading.textContent = '加载失败, 请重试'; } catch (e) {}
      });
    } catch (e) {}
  }

  function failBody(body, msg) {
    var err = document.createElement('div');
    err.style.cssText = 'text-align:center;padding:36px 12px;font-size:13px;color:#e5484d;';
    err.textContent = msg;
    body.appendChild(err);
  }

  function renderBody(body, path, kind, data) {
    // 图片: data URL 直显
    if (kind === 'image') {
      if (!data || data.type !== 'binary' || !data.content) {
        failBody(body, '图片加载失败 (文件可能不存在)');
        return;
      }
      if (data.content.length * 0.75 > 5 * 1024 * 1024) {
        failBody(body, '图片过大 (超过 5MB), 请用看图工具打开');
        return;
      }
      var im = document.createElement('img');
      im.src = 'data:' + mimeOf(path) + ';base64,' + data.content;
      im.alt = baseName(path);
      im.style.cssText = 'width:100%;border-radius:8px;background:#fff;display:block;';
      body.appendChild(im);
      return;
    }
    // 文本/代码: 等宽 + 自动换行 + 截断保护
    if (kind === 'text') {
      var text = (data && data.type === 'text' && typeof data.content === 'string') ? data.content : null;
      if (text === null) {
        // 有些文本被当成 binary 回的, 尝试 base64 解
        if (data && data.type === 'binary' && data.content) {
          try {
            var bin = atob(data.content);
            var bytes = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
            text = new TextDecoder('utf-8', { fatal: false }).decode(bytes);
          } catch (e) { text = null; }
        }
      }
      if (text === null) {
        failBody(body, '文本读取失败');
        return;
      }
      if (!text.length) {
        // 空文件: 明确提示, 否则空白 <pre> 看起来像加载失败
        var eb = document.createElement('div');
        eb.style.cssText = 'text-align:center;padding:36px 12px;font-size:13px;'
          + 'color:var(--v2-text-text-muted,#888);';
        eb.textContent = '文件为空 (0 字节): ' + baseName(path);
        body.appendChild(eb);
        return;
      }
      var MAX = 120 * 1024;
      var cut = false;
      if (text.length > MAX) {
        text = text.slice(0, MAX);
        cut = true;
      }
      var pre = document.createElement('pre');
      pre.style.cssText = 'margin:0;padding:10px;border-radius:8px;font-size:12px;line-height:1.6;'
        + 'white-space:pre-wrap;word-break:break-word;max-height:60vh;overflow:auto;'
        + 'font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;'
        + 'color:var(--v2-text-text-base,#1a1a1a);'
        + 'background:var(--v2-background-bg-deep,#f5f5f5);';
      pre.textContent = text + (cut ? '\n\n… (过长已截断, 共 ' + text.length + ' 字内)' : '');
      body.appendChild(pre);
      return;
    }
    // 音频 / 视频: data URL 直播 (限 8MB)
    if (kind === 'audio' || kind === 'video') {
      if (!data || data.type !== 'binary' || !data.content) {
        failBody(body, '媒体加载失败 (文件可能不存在)');
        return;
      }
      if (data.content.length * 0.75 > 8 * 1024 * 1024) {
        failBody(body, '文件过大 (超过 8MB), 请用系统播放器打开');
        return;
      }
      var src = 'data:' + mimeOf(path) + ';base64,' + data.content;
      var el = document.createElement(kind);
      el.src = src;
      el.controls = true;
      el.preload = 'metadata';
      el.style.cssText = kind === 'video'
        ? 'width:100%;border-radius:8px;background:#000;'
        : 'width:100%;margin-top:12px;';
      body.appendChild(el);
      return;
    }
    failBody(body, '暂不支持预览此格式 (' + extOf(path) + ')');
  }

  function armSkylight() {
    try {
      if (window.__ocSkylight) return;
      window.__ocSkylight = 1;
      document.addEventListener('click', function (ev) {
        try {
          var f = findFilePath(ev.target);
          if (!f) return;
          ev.preventDefault();
          if (ev.stopPropagation) ev.stopPropagation();
          openSkylight(f.path, f.kind);
        } catch (e) {}
      }, true);
    } catch (e) {}
  }

  function start() {
    armSkylight();
  }

  if (document.body) start();
  else document.addEventListener('DOMContentLoaded', start);
})();
