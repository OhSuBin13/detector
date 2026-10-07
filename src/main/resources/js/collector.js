// 공통 수집기: frame 안의 글자 요소와 숨김 신호를 한 번에 모은다.
// 판정(임계값 재확인·의미 게이트)은 Java 쪽 분석기가 한다. 여기서는 측정과 "숨김이 걸린 요소(root)" 찾기만 한다.
// 요소는 window.__adx.els 에 모아 두고 번호로 주고받는다(selector.js가 같은 번호를 쓴다).
(opts) => {
  const doc = document;
  const empty = { truncated: false, holders: [], roots: {} };
  if (!doc.body) return JSON.stringify(empty);

  const deadline = performance.now() + opts.budgetMs;
  const els = [];
  const idxOf = new Map();
  window.__adx = { els };
  const idx = (el) => {
    let i = idxOf.get(el);
    if (i === undefined) { i = els.length; els.push(el); idxOf.set(el, i); }
    return i;
  };

  const sx = window.scrollX || 0;
  const sy = window.scrollY || 0;
  const vw = Math.max(doc.documentElement.clientWidth || 0, window.innerWidth || 0);
  const XHTML = 'http://www.w3.org/1999/xhtml';
  const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'TEXTAREA', 'SELECT', 'OPTION', 'TITLE',
    'IFRAME', 'OBJECT', 'EMBED', 'CANVAS', 'AUDIO', 'VIDEO', 'MAP']);
  const norm = (s) => s.replace(/\s+/g, ' ').trim();

  const parseColor = (s) => {
    if (!s) return null;
    if (s === 'transparent') return [0, 0, 0, 0];
    const m = /^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:\s*[,/]\s*([\d.]+%?))?\s*\)$/.exec(s);
    if (!m) return null;
    const a = m[4] === undefined ? 1 : (m[4].endsWith('%') ? parseFloat(m[4]) / 100 : parseFloat(m[4]));
    return [+m[1], +m[2], +m[3], a];
  };
  const blend = (fg, bg) => [0, 1, 2].map((i) => fg[i] * fg[3] + bg[i] * (1 - fg[3]));
  const lum = (c) => {
    const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]);
  };
  const contrast = (a, b) => {
    const la = lum(a), lb = lum(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  };
  const css = (c) => 'rgb(' + c.slice(0, 3).map((v) => Math.round(v)).join(', ') + ')';

  // 요소별 계산값(조상으로 거슬러 올라가며 누적하는 값은 여기서 한 번만 구한다).
  const infoMap = new Map();
  const info = (el) => {
    let v = infoMap.get(el);
    if (v) return v;
    const cs = getComputedStyle(el);
    const parent = el.parentElement;
    const pi = parent ? info(parent) : null;
    let op = parseFloat(cs.opacity);
    if (isNaN(op)) op = 1;
    const fm = /opacity\(\s*([\d.]+)(%?)\s*\)/.exec(cs.filter || '');
    if (fm) op *= fm[2] ? parseFloat(fm[1]) / 100 : parseFloat(fm[1]);
    const bgc = parseColor(cs.backgroundColor);
    const bgImg = !!cs.backgroundImage && cs.backgroundImage !== 'none';
    const base = pi ? pi.bg : [255, 255, 255];
    const baseUnknown = pi ? pi.bgUnknown : false;
    let bg, bgUnknown;
    if (bgc === null) { bg = base; bgUnknown = true; }
    else if (bgc[3] >= 0.999) { bg = bgc.slice(0, 3); bgUnknown = bgImg; }
    else if (bgc[3] > 0) { bg = blend(bgc, base); bgUnknown = baseUnknown || bgImg; }
    else { bg = base; bgUnknown = baseUnknown || bgImg; }
    const dn = cs.display === 'none';
    v = {
      el, cs, pi, op, bg, bgUnknown,
      cumOp: (pi ? pi.cumOp : 1) * op,
      dnRoot: dn ? el : (pi ? pi.dnRoot : null),
      clipText: cs.webkitBackgroundClip === 'text' || cs.backgroundClip === 'text' || (pi ? pi.clipText : false),
      colorKey: cs.color + '|' + cs.webkitTextFillColor,
    };
    infoMap.set(el, v);
    return v;
  };

  // 1) 글자를 직접 가진 요소(holder)를 모은다.
  const holderMap = new Map();
  const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
  let truncated = false;
  let node;
  while ((node = walker.nextNode())) {
    const value = node.nodeValue;
    if (!value || !/\S/.test(value)) continue;
    const p = node.parentElement;
    if (!p || SKIP.has(p.tagName) || p.namespaceURI !== XHTML) continue;
    let h = holderMap.get(p);
    if (!h) {
      if (holderMap.size >= opts.maxHolders) { truncated = true; break; }
      h = { el: p, nodes: [] };
      holderMap.set(p, h);
    }
    h.nodes.push(node);
  }

  // 메뉴 안의 숨김인가: nav 조상, 메뉴 클래스(gnb·lnb·전체메뉴 등), 또는 GNB 하위 메뉴
  // (숨긴 요소가 링크 목록이고 같은 li에 보이는 링크·버튼이 있다). 링크 없는 글은 메뉴로 보지 않는다
  // (게시판 목록 li의 제목 옆에 숨긴 광고를 메뉴로 오인하지 않게).
  const MENU_CLASS = /(?:^|[\s_-])(gnb|lnb|snb|allmenu|sitemap|submenu|mega|depth[23]|[23]depth)(?=$|[\s_-]|\d)/i;
  const onScreen = (el) => {
    const q = el.getBoundingClientRect();
    if (q.width <= 1 || q.height <= 1) return false;
    return !el.checkVisibility || el.checkVisibility({ opacityProperty: true, visibilityProperty: true });
  };
  const inMenu = (el) => {
    if (el.closest('nav')) return 1;
    for (let a = el, k = 0; a && a !== doc.body && k < 25; a = a.parentElement, k++) {
      const cn = typeof a.className === 'string' ? a.className : (a.getAttribute('class') || '');
      if (MENU_CLASS.test(cn + ' ' + (a.id || ''))) return 1;
    }
    const li = el.closest('li');
    if (li && el.querySelector('a[href]')) {
      for (const c of li.querySelectorAll(':scope > a, :scope > button, :scope > * > a')) {
        if (!el.contains(c) && !c.contains(el) && onScreen(c)) return 1;
      }
    }
    return 0;
  };

  const roots = {};
  const rootEls = [];
  const addRoot = (el) => {
    const i = idx(el);
    if (!(i in roots)) {
      const t = norm(el.textContent || '');
      let depth = 0;
      for (let a = el; a; a = a.parentElement) depth++;
      roots[i] = { t: t.slice(0, opts.maxText), n: t.length, d: depth, p: -1, m: inMenu(el) };
      rootEls.push(el);
    }
    return i;
  };

  // 위장 글자가 있을 수 있는 글인가(없고 숨김 신호도 없으면 보내지 않는다).
  const INTERESTING = /[^\u0000-\u007F가-힣]|[A-Za-z][0-9@$!]|[0-9@$!][A-Za-z]|[가-힣][A-Za-z0-9|!^]|[A-Za-z0-9|!^][가-힣]|[A-Za-z]{4}/;
  const offLeft = (q) => q.right + sx < 0 || (q.right + sx <= 0 && q.width > 0);
  const offTop = (q) => q.bottom + sy < 0 || (q.bottom + sy <= 0 && q.height > 0);
  const offRight = (q) => q.left + sx >= vw + opts.farRight;
  const range = doc.createRange();
  const kept = [];
  const keptEls = new Set();

  // 2) holder마다 숨김 신호를 잰다.
  for (const h of holderMap.values()) {
    if (performance.now() > deadline) { truncated = true; break; }
    const el = h.el;
    const inf = info(el);
    const cs = inf.cs;
    const own = norm(h.nodes.map((n) => n.nodeValue).join(' '));
    const rec = { e: -1, t: own.slice(0, opts.maxText), ph: -1, dn: -1, vis: -1, clip: -1 };
    let signal = false;

    // display:none (자신 또는 조상)
    if (inf.dnRoot) { rec.dn = addRoot(inf.dnRoot); signal = true; }

    // font-size 0~1px
    const fs = parseFloat(cs.fontSize);
    if (fs <= opts.fontSizeMax) {
      let r = inf;
      while (r.pi && r.pi.el !== doc.body && parseFloat(r.pi.cs.fontSize) <= opts.fontSizeMax) r = r.pi;
      rec.fs = { v: fs, r: addRoot(r.el) };
      signal = true;
    }

    // 화면 밖 위치·잘림(그려지는 요소만)
    if (!inf.dnRoot) {
      let l = Infinity, t = Infinity, r = -Infinity, b = -Infinity, any = false;
      for (const n of h.nodes) {
        range.selectNodeContents(n);
        for (const q of range.getClientRects()) {
          any = true;
          if (q.left < l) l = q.left;
          if (q.top < t) t = q.top;
          if (q.right > r) r = q.right;
          if (q.bottom > b) b = q.bottom;
        }
      }
      if (any) {
        const box = { left: l, top: t, right: r, bottom: b, width: r - l, height: b - t };
        const test = offLeft(box) ? offLeft : offTop(box) ? offTop : offRight(box) ? offRight : null;
        if (test) {
          const how = test === offLeft ? 'left' : test === offTop ? 'top' : 'right';
          let root = el;
          let indent = false;
          if (test(el.getBoundingClientRect())) {
            while (root.parentElement && root.parentElement !== doc.body && root.parentElement !== doc.documentElement
                && test(root.parentElement.getBoundingClientRect())) {
              root = root.parentElement;
            }
          } else {
            // 요소는 화면 안에 있고 글자만 밀려난 경우(text-indent 등)
            const ti = cs.textIndent;
            indent = Math.abs(parseFloat(ti) || 0) >= 100;
            if (indent) {
              let ri = inf;
              while (ri.pi && ri.pi.el !== doc.body && ri.pi.cs.textIndent === ti) ri = ri.pi;
              root = ri.el;
            }
          }
          rec.off = { r: addRoot(root), how, indent, x: Math.round(l + sx), y: Math.round(t + sy) };
          signal = true;
        } else {
          // overflow·clip으로 잘려 보이지 않는 글자(추가 유형)
          for (let a = inf; a && a.el !== doc.body && a.el !== doc.documentElement; a = a.pi) {
            const c = a.cs;
            let how = null;
            const positioned = c.position === 'absolute' || c.position === 'fixed';
            if (positioned && c.clip && c.clip !== 'auto' && /^rect\((0px|1px)[, ]+(0px|1px)[, ]+(0px|1px)[, ]+(0px|1px)\)$/.test(c.clip)) {
              how = 'clip:' + c.clip;
            } else if (c.clipPath && /^(inset\((50|100)%|circle\(0)/.test(c.clipPath)) {
              how = 'clip-path:' + c.clipPath;
            } else if (c.display !== 'inline' && (c.overflowX === 'hidden' || c.overflowX === 'clip' || c.overflowY === 'hidden' || c.overflowY === 'clip')) {
              const q = a.el.getBoundingClientRect();
              const cutX = c.overflowX === 'hidden' || c.overflowX === 'clip';
              const cutY = c.overflowY === 'hidden' || c.overflowY === 'clip';
              const iw = Math.min(r, q.right) - Math.max(l, q.left);
              const ih = Math.min(b, q.bottom) - Math.max(t, q.top);
              if ((cutX && (q.width <= 1.5 || iw <= 0.5)) || (cutY && (q.height <= 1.5 || ih <= 0.5))) {
                how = 'overflow:hidden; ' + Math.round(q.width) + 'x' + Math.round(q.height) + 'px';
              }
            }
            if (how) { rec.clip = addRoot(a.el); rec.clipHow = how; signal = true; break; }
          }
        }
      }
    }

    // visibility:hidden (추가 유형)
    if (cs.visibility !== 'visible') {
      let r = inf;
      while (r.pi && r.pi.el !== doc.body && r.pi.cs.visibility !== 'visible') r = r.pi;
      rec.vis = addRoot(r.el);
      signal = true;
    }

    // 누적 opacity
    if (inf.cumOp <= opts.opacityMax) {
      let r = null;
      for (let i = inf; i; i = i.pi) { if (i.op <= opts.opacityMax) { r = i; break; } }
      if (!r) { for (let i = inf; i; i = i.pi) { if (i.op < 1) { r = i; break; } } }
      rec.op = { v: inf.cumOp, r: addRoot((r || inf).el) };
      signal = true;
    }

    // 글자색: 투명 또는 배경과 같은 색
    if (!inf.clipText) {
      const col = parseColor(cs.color);
      const fill = parseColor(cs.webkitTextFillColor);
      const fg = fill || col;
      const shadow = !!cs.textShadow && cs.textShadow !== 'none';
      const stroke = parseFloat(cs.webkitTextStrokeWidth) > 0;
      if (fg && !shadow && !stroke) {
        if (fg[3] <= opts.alphaMax) {
          let r = inf;
          while (r.pi && r.pi.el !== doc.body && r.pi.el !== doc.documentElement && r.pi.colorKey === inf.colorKey) r = r.pi;
          const prop = fill && col && fill.join() !== col.join() ? '-webkit-text-fill-color' : 'color';
          rec.ca = { v: fg[3], r: addRoot(r.el), how: prop };
          signal = true;
        } else if (!inf.bgUnknown) {
          const shown = blend(fg, inf.bg);
          const ratio = contrast(shown, inf.bg);
          if (ratio <= opts.contrastMax) {
            let r = inf;
            const bgKey = inf.bg.join();
            while (r.pi && r.pi.el !== doc.body && r.pi.el !== doc.documentElement && !r.pi.bgUnknown
                && r.pi.colorKey === inf.colorKey && r.pi.bg.join() === bgKey) {
              r = r.pi;
            }
            rec.sc = { v: Math.round(ratio * 1000) / 1000, r: addRoot(r.el), fg: css(shown), bg: css(inf.bg) };
            signal = true;
          }
        }
      }
    }

    if (signal || INTERESTING.test(own) || (el.childElementCount > 0 && INTERESTING.test(el.textContent || ''))) {
      rec.e = idx(el);
      if (el.childElementCount > 0) {
        const full = norm(el.textContent || '');
        if (full.length <= opts.maxFull && full !== own) rec.f = full;
      }
      rec.el = el;
      kept.push(rec);
      keptEls.add(el);
    }
  }

  // 3) 포함 관계: holder의 가장 가까운 holder 조상, root의 가장 가까운 root 조상
  for (const rec of kept) {
    for (let a = rec.el.parentElement; a; a = a.parentElement) {
      if (keptEls.has(a)) { rec.ph = idx(a); break; }
    }
    delete rec.el;
  }
  const rootSet = new Set(rootEls);
  for (const el of rootEls) {
    for (let a = el.parentElement; a; a = a.parentElement) {
      if (rootSet.has(a)) { roots[idx(el)].p = idx(a); break; }
    }
  }

  return JSON.stringify({ truncated, holders: kept, roots });
}
