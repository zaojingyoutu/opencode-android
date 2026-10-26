(function () {
  if (window.__ocAttachFix) return;
  var srv = '__SERVER_URL__';
  if (srv && srv !== 'null' && location.origin !== srv) return;
  window.__ocAttachFix = 1;

  function b64ToBytes(b64) {
    var bin = atob(b64);
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  // 原生选图回调: 把图片作为 File 注入页面自己的 composer, 触发 change 交给 UI 处理
  window.__ocPickedFiles = function (items) {
    try {
      if (!items || !items.length) return;
      var input = null;
      var all = document.querySelectorAll('input[type=file]');
      for (var i = 0; i < all.length; i++) {
        var el = all[i];
        if (el.isConnected && !el.disabled) input = el;
      }
      if (!input) return;
      var dt = new DataTransfer();
      for (var j = 0; j < items.length; j++) {
        var it = items[j];
        var bytes = b64ToBytes(it.data);
        var f = new File([bytes], it.name || ('image_' + (j + 1) + '.jpg'),
          { type: it.mime || 'image/jpeg' });
        dt.items.add(f);
      }
      input.files = dt.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
    } catch (e) {}
  };

  // 菜单项「图片和文件」: WebView 的 input.click() 在本机被静默丢弃 (不触发
  // onShowFileChooser), 这里在同一个受信任 tap 里直接走原生桥拉起系统相册,
  // 选完由原生回注 File。若 WebView 自身 chooser 正常则不重复拉起。
  document.addEventListener('pointerup', function (e) {
    try {
      if (!e.isTrusted) return;
      var node = e.target;
      if (!node || !node.closest) return;
      var item = node.closest('[role="menuitem"],[role="menuitemradio"]');
      if (!item) return;
      var text = item.textContent || '';
      if (!/图片和文件|上传|附件|attach|upload/i.test(text)) return;
      var seen = false;
      try {
        seen = !!(window.OcLan && OcLan.chooserSeenRecently && OcLan.chooserSeenRecently());
      } catch (err) {}
      if (seen) return;
      setTimeout(function () {
        try {
          if (window.OcLan && OcLan.pickFiles) OcLan.pickFiles();
        } catch (err) {}
      }, 150);
    } catch (err) {}
  }, true);
})();
