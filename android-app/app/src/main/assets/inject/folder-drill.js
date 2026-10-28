(function () {
  if (window.__ocFolderDrill) return;
  var srv = '__SERVER_URL__';
  if (srv && srv !== 'null' && location.origin !== srv) return;
  window.__ocFolderDrill = 1;

  function setInputValue(input, v) {
    var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(input, v);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }

  // 目录弹窗的列表: data-component=list, 搜索框 placeholder 为 "Search folders"/"搜索文件夹"
  function folderList() {
    var lists = document.querySelectorAll('[data-component="list"]');
    for (var i = 0; i < lists.length; i++) {
      var input = lists[i].querySelector('input');
      if (!input) continue;
      var ph = (input.getAttribute('placeholder') || '').toLowerCase();
      if (ph.indexOf('folder') < 0 && ph.indexOf('文件夹') < 0 && ph.indexOf('目录') < 0) continue;
      return {
        list: lists[i],
        input: input,
        bar: lists[i].querySelector('[data-slot="list-search-wrapper"]')
      };
    }
    return null;
  }

  function clean(p) {
    return String(p || '').replace(/\/+$/, '');
  }

  function drill(path) {
    var f = folderList();
    if (!f) return;
    setInputValue(f.input, clean(path) + '/');
    try { f.input.focus(); } catch (e) {}
  }

  function basePath(f) {
    var v = (f.input.value || '').trim();
    if (v.charAt(0) === '~') return null;
    if (v.charAt(0) === '/') return v.charAt(v.length - 1) === '/' ? v : v.replace(/\/[^/]*$/, '/');
    return '/workspace/';
  }

  function createFolder(f) {
    var base = basePath(f);
    if (!base) return;
    try {
      if (window.OcLan && OcLan.newProjectFolder) OcLan.newProjectFolder(base);
    } catch (e) {}
  }

  function tap(el, fn) {
    el.addEventListener('pointerdown', function (e) { e.stopPropagation(); });
    el.addEventListener('pointerup', function (e) {
      e.stopPropagation();
      e.preventDefault();
      fn();
    });
    el.addEventListener('click', function (e) {
      e.stopPropagation();
      e.preventDefault();
    });
  }

  function decorate() {
    var f = folderList();
    if (!f) return;
    var rows = f.list.querySelectorAll('[data-slot="list-item"][data-key]');
    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      if (row.getAttribute('data-oc-drill') === '1') continue;
      var key = row.getAttribute('data-directory-path') || row.getAttribute('data-key') || '';
      if (key.charAt(0) !== '/' && key.charAt(0) !== '~') continue;
      row.setAttribute('data-oc-drill', '1');
      var b = document.createElement('span');
      b.setAttribute('role', 'button');
      b.setAttribute('aria-label', '进入文件夹');
      b.textContent = '\u25B8';
      b.style.cssText = 'flex:0 0 auto;margin-left:auto;width:34px;height:34px;display:flex;'
          + 'align-items:center;justify-content:center;border-radius:6px;color:inherit;'
          + 'opacity:.55;font-size:16px';
      tap(b, function (path) {
        return function () { drill(path); };
      }(key));
      row.appendChild(b);
    }
    if (f.bar && !f.bar.querySelector('[data-oc-newfolder]')) {
      var nb = document.createElement('span');
      nb.setAttribute('role', 'button');
      nb.setAttribute('data-oc-newfolder', '1');
      nb.textContent = '新建';
      nb.style.cssText = 'flex:0 0 auto;height:28px;padding:0 10px;display:flex;'
          + 'align-items:center;border-radius:6px;font-size:12px;color:inherit;'
          + 'opacity:.75;border:1px solid currentColor;margin-left:4px';
      tap(nb, function () { createFolder(f); });
      f.bar.appendChild(nb);
    }
  }

  window.__ocFolderCreated = function (path) {
    var f = folderList();
    if (!f) return;
    var p = String(path || '');
    setInputValue(f.input, p.replace(/\/[^/]*$/, '/'));
    var tries = 0;
    (function pick() {
      var f2 = folderList();
      if (f2) {
        var rows = f2.list.querySelectorAll('[data-slot="list-item"][data-key]');
        for (var i = 0; i < rows.length; i++) {
          if (rows[i].getAttribute('data-key') === p) {
            rows[i].click();
            return;
          }
        }
      }
      if (++tries < 20) setTimeout(pick, 100);
    })();
  };

  var pending = 0;
  function schedule() {
    if (pending) return;
    pending = 1;
    requestAnimationFrame(function () {
      pending = 0;
      try { decorate(); } catch (e) {}
    });
  }
  new MutationObserver(schedule).observe(document.documentElement, {
    childList: true,
    subtree: true
  });
  schedule();
})();
