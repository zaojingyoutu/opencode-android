(function () {
  if (window.__ocTouchMenu) return;
  if (location.origin !== '__SERVER_URL__') return;
  window.__ocTouchMenu = 1;

  // 触摸选择菜单项发生在 pointerup，菜单随后卸掉。
  // 浏览器紧接着补发的 click 会落到菜单下面的遮罩上，把刚打开的确认框关掉。
  // 只丢掉这下补发的 click，pointerup 仍交给页面自己处理。
  document.addEventListener('pointerup', function (e) {
    if (!e.pointerType || e.pointerType === 'mouse') return;
    var node = e.target;
    if (!node || !node.closest) return;
    var item = node.closest('[role="menuitem"],[role="menuitemradio"]');
    if (!item) return;
    var kill = function (ev) {
      document.removeEventListener('click', kill, true);
      // 只挡落在菜单项之外(遮罩/背景)的那下补发 click (菜单项已卸载时 click 会落到遮罩上,
      // 把刚打开的确认框关掉)。菜单项自己的 click 必须放行, 否则依赖 click 触发的动作
      // (如 + → 附件 打开文件选择) 会被整条吞掉, 表现为"点了没反应"。
      if (ev.target && item.contains(ev.target)) return;
      ev.preventDefault();
      ev.stopPropagation();
    };
    document.addEventListener('click', kill, true);
    setTimeout(function () {
      document.removeEventListener('click', kill, true);
    }, 700);
  }, true);
})();
