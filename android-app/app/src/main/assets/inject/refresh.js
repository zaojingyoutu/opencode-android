(function () {
  if (window.__ocRefreshInit) return;
  window.__ocRefreshInit = 1;
  if (location.origin !== '__SERVER_URL__') return;

  var ICON =
    '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M21 12a9 9 0 1 1-2.6-6.3"/><path d="M21 3v6h-6"/></svg>';
  var MORE =
    '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="currentColor">' +
    '<circle cx="6" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="18" cy="12" r="1.6"/></svg>';

  function barOf(submit) {
    var node = submit.parentElement;
    while (node && node !== document.body) {
      var cls = typeof node.className === 'string' ? node.className : '';
      if (cls.indexOf('flex') !== -1 && cls.indexOf('items-center') !== -1) return node;
      node = node.parentElement;
    }
    return submit.parentElement;
  }

  function placeMore() {
    var models = document.querySelectorAll('[data-component="prompt-model-control"]');
    for (var i = 0; i < models.length; i++) {
      var dock = models[i].parentElement;
      if (!dock) continue;
      dock.setAttribute('data-oc-dock', '1');
      if (dock.querySelector('[data-oc-more]')) continue;
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.setAttribute('data-oc-more', '1');
      btn.setAttribute('aria-label', '更多');
      btn.setAttribute('aria-expanded', 'false');
      btn.title = '智能体和变体';
      btn.innerHTML = MORE;
      btn.addEventListener('mousedown', function (e) {
        e.preventDefault();
        e.stopPropagation();
      });
      btn.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        var row = e.currentTarget.parentElement;
        if (!row) return;
        var open = row.getAttribute('data-oc-open') === '1';
        if (open) row.removeAttribute('data-oc-open');
        else row.setAttribute('data-oc-open', '1');
        e.currentTarget.setAttribute('aria-expanded', open ? 'false' : 'true');
      });
      dock.insertBefore(btn, dock.firstChild);
    }
  }

  document.addEventListener('click', function (e) {
    var t = e.target;
    if (t && t.closest && t.closest('[data-oc-dock]')) return;
    var rows = document.querySelectorAll('[data-oc-dock][data-oc-open]');
    for (var i = 0; i < rows.length; i++) {
      rows[i].removeAttribute('data-oc-open');
      var more = rows[i].querySelector('[data-oc-more]');
      if (more) more.setAttribute('aria-expanded', 'false');
    }
  });

  function place() {
    var submits = document.querySelectorAll('[data-action="prompt-submit"]');
    for (var i = 0; i < submits.length; i++) {
      var bar = barOf(submits[i]);
      if (!bar || bar.querySelector('[data-oc-refresh]')) continue;
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.setAttribute('data-oc-refresh', '1');
      btn.setAttribute('aria-label', '刷新页面');
      btn.title = '刷新页面';
      btn.innerHTML = ICON;
      btn.addEventListener('mousedown', function (e) {
        e.preventDefault();
        e.stopPropagation();
      });
      btn.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        location.reload();
      });
      bar.insertBefore(btn, bar.firstChild);
    }
  }

  function tick() {
    place();
    placeMore();
  }

  tick();
  setInterval(tick, 1000);
})();
