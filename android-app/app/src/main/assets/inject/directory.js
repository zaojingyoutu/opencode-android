(function () {
  if (window.__ocDirInit) return;
  window.__ocDirInit = 1;
  if (location.origin !== '__SERVER_URL__') return;

  var orig = window.fetch;
  if (typeof orig !== 'function') return;

  var NOTE_KEY = 'oc-delete-note';

  function sessionIdFrom(url) {
    try {
      var path = new URL(url, location.href).pathname;
      var m = path.match(/\/session\/(ses_[A-Za-z0-9]+)\/?$/);
      return m ? m[1] : '';
    } catch (e) {
      return '';
    }
  }

  function currentSessionId() {
    return sessionIdFrom(location.href);
  }

  function showNote(text, ms) {
    var el = document.getElementById('oc-delete-note');
    if (!el) {
      el = document.createElement('div');
      el.id = 'oc-delete-note';
      el.setAttribute('role', 'status');
      el.style.cssText = [
        'position:fixed',
        'left:16px',
        'right:16px',
        'bottom:calc(24px + env(safe-area-inset-bottom, 0px))',
        'z-index:2147483647',
        'padding:12px 14px',
        'border-radius:10px',
        'background:#1c2128',
        'color:#f4f4f5',
        'font:14px/1.4 sans-serif',
        'text-align:center',
        'box-shadow:0 8px 24px rgba(0,0,0,.35)'
      ].join(';');
      (document.body || document.documentElement).appendChild(el);
    }
    el.textContent = text;
    clearTimeout(showNote.timer);
    showNote.timer = setTimeout(function () {
      if (el.parentNode) el.parentNode.removeChild(el);
    }, ms || 4000);
  }

  function leaveRemoved() {
    try { sessionStorage.setItem(NOTE_KEY, '已删除'); } catch (e) {}
    showNote('已删除', 2500);
    setTimeout(function () {
      if (currentSessionId()) location.assign('/');
    }, 500);
  }

  function methodOf(input, init) {
    try {
      if (init && init.method) return String(init.method).toUpperCase();
      if (input instanceof Request) return String(input.method || 'GET').toUpperCase();
    } catch (e) {}
    return 'GET';
  }

  function directoryHeader(input, init) {
    try {
      var headers = input instanceof Request
        ? input.headers
        : (init && init.headers ? new Headers(init.headers) : null);
      if (!headers) return '';
      var header = headers.get('x-opencode-directory');
      if (!header) return '';
      try { return decodeURIComponent(header); } catch (e) { return header; }
    } catch (e) {
      return '';
    }
  }

  // 只给删除和更新补目录。发送提示是带正文的 POST，重构成新 Request
  // 会用掉正文流，原请求再发就变成 Failed to fetch。
  // 确认框的按钮交给页面自己处理，这里不再拦截点击。
  function rewriteWrite(input, init) {
    var method = methodOf(input, init);
    if (method !== 'DELETE' && method !== 'PATCH' && method !== 'PUT') return null;
    var url = new URL(input instanceof Request ? input.url : String(input), location.href);
    if (url.searchParams.has('directory')) return null;
    var dir = directoryHeader(input, init);
    if (!dir) return null;
    url.searchParams.set('directory', dir);
    if (input instanceof Request) return new Request(url.toString(), input.clone());
    return new Request(url.toString(), init);
  }

  window.fetch = function (input, init) {
    var method = methodOf(input, init);
    var replaced = null;
    if (method === 'DELETE' || method === 'PATCH' || method === 'PUT') {
      try { replaced = rewriteWrite(input, init); } catch (e) { replaced = null; }
    }
    var pending = replaced ? orig.call(this, replaced) : orig.apply(this, arguments);
    if (method !== 'DELETE') return pending;
    return Promise.resolve(pending).then(function (res) {
      var id = sessionIdFrom((replaced && replaced.url) || res.url || '');
      if (id && id === currentSessionId()) {
        res.clone().text().then(function (body) {
          if (res.ok && body !== 'false') leaveRemoved();
          else if (!res.ok) showNote('删除失败，会话还在（' + res.status + '）');
        }).catch(function () {});
      }
      return res;
    });
  };

  try {
    var pendingNote = sessionStorage.getItem(NOTE_KEY);
    if (pendingNote) {
      sessionStorage.removeItem(NOTE_KEY);
      showNote(pendingNote, 2500);
    }
  } catch (e) {}
})();
