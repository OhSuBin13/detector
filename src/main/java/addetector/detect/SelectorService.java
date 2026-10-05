package addetector.detect;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 보고할 요소의 위치(location)를 공모전 표기로 만든다. 선택자 생성 자체는 selector.js가 한다. */
public final class SelectorService {
    private SelectorService() {}

    private static final String SELECTOR_JS = FrameSnapshot.resource("/js/selector.js");

    private static final String BY_INDEX = "(indices) => {\n" + SELECTOR_JS
        + "\n  const els = (window.__adx && window.__adx.els) || [];"
        + "\n  return indices.map((i) => (els[i] ? __adxSelector(els[i]) : null));\n}";

    /**
     * iframe 요소 → {tag, src(절대 주소), same(같은 주소의 frame 수), path(구조 선택자)}.
     * 같은 주소의 iframe이 여럿이면 주소만으로는 한 곳을 가리킬 수 없어 구조 선택자를 함께 쓴다.
     */
    private static final String FRAME_ELEMENT = "(el) => {\n" + SELECTOR_JS
        + "\n  const tag = el.localName;"
        + "\n  const src = el.getAttribute('src') ? el.src : '';"
        + "\n  let same = 0;"
        + "\n  if (src) for (const f of el.ownerDocument.querySelectorAll(tag)) if (f.getAttribute('src') && f.src === src) same++;"
        + "\n  return { tag, src, same, path: (!src || same > 1) ? __adxSelector(el) : null };\n}";

    public static final String FRAME_SEPARATOR = " >>> ";

    /** 요소 번호들의 선택자. 한 곳만 가리키는 선택자를 만들 수 없는 요소는 null. */
    @SuppressWarnings("unchecked")
    public static List<String> selectors(Frame frame, List<Integer> elements) {
        if (elements.isEmpty()) {
            return List.of();
        }
        Object result = frame.evaluate(BY_INDEX, elements);
        List<String> out = new ArrayList<>(elements.size());
        for (Object o : (List<Object>) result) {
            out.add(o == null ? null : o.toString());
        }
        return out;
    }

    /**
     * frame 안 요소 앞에 붙일 접두사. 최상위 frame이면 빈 문자열.
     * 예: {@code iframe[src="https://host/widget.html"] >>> }, 여러 겹이면 반복.
     *
     * @return 접두사, iframe 위치를 한 곳으로 특정할 수 없으면 null
     */
    @SuppressWarnings("unchecked")
    public static String framePrefix(Frame frame) {
        StringBuilder prefix = new StringBuilder();
        for (Frame f = frame; f.parentFrame() != null; f = f.parentFrame()) {
            ElementHandle element = f.frameElement();
            Map<String, Object> info = (Map<String, Object>) element.evaluate(FRAME_ELEMENT);
            String tag = String.valueOf(info.get("tag"));
            String src = String.valueOf(info.get("src"));
            Object path = info.get("path");
            String segment;
            if (!src.isEmpty() && path == null) {
                segment = tag + srcAttribute(src);
            } else if (path == null) {
                return null;
            } else if (src.isEmpty()) {
                segment = path.toString();
            } else {
                segment = withSrc(path.toString(), src);
            }
            prefix.insert(0, segment + FRAME_SEPARATOR);
        }
        return prefix.toString();
    }

    private static String srcAttribute(String src) {
        return "[src=\"" + src.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
    }

    /** 구조 선택자의 마지막 마디에 src 속성을 끼운다: {@code div > iframe:nth-of-type(2)} → {@code div > iframe[src="…"]:nth-of-type(2)} */
    static String withSrc(String path, String src) {
        int last = path.lastIndexOf(" > ");
        String head = last < 0 ? "" : path.substring(0, last + 3);
        String tail = last < 0 ? path : path.substring(last + 3);
        int pseudo = tail.indexOf(':');
        return head + (pseudo < 0 ? tail + srcAttribute(src) : tail.substring(0, pseudo) + srcAttribute(src) + tail.substring(pseudo));
    }
}
