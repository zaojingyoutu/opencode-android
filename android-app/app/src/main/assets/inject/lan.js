(function () {
  if (window.__ocLanInit) return;
  window.__ocLanInit = 1;
  if (location.origin !== '__SERVER_URL__') return;
  var timer = null;
  function info() {
    try { return JSON.parse(window.OcLan.info()); } catch (e) { return null; }
  }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.setAttribute(cls.startsWith('data-') ? cls : 'class', cls);
    if (text != null) e.textContent = text;
    return e;
  }
  function row(title, desc, control) {
    var r = el('div', 'data-component', null);
    r.setAttribute('data-component', 'settings-v2-row');
    var copy = el('div', 'data-slot', null);
    copy.setAttribute('data-slot', 'settings-v2-row-copy');
    copy.appendChild(el('div', 'data-slot', null)).setAttribute('data-slot', 'settings-v2-row-title');
    copy.lastChild.textContent = title;
    if (desc != null) {
      copy.appendChild(el('div', 'data-slot', null)).setAttribute('data-slot', 'settings-v2-row-description');
      copy.lastChild.textContent = desc;
    }
    var ctl = el('div', 'data-slot', null);
    ctl.setAttribute('data-slot', 'settings-v2-row-control');
    if (control) ctl.appendChild(control);
    r.appendChild(copy);
    r.appendChild(ctl);
    return r;
  }
  /* 复刻应用原生 Switch 结构; 选中态样式内联 (应用 CSS 无可用选中态规则):
     开 = 品牌绿 + 滑块右移, 关 = 跟随应用默认灰 */
  function makeSwitch(checked, onChange) {
    var wrap = el('div', 'data-component', null);
    wrap.setAttribute('role', 'group');
    wrap.setAttribute('data-component', 'switch');
    var input = document.createElement('input');
    input.type = 'checkbox';
    input.setAttribute('role', 'switch');
    input.setAttribute('data-slot', 'switch-input');
    input.style.cssText = 'border:0;clip:rect(0 0 0 0);clip-path:inset(50%);height:1px;' +
      'margin:0 -1px -1px 0;overflow:hidden;padding:0;position:absolute;width:1px;white-space:nowrap';
    var ctl = el('div', 'data-slot', null);
    ctl.setAttribute('data-slot', 'switch-control');
    ctl.style.cssText = 'cursor:pointer;position:relative;width:24px;height:16px;' +
      'border-radius:4px;transition:background-color .15s';
    var thumb = el('div', 'data-slot', null);
    thumb.setAttribute('data-slot', 'switch-thumb');
    thumb.style.cssText = 'position:absolute;width:12px;height:12px;border-radius:2px;' +
      'top:2px;left:2px;transition:transform .15s';
    ctl.appendChild(thumb);
    wrap.appendChild(input);
    wrap.appendChild(ctl);
    function apply() {
      var on = input.checked;
      input.setAttribute('aria-checked', String(on));
      if (on) {
        ctl.style.backgroundColor = '#3fb950';
        thumb.style.transform = 'translateX(8px)';
        thumb.style.backgroundColor = '#fff';
      } else {
        ctl.style.backgroundColor = '';
        thumb.style.transform = 'translateX(0)';
        thumb.style.backgroundColor = '';
      }
    }
    input.checked = checked;
    apply();
    ctl.addEventListener('click', function (e) {
      e.stopPropagation();
      input.checked = !input.checked;
      apply();
      onChange(input.checked);
    });
    return wrap;
  }
  function miniBtn(txt, onClick) {
    var b = el('button', null, txt);
    b.style.cssText = 'font-size:12px;padding:3px 10px;cursor:pointer;color:inherit;' +
      'background:transparent;border:1px solid color-mix(in srgb, currentColor 30%, transparent);' +
      'border-radius:8px;opacity:.8;white-space:nowrap';
    b.onclick = function (e) { e.stopPropagation(); onClick(); };
    return b;
  }
  function mkSection() {
    var d = info();
    if (!d || !d.user) return null;
    var sec = el('div', 'settings-v2-section');
    sec.appendChild(el('h3', 'settings-v2-section-title', '局域网访问'));
    var list = el('div', 'data-component', null);
    list.setAttribute('data-component', 'settings-v2-list');

    // 开关行 (原生 Switch)。标题用"启用"而非"局域网访问", 避免与区块标题重复
    list.appendChild(row('启用',
      '开启后同一 Wi-Fi 下的电脑/平板可打开本页 (大屏使用)',
      makeSwitch(!!d.enabled, function (v) { window.OcLan.setEnabled(v); })));

    if (d.enabled) {
      var urlDesc = el('div');
      urlDesc.textContent = d.url || '(未获取到 Wi-Fi IP)';
      urlDesc.style.userSelect = 'text';
      urlDesc.style.opacity = '.85';
      urlDesc.style.wordBreak = 'break-all';
      list.appendChild(row('访问地址', '', null));
      var urlRow = list.lastChild;
      var urlCtrl = urlRow.querySelector('[data-slot="settings-v2-row-control"]');
      urlCtrl.style.cssText = 'display:flex;flex-wrap:wrap;gap:6px;align-items:center;justify-content:flex-end;min-width:0';
      urlCtrl.appendChild(urlDesc);
      urlCtrl.appendChild(miniBtn('复制', function () { window.OcLan.copy(); }));
    }

    // 本机自动登录和局域网共用这一份密码，不打开局域网也能改
    var pwInput = document.createElement('input');
    pwInput.type = 'text';
    pwInput.placeholder = '输入新密码 (至少 6 位)';
    pwInput.style.cssText = 'flex:1 1 auto;min-width:96px;max-width:180px;font-size:12px;padding:3px 8px;color:inherit;' +
      'background:transparent;border:1px solid color-mix(in srgb, currentColor 30%, transparent);' +
      'border-radius:8px';
    var pwCtl = el('div');
    pwCtl.style.cssText = 'display:flex;flex-wrap:wrap;gap:6px;align-items:center;justify-content:flex-end;min-width:0';
    pwCtl.appendChild(pwInput);
    pwCtl.appendChild(miniBtn('保存', function () {
      var v = pwInput.value.trim();
      if (v.length < 6 || v.length > 64) {
        pwInput.value = '';
        pwInput.placeholder = '密码长度需 6~64 位';
        return;
      }
      window.OcLan.setPassword(v);
    }));
    list.appendChild(row('访问密码', '当前: ' + d.password + '。本机登录也用它，保存后会重启服务', pwCtl));

    sec.appendChild(list);
    return sec;
  }
  function tabLabel(el) {
    return (el.textContent || '').replace(/\s+/g, ' ').trim();
  }
  function isHostTab(el) {
    var label = tabLabel(el);
    return label === '服务器' || /^servers$/i.test(label) ||
        label === '通用' || /^general$/i.test(label);
  }
  function hostTabs() {
    function collect(selector) {
      var nodes = document.querySelectorAll(selector);
      var tabs = [];
      for (var i = 0; i < nodes.length; i++) {
        if (isHostTab(nodes[i])) tabs.push(nodes[i]);
      }
      return tabs;
    }
    var tabs = collect('[role="tab"]');
    if (tabs.length) return tabs;
    return collect('button, a');
  }
  function tabSelected(tab) {
    if (tab.getAttribute('aria-selected') === 'true') return true;
    var current = tab.getAttribute('aria-current');
    return current === 'page' || current === 'true';
  }
  function contentFor(tab) {
    var id = tab.getAttribute('aria-controls');
    if (id) {
      var byId = document.getElementById(id);
      if (byId) return byId;
    }
    if (tab.id && /^[\w:-]+$/.test(tab.id)) {
      var labelled = document.querySelector('[role="tabpanel"][aria-labelledby="' + tab.id + '"]');
      if (labelled) return labelled;
    }
    var root = tab.closest('[role="dialog"]') || tab.closest('dialog') || document.body;
    var panels = root.querySelectorAll('[role="tabpanel"]');
    for (var i = 0; i < panels.length; i++) {
      var panel = panels[i];
      if (panel.hasAttribute('hidden')) continue;
      var st = getComputedStyle(panel);
      if (st.display === 'none' || st.visibility === 'hidden') continue;
      return panel;
    }
    var anc = tab;
    while (anc && anc !== document.body) {
      var bodies = anc.querySelectorAll('.settings-v2-tab-body');
      for (var b = 0; b < bodies.length; b++) {
        if (getComputedStyle(bodies[b]).display !== 'none') return bodies[b];
      }
      anc = anc.parentElement;
    }
    return null;
  }
  function tryInject() {
    var tabs = hostTabs();
    var active = null;
    for (var i = 0; i < tabs.length; i++) {
      if (tabSelected(tabs[i])) { active = tabs[i]; break; }
    }
    var d = info();
    var sec = document.querySelector('.oc-lan-sec');
    if (!active || !d) {
      if (sec) sec.style.display = 'none';
      return;
    }
    var on = d.enabled ? '1' : '0';
    if (sec && sec.getAttribute('data-on') !== on) {
      sec.remove();
      sec = null;
    }
    if (sec) {
      sec.style.display = '';
      return;
    }
    var panel = contentFor(active);
    if (!panel) return;
    var s = mkSection();
    if (!s) return;
    s.className += ' oc-lan-sec';
    s.setAttribute('data-on', on);
    panel.appendChild(s);
  }
  // 轮询注入: 每秒检查一次 (设置面板打开且节缺失时补注)。
  // 之前用 MutationObserver 在部分页面实例上不触发, 轮询虽朴素但绝对可靠,
  // 开销为一次 querySelector 级别, 可忽略。
  // __ocLanTry 暴露给外部 (CDP 调试/原生侧手动触发)
  function start() {
    tryInject();
    setInterval(tryInject, 1000);
    window.__ocLanTry = tryInject;
  }
  if (document.body) start();
  else document.addEventListener('DOMContentLoaded', start);
})();
