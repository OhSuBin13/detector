package addetector.detect;

import addetector.detect.KeywordDictionary.Keyword;
import addetector.text.HangulJamo;
import addetector.text.JamoAligner;
import addetector.text.JamoStream;
import addetector.text.JamoText;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JAMO(자모 분해): 음절을 자모로 풀어 써서 키워드 매칭을 피한 문구를 찾는다.
 * <ol>
 *   <li>풀어 쓴 자모(일부만 풀었거나 닮은꼴이 섞여도 됨)를 이으면 광고 키워드가 된다.</li>
 *   <li>글 사이사이에 흩어 놓은 낱자모만 이으면 광고 키워드가 된다.</li>
 *   <li>풀어 쓴 낱말이 있고, 같은 글에 광고 문구가 있다.</li>
 * </ol>
 * ㅋㅋㅋ·ㅠㅠ 같은 채팅 자모는 음절이 되지 않으므로 걸리지 않는다.
 */
public final class JamoAnalyzer {
    /** 흩어 쓴 자모로 인정할 최소 키워드 길이(낱자모 수). 짧으면 우연히 맞는다. */
    private static final int SCATTERED_MIN_LENGTH = 4;
    /** 풀어 쓴 낱말로 인정할 최소 음절 수. */
    private static final int DECOMPOSED_MIN_SYLLABLES = 2;

    private final AdSignals signals;
    private final KeywordDictionary dictionary;

    public JamoAnalyzer(AdSignals signals) {
        this.signals = signals;
        this.dictionary = signals.dictionary();
    }

    /** 빠른 사전 검사: 낱자모가 하나라도 있는가. */
    public static boolean hasJamo(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x1100 && HangulJamo.toCompatJamo(c) != 0) {
                return true;
            }
        }
        return false;
    }

    /** @return 판정, 해당 없으면 null */
    public TextVerdict analyze(String rawText) {
        if (!hasJamo(rawText)) {
            return null;
        }
        // NFD로 저장된 정상 한글(조합형 자모가 음절로 합쳐지는 것)은 자모 분해가 아니다.
        String text = Normalizer.normalize(rawText, Normalizer.Form.NFC);
        if (!hasJamo(text)) {
            return null;
        }
        List<String> reasons = new ArrayList<>();
        List<Keyword> matched = new ArrayList<>();
        String decoded;

        JamoStream stream = JamoStream.of(text, true);
        List<JamoAligner.Match> matches = new ArrayList<>();
        for (JamoAligner.Match m : JamoAligner.find(stream, dictionary.koreanJamo(), true)) {
            if (m.jamo() >= 1 && m.jamo() + m.look() >= 2) {
                matches.add(m);
            }
        }
        matches = nonOverlapping(matches);
        if (!matches.isEmpty()) {
            StringBuilder sb = new StringBuilder(text);
            for (int i = matches.size() - 1; i >= 0; i--) {
                JamoAligner.Match m = matches.get(i);
                Keyword k = dictionary.korean().get(m.keyword());
                matched.add(0, k);
                sb.replace(m.srcStart(), m.srcEnd(), k.word());
            }
            decoded = JamoText.decode(sb.toString());
            reasons.add("풀어 쓴 자모를 다시 합치면 광고 키워드가 됩니다.");
            if (matches.stream().anyMatch(m -> m.look() > 0)) {
                reasons.add("자모 자리에 닮은꼴 글자(r→ㅏ, l→ㅣ 등)를 섞었습니다.");
            }
            if (matches.stream().anyMatch(m -> m.fuzzy() > 0)) {
                reasons.add("비슷한 자모(ㄱ↔ㅋ 등)로 바꿔 쓴 것을 같은 낱말로 보았습니다.");
            }
        } else {
            Keyword scattered = findScattered(text);
            if (scattered != null) {
                matched.add(scattered);
                decoded = scattered.word() + " ← " + JamoText.decode(text);
                reasons.add("글 사이에 흩어 놓은 낱자모만 이으면 광고 키워드가 됩니다.");
            } else if (hasDecomposedWord(text)) {
                decoded = JamoText.decode(text);
                reasons.add("자모로 풀어 쓴 낱말이 광고 문구와 함께 있습니다.");
            } else {
                return null;
            }
        }

        AdSignals.Assessment a = signals.assess(text, matched);
        if (a.blockedByContext()) {
            return null;
        }
        if (matched.isEmpty() ? !a.adLike() : a.maxWeight() < 2 && !a.adLike()) {
            return null;
        }
        Set<String> keywords = new LinkedHashSet<>();
        matched.forEach(k -> keywords.add(k.word()));
        keywords.addAll(a.keywordWords());
        keywords.addAll(a.contacts());
        return new TextVerdict(decoded, "자모 분해", reasons, List.copyOf(keywords));
    }

    /** 겹치는 일치 중 긴 것을 남긴다. */
    private static List<JamoAligner.Match> nonOverlapping(List<JamoAligner.Match> matches) {
        List<JamoAligner.Match> sorted = new ArrayList<>(matches);
        sorted.sort(Comparator.comparingInt((JamoAligner.Match m) -> m.from() - m.to())
            .thenComparingInt(JamoAligner.Match::fuzzy)
            .thenComparingInt(JamoAligner.Match::from));
        List<JamoAligner.Match> kept = new ArrayList<>();
        for (JamoAligner.Match m : sorted) {
            boolean overlap = false;
            for (JamoAligner.Match k : kept) {
                if (m.from() < k.to() && k.from() < m.to()) {
                    overlap = true;
                    break;
                }
            }
            if (!overlap) {
                kept.add(m);
            }
        }
        kept.sort(Comparator.comparingInt(JamoAligner.Match::from));
        return kept;
    }

    private Keyword findScattered(String text) {
        JamoStream only = JamoStream.standaloneOnly(text);
        if (only.units().size() < SCATTERED_MIN_LENGTH) {
            return null;
        }
        Keyword best = null;
        for (JamoAligner.Match m : JamoAligner.find(only, dictionary.koreanJamo(), false)) {
            Keyword k = dictionary.korean().get(m.keyword());
            if (k.jamo().length() >= SCATTERED_MIN_LENGTH && (best == null || k.jamo().length() > best.jamo().length())) {
                best = k;
            }
        }
        return best;
    }

    /** 풀어 쓴 자모가 음절 둘 이상으로 조합되는가. */
    private static boolean hasDecomposedWord(String text) {
        return syllables(JamoText.decode(text)) - syllables(text) >= DECOMPOSED_MIN_SYLLABLES;
    }

    private static int syllables(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (HangulJamo.isSyllable(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }
}
