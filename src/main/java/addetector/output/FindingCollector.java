package addetector.output;

import addetector.crawl.UrlNormalizer;
import addetector.model.Finding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 워커들이 찾은 것을 모은다. (정규화 url, location, technique)가 같으면 한 건이다(채점 단위).
 * 꺼낼 때는 정렬해서 주므로 워커 순서와 무관하게 같은 결과가 나온다. 스레드 안전.
 */
public final class FindingCollector {
    private final Map<String, Finding> findings = new LinkedHashMap<>();

    /** @return 새 항목이면 true */
    public synchronized boolean add(Finding finding) {
        if (finding.url() == null || finding.location() == null || finding.location().isBlank() || finding.technique() == null) {
            return false;
        }
        return findings.putIfAbsent(key(finding), finding) == null;
    }

    /** 중복 판정 키. 추가 유형은 유형 이름까지 본다. */
    public static String key(Finding f) {
        String type = f.extraType() == null ? f.technique().name() : f.technique().name() + ":" + f.extraType();
        return UrlNormalizer.key(f.url()) + "\n" + f.location() + "\n" + type;
    }

    public synchronized int size() {
        return findings.size();
    }

    /** 적격평가 대상(4기법). */
    public List<Finding> core() {
        return sorted(true);
    }

    /** 추가 유형(ETC). */
    public List<Finding> extra() {
        return sorted(false);
    }

    private synchronized List<Finding> sorted(boolean core) {
        List<Finding> list = new ArrayList<>();
        for (Finding f : findings.values()) {
            if (f.technique().core() == core) {
                list.add(f);
            }
        }
        list.sort(Comparator.comparing((Finding f) -> UrlNormalizer.key(f.url()))
            .thenComparing(Finding::location)
            .thenComparing(f -> f.technique().name())
            .thenComparing(f -> f.extraType() == null ? "" : f.extraType()));
        return list;
    }
}
