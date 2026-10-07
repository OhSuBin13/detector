// 평가 하네스용: 탐지 결과의 location이 가리키는 요소에 정답 표시(data-expect / data-expect-extra)가 있는지 본다.
// 입력: [{location, technique}]  (technique은 기법 코드, 추가 유형은 "ETC:이름")
// 출력(JSON 문자열): {expected: [기법…], results: [{ok, known, why}], missing: [{technique, snippet}]}
// known: 받아들이기로 한 오탐(대조군의 data-known-fp). 오탐으로 세지만 --strict 실패로는 보지 않는다.
(findings) => {
  // 선택자 한 마디를 문서에서 찾는다. 정확히 한 요소만 가리켜야 한다.
  const one = (doc, selector) => {
    let list;
    const m = /^(.*)\[src="((?:[^"\\]|\\.)*)"\](.*)$/.exec(selector);
    try {
      if (m) {
        // iframe[src="절대주소"]: DOM의 src 속성은 상대 주소일 수 있으므로 해석된 주소로 비교한다.
        const src = m[2].replace(/\\(.)/g, '$1');
        list = Array.from(doc.querySelectorAll(m[1] + m[3])).filter((e) => e.src === src);
      } else {
        list = Array.from(doc.querySelectorAll(selector));
      }
    } catch (e) {
      return { why: '잘못된 선택자: ' + selector };
    }
    if (list.length === 0) return { why: '가리키는 요소가 없음: ' + selector };
    if (list.length > 1) return { why: list.length + '곳을 가리킴: ' + selector };
    return { el: list[0] };
  };
  const resolve = (location) => {
    const parts = location.split(' >>> ');
    let doc = document;
    for (let i = 0; i < parts.length - 1; i++) {
      const r = one(doc, parts[i]);
      if (!r.el) return r;
      doc = r.el.contentDocument;
      if (!doc) return { why: 'frame 문서에 접근할 수 없음: ' + parts[i] };
    }
    return one(doc, parts[parts.length - 1]);
  };

  // 정답: 모든 frame의 표시된 요소
  const truth = new Map();
  const collect = (doc) => {
    for (const el of doc.querySelectorAll('[data-expect], [data-expect-extra]')) {
      const want = new Set();
      for (const t of (el.getAttribute('data-expect') || '').split(/\s+/)) if (t) want.add(t);
      for (const t of (el.getAttribute('data-expect-extra') || '').split(/\s+/)) if (t) want.add('ETC:' + t);
      truth.set(el, { want, got: new Set() });
    }
    for (const f of doc.querySelectorAll('iframe, frame')) {
      try { if (f.contentDocument) collect(f.contentDocument); } catch (e) { /* 다른 출처 */ }
    }
  };
  collect(document);

  const results = findings.map((f) => {
    const r = resolve(f.location);
    if (!r.el) return { ok: false, why: r.why };
    const t = truth.get(r.el);
    if (!t || !t.want.has(f.technique)) {
      const known = r.el.closest('[data-known-fp]');
      const why = '정답 표시가 없는 요소 <' + r.el.localName + ' class="' + (r.el.getAttribute('class') || '') + '">';
      return known
        ? { ok: false, known: true, why: '알려진 오탐(' + known.getAttribute('data-known-fp') + ') ' + why }
        : { ok: false, why };
    }
    if (t.got.has(f.technique)) return { ok: false, why: '같은 요소·기법을 두 번 보고' };
    t.got.add(f.technique);
    return { ok: true, why: '' };
  });

  const expected = [];
  const missing = [];
  for (const [el, t] of truth) {
    for (const technique of t.want) {
      expected.push(technique);
      if (!t.got.has(technique)) {
        missing.push({ technique, snippet: el.outerHTML.replace(/\s+/g, ' ').slice(0, 140) });
      }
    }
  }
  return JSON.stringify({ expected, results, missing });
}
