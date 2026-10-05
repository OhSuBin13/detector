// 공모전 표기(붙임4)에 맞는 CSS 선택자를 만든다.
//   - 태그명 + 클래스, 상위 요소부터 " > "로 연결
//   - 클래스가 없거나 같은 꼴의 형제가 있으면 :nth-of-type(n)
//   - id 선택자는 쓰지 않는다
// 문서 안에서 정확히 그 요소 하나만 가리키는 선택자를 찾지 못하면 null (보고하지 않는다).
function __adxSelector(el) {
  const doc = el.ownerDocument;
  if (!el.isConnected || el.getRootNode() !== doc) return null;
  const SAFE = /^-?[A-Za-z_][A-Za-z0-9_-]*$/;

  const classesOf = (e) => {
    const out = [];
    for (const c of e.classList) {
      if (SAFE.test(c) && !out.includes(c)) out.push(c);
    }
    return out;
  };
  const segment = (e) => {
    const tag = e.localName;
    const classes = classesOf(e);
    let s = tag + classes.map((c) => '.' + c).join('');
    const parent = e.parentElement;
    if (parent) {
      let position = 0;
      let sameType = 0;
      let sameShape = 0;
      for (const sib of parent.children) {
        if (sib.localName !== tag) continue;
        sameType++;
        if (sib === e) position = sameType;
        else if (classes.every((c) => sib.classList.contains(c))) sameShape++;
      }
      // 같은 태그·클래스 꼴의 형제가 있으면 순서로 구분한다.
      if (sameShape > 0) s += ':nth-of-type(' + position + ')';
    }
    return s;
  };
  const unique = (sel) => {
    try {
      const found = doc.querySelectorAll(sel);
      return found.length === 1 && found[0] === el;
    } catch (e) {
      return false;
    }
  };

  const parts = [];
  let cur = el;
  while (cur && cur.nodeType === 1) {
    const isRoot = cur === doc.documentElement;
    parts.unshift(isRoot ? cur.localName : segment(cur));
    const sel = parts.join(' > ');
    // 예시 표기처럼 부모까지는 적는다(요소 하나만 적으면 페이지가 조금만 달라도 여러 곳을 가리킬 수 있다).
    const enough = parts.length >= 2 || cur === doc.body || isRoot;
    if (enough && unique(sel)) return sel;
    if (isRoot) break;
    cur = cur.parentElement;
  }
  return null;
}
