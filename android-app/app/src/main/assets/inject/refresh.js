(function () {
  if (window.__ocRefreshInit) return;
  window.__ocRefreshInit = 1;
  if (location.origin !== '__SERVER_URL__') return;

  var ICON =
    '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M21 12a9 9 0 1 1-2.6-6.3"/><path d="M21 3v6h-6"/></svg>';

  function barOf(submit) {
    var node = submit.parentElement;
    while (node && node !== document.body) {
      var cls = typeof node.className === 'string' ? node.className : '';
      if (cls.indexOf('flex') !== -1 && cls.indexOf('items-center') !== -1) return node;
      node = node.parentElement;
    }
    return submit.parentElement;
  }

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

  place();
  setInterval(place, 1000);
})();
