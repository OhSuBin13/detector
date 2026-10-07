package addetector.detect;

import addetector.detect.AdSignals.Assessment;
import addetector.detect.FrameSnapshot.Holder;
import addetector.detect.FrameSnapshot.Root;
import addetector.model.Finding;
import addetector.model.Technique;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CSS 은닉 기법(TRANSPARENT, OFFSCREEN) 공통 흐름.
 * <ol>
 *   <li>글자마다 숨김이 걸린 요소(root)를 찾는다. 여러 겹이면 글자에 가장 가까운 것.</li>
 *   <li>root 단위로 묶는다. 메뉴 안의 숨김과 길고 키워드가 드문 숨김(FAQ 답변·탭·전자책 페이지)은 정상 숨김으로 보고 뺀다.</li>
 *   <li>남은 root 중 의미 게이트({@link AdSignals.Assessment#hiddenAdLike()})를 통과한 것만 남긴다.</li>
 *   <li>통과한 root가 겹쳐 있으면 안쪽 것만 보고한다.</li>
 * </ol>
 */
abstract class HiddenDetector implements Detector {
    /** root 전체 글로 판정하는 최대 길이. 더 길면 큰 컨테이너이므로 안의 글 덩어리 단위로만 본다. */
    private static final int ROOT_TEXT_MAX = 300;
    private static final int CACHE_LIMIT = 50_000;

    private final AdSignals signals;
    private final Map<String, Assessment> cache = new HashMap<>();

    HiddenDetector(AdSignals signals) {
        this.signals = signals;
    }

    abstract Technique technique();

    abstract List<HidingSignal> signals(Holder holder);

    /** ETC 유형 이름. 핵심 기법이면 null. */
    String extraType() {
        return null;
    }

    private static final class Group {
        final int root;
        final Set<String> methods = new LinkedHashSet<>();
        final Set<String> reasons = new LinkedHashSet<>();
        final List<Holder> holders = new ArrayList<>();

        Group(int root) {
            this.root = root;
        }
    }

    private Assessment assess(String text) {
        Assessment a = cache.get(text);
        if (a == null) {
            if (cache.size() >= CACHE_LIMIT) {
                cache.clear();
            }
            a = signals.assess(text);
            cache.put(text, a);
        }
        return a;
    }

    @Override
    public List<Candidate> detect(FrameContext context) {
        Map<Integer, Root> roots = context.snapshot().roots();
        Map<Integer, Group> groups = new LinkedHashMap<>();
        for (Holder h : context.snapshot().holders()) {
            List<HidingSignal> found = signals(h);
            if (found.isEmpty()) {
                continue;
            }
            HidingSignal nearest = null;
            for (HidingSignal s : found) {
                if (roots.containsKey(s.root()) && (nearest == null || roots.get(s.root()).d() > roots.get(nearest.root()).d())) {
                    nearest = s;
                }
            }
            if (nearest == null) {
                continue;
            }
            Group g = groups.computeIfAbsent(nearest.root(), Group::new);
            g.holders.add(h);
            g.methods.add(nearest.method());
            g.reasons.add(nearest.reason());
        }

        // 안쪽 root부터 본다.
        List<Group> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingInt((Group g) -> roots.get(g.root).d()).reversed());
        Set<Integer> coveredAncestors = new HashSet<>();
        List<Candidate> out = new ArrayList<>();
        for (Group g : ordered) {
            Root root = roots.get(g.root);
            // 메뉴 안의 숨김, 그리고 길고 키워드가 드문 숨김(FAQ 답변·탭·전자책 페이지)은 정상 숨김으로 본다.
            // 숨긴 광고는 짧은 문구이거나 키워드가 몰린 목록이다.
            if (root.m() == 1 || !assess(root.t()).compact(root.n())) {
                continue;
            }
            // 숨긴 요소 안의 글 덩어리 하나가 그 자체로 광고인가
            Assessment direct = null;
            String directText = null;
            boolean covered = coveredAncestors.contains(g.root);
            for (Holder h : g.holders) {
                // 안쪽 요소가 이미 보고됐으면 자식 글을 합친 글은 보지 않는다(같은 문구를 두 번 보고하지 않게).
                for (String text : covered ? new String[] {h.t()} : new String[] {h.t(), h.f()}) {
                    if (direct == null && text != null && !text.isEmpty()) {
                        Assessment a = assess(text);
                        if (a.hiddenAdLike()) {
                            direct = a;
                            directText = text;
                        }
                    }
                }
            }
            // 숨긴 요소 전체 글이 광고인가(여러 요소에 나눠 쓴 문구)
            Assessment whole = null;
            if (root.n() <= ROOT_TEXT_MAX && !root.t().isEmpty()) {
                Assessment a = assess(root.t());
                if (a.hiddenAdLike()) {
                    whole = a;
                }
            }
            if (direct == null && (whole == null || covered)) {
                continue;
            }
            Assessment a = whole != null ? whole : direct;
            String evidence = whole != null ? root.t() : directText;
            Set<String> keywords = new LinkedHashSet<>(a.keywordWords());
            keywords.addAll(a.contacts());
            List<String> reasons = new ArrayList<>(g.reasons);
            reasons.add("숨겨진 글에 불법광고 문구가 있습니다.");
            Finding.Detail detail = new Finding.Detail(a.decoded(), String.join(", ", g.methods), reasons, List.copyOf(keywords));
            out.add(new Candidate(g.root, technique(), TextDetector.clip(evidence), extraType(), detail));
            for (int p = root.p(), guard = 0; p >= 0 && guard < 1000; guard++) {
                coveredAncestors.add(p);
                Root parent = roots.get(p);
                p = parent == null ? -1 : parent.p();
            }
        }
        return out;
    }
}
