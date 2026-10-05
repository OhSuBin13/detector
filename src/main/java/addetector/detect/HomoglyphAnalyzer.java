package addetector.detect;

import addetector.detect.KeywordDictionary.Keyword;
import addetector.text.HangulJamo;
import addetector.text.JamoAligner;
import addetector.text.JamoStream;
import addetector.text.JamoText;
import addetector.text.LatinSkeleton;
import addetector.text.LatinSkeleton.Kind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * HOMOGLYPH(닮은꼴 글자 위장): 키릴·전각 문자, 숫자 등 닮은 글자를 섞어 필터를 피한 문구를 찾는다.
 * <ul>
 *   <li>영문 경로: 닮은꼴을 걷어낸 뼈대가 광고 키워드가 된다({@code ｍｅｇａ－ＢＥＴ}, 키릴 섞인 casino, {@code c4sino}).</li>
 *   <li>한글 경로: 한글에 섞인 닮은꼴 글자를 자모로 읽으면 광고 키워드가 된다({@code 7r지노}).</li>
 *   <li>위장한 낱말이 있고, 같은 글에 광고 문구가 있다.</li>
 * </ul>
 * 진짜 낱자모가 섞인 것({@code ㅋr지노})은 JAMO로만 본다. 전각 숫자·기호만 있는 것(３０％)은 정상 표기로 본다.
 */
public final class HomoglyphAnalyzer {
    /** 숫자 치환만으로 인정하는 최소 키워드 길이(c4sino). 더 짧으면 다른 광고 신호가 있어야 한다. */
    private static final int LEET_MIN_LENGTH = 5;
    /** 키워드 없이 위장 낱말로 보는 최소 위장 글자 수. */
    private static final int DISGUISED_WORD_MIN = 2;

    private final AdSignals signals;
    private final KeywordDictionary dictionary;

    public HomoglyphAnalyzer(AdSignals signals) {
        this.signals = signals;
        this.dictionary = signals.dictionary();
    }

    /** 빠른 사전 검사: 볼 만한 글자가 있는가. */
    public static boolean worthAnalyzing(String text) {
        boolean latinOrDigit = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                if (Character.isLetterOrDigit(c)) {
                    latinOrDigit = true;
                }
            } else if (!HangulJamo.isSyllable(c) && !Character.isWhitespace(c)) {
                // 한글 음절이 아닌 비ASCII 글자: 전각·키릴·원문자(ⓒ) 등 닮은꼴일 수 있다.
                return true;
            }
        }
        // ASCII만 있어도 숫자 치환(c4sino)과 한글에 섞인 닮은꼴(7r지노)은 봐야 한다.
        return latinOrDigit;
    }

    /** 위장을 풀어 놓을 자리. */
    private record Patch(int start, int end, String replacement) {}

    /** @return 판정, 해당 없으면 null */
    public TextVerdict analyze(String text) {
        if (!worthAnalyzing(text)) {
            return null;
        }
        List<Keyword> matched = new ArrayList<>();
        List<Keyword> needSupport = new ArrayList<>();
        List<Patch> patches = new ArrayList<>();
        Set<String> reasons = new LinkedHashSet<>();
        boolean disguisedWord = false;

        // 영문 경로
        for (LatinSkeleton.Token token : LatinSkeleton.tokens(text)) {
            int compat = token.count(Kind.COMPAT);
            int script = token.count(Kind.SCRIPT);
            int diacritic = token.count(Kind.DIACRITIC);
            int leet = token.count(Kind.LEET);
            if (compat + script + diacritic + leet == 0) {
                continue;
            }
            boolean hit = false;
            for (Keyword k : dictionary.latin()) {
                for (LatinSkeleton.Match m : LatinSkeleton.find(token, k.word())) {
                    // 숫자 치환만 있는 경우, 낱말 안쪽에 낀 숫자가 있어야 치환으로 본다(끝에 붙은 숫자는 그냥 숫자).
                    if (m.disguised() == 0 && m.leetInside() == 0) {
                        continue;
                    }
                    int start = token.cells().get(m.from()).src();
                    int end = m.to() < token.cells().size() ? token.cells().get(m.to()).src() : token.end();
                    if (m.disguised() > 0) {
                        matched.add(k);
                        reasons.add(script > 0 ? "키릴·그리스 등 다른 문자의 닮은꼴 글자를 걷어내면 광고 키워드가 됩니다."
                            : compat > 0 ? "전각·특수 영문자를 일반 글자로 바꾸면 광고 키워드가 됩니다."
                            : "변형 글자를 일반 글자로 바꾸면 광고 키워드가 됩니다.");
                    } else if (k.word().length() >= LEET_MIN_LENGTH) {
                        matched.add(k);
                        reasons.add("글자 대신 넣은 숫자·기호(0→o, 1→l, 4→a 등)를 되돌리면 광고 키워드가 됩니다.");
                    } else {
                        needSupport.add(k);
                    }
                    patches.add(new Patch(start, end, k.word()));
                    hit = true;
                }
            }
            if (!hit && (compat >= DISGUISED_WORD_MIN || script >= 1 && token.count(Kind.PLAIN) >= 1 && token.letters() >= 3)) {
                disguisedWord = true;
                patches.add(new Patch(token.start(), token.end(), token.reading()));
            }
        }

        // 한글 경로
        JamoStream stream = JamoStream.of(text, true);
        if (stream.lookCount() > 0) {
            List<JamoAligner.Match> found = new ArrayList<>();
            for (JamoAligner.Match m : JamoAligner.find(stream, dictionary.koreanJamo(), true)) {
                // 진짜 낱자모가 섞였으면 JAMO의 몫이다. 한글 음절이 하나도 없으면 한글을 흉내 낸 것으로 보지 않는다.
                if (m.look() >= 1 && m.jamo() == 0 && m.syl() >= 1) {
                    found.add(m);
                }
            }
            found.sort(Comparator.comparingInt((JamoAligner.Match m) -> m.from() - m.to()).thenComparingInt(JamoAligner.Match::fuzzy));
            List<JamoAligner.Match> kept = new ArrayList<>();
            for (JamoAligner.Match m : found) {
                if (kept.stream().noneMatch(k -> m.from() < k.to() && k.from() < m.to())) {
                    kept.add(m);
                }
            }
            for (JamoAligner.Match m : kept) {
                Keyword k = dictionary.korean().get(m.keyword());
                matched.add(k);
                patches.add(new Patch(m.srcStart(), m.srcEnd(), k.word()));
                reasons.add("한글 자리에 넣은 닮은꼴 글자(7→ㄱ, r→ㅏ, l→ㅣ 등)를 한글로 읽으면 광고 키워드가 됩니다.");
            }
        }

        if (matched.isEmpty() && needSupport.isEmpty() && !disguisedWord) {
            return null;
        }
        List<Keyword> known = new ArrayList<>(matched);
        known.addAll(needSupport);
        AdSignals.Assessment a = signals.assess(text, known);
        if (a.blockedByContext()) {
            return null;
        }
        if (matched.isEmpty()) {
            // 위장한 키워드를 확정하지 못했으면, 위장 낱말을 뺀 나머지 글만으로도 광고여야 한다.
            if (!signals.assess(text).adLike()) {
                return null;
            }
            reasons.add(needSupport.isEmpty()
                ? "닮은꼴 글자로 쓴 낱말이 광고 문구와 함께 있습니다."
                : "숫자·기호로 바꿔 쓴 낱말이 광고 문구와 함께 있습니다.");
        } else if (a.maxWeight() < 2 && !a.adLike()) {
            return null;
        }

        Set<String> keywords = new LinkedHashSet<>();
        known.forEach(k -> keywords.add(k.word()));
        keywords.addAll(a.keywordWords());
        keywords.addAll(a.contacts());
        return new TextVerdict(decode(text, patches), "닮은꼴 글자 위장", List.copyOf(reasons), List.copyOf(keywords));
    }

    private static String decode(String text, List<Patch> patches) {
        List<Patch> sorted = new ArrayList<>(patches);
        sorted.sort(Comparator.comparingInt(Patch::start).reversed());
        StringBuilder sb = new StringBuilder(text);
        int limit = text.length();
        for (Patch p : sorted) {
            if (p.end() <= limit && p.start() < p.end()) {
                sb.replace(p.start(), p.end(), p.replacement());
                limit = p.start();
            }
        }
        return JamoText.decode(sb.toString());
    }
}
