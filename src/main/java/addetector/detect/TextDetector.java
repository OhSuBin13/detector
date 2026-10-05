package addetector.detect;

import addetector.detect.FrameSnapshot.Holder;
import addetector.model.Technique;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 글자 위장 기법(JAMO, HOMOGLYPH) 공통 흐름.
 * 글자를 직접 가진 요소를 보고하고, 자식 글까지 합쳐야만 걸린 요소는 자손이 따로 걸렸으면 버린다.
 */
abstract class TextDetector implements Detector {
    /** 같은 문구(메뉴·바닥글)가 페이지마다 반복되므로 판정을 기억해 둔다. */
    private static final int CACHE_LIMIT = 50_000;
    static final int EVIDENCE_MAX = 500;

    private final Map<String, Optional<TextVerdict>> cache = new HashMap<>();

    abstract Technique technique();

    abstract TextVerdict analyze(String text);

    /** ETC 유형 이름. 핵심 기법이면 null. */
    String extraType() {
        return null;
    }

    private TextVerdict cached(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Optional<TextVerdict> v = cache.get(text);
        if (v == null) {
            if (cache.size() >= CACHE_LIMIT) {
                cache.clear();
            }
            v = Optional.ofNullable(analyze(text));
            cache.put(text, v);
        }
        return v.orElse(null);
    }

    @Override
    public List<Candidate> detect(FrameContext context) {
        Map<Integer, Candidate> found = new LinkedHashMap<>();
        Set<Integer> viaFull = new HashSet<>();
        for (Holder h : context.snapshot().holders()) {
            String evidence = h.t();
            TextVerdict v = cached(h.t());
            if (v == null && h.f() != null) {
                v = cached(h.f());
                evidence = h.f();
                if (v != null) {
                    viaFull.add(h.e());
                }
            }
            if (v != null) {
                found.put(h.e(), new Candidate(h.e(), technique(), clip(evidence), extraType(), v.toDetail()));
            }
        }
        if (!viaFull.isEmpty()) {
            Set<Integer> hasDescendant = new HashSet<>();
            for (int element : found.keySet()) {
                Holder h = context.holder(element);
                int guard = 0;
                while (h != null && h.ph() >= 0 && guard++ < 1000) {
                    hasDescendant.add(h.ph());
                    h = context.holder(h.ph());
                }
            }
            viaFull.retainAll(hasDescendant);
            found.keySet().removeAll(viaFull);
        }
        return new ArrayList<>(found.values());
    }

    static String clip(String text) {
        return text.length() <= EVIDENCE_MAX ? text : text.substring(0, EVIDENCE_MAX);
    }
}
