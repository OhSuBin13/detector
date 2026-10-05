package addetector.detect;

import addetector.detect.KeywordDictionary.Keyword;
import addetector.text.HangulJamo;
import addetector.text.JamoText;
import addetector.text.LatinSkeleton;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 의미 게이트. 숨김·위장 신호가 있어도 불법광고로 볼 근거(키워드·연락처)가 있고
 * 보도·단속·예방 문맥이 아닐 때만 보고하게 한다.
 */
public final class AdSignals {
    /** 광고 문구에 흔한 말. 키워드가 있을 때만 보조 근거로 센다. */
    private static final List<String> CUES = List.of(
        "가입", "지급", "코드", "바로가기", "문의", "추천인", "이벤트", "무료", "보너스", "쿠폰", "체험", "상담",
        "클릭", "접속", "주소", "도메인", "입금", "출금", "충전", "환전", "당첨", "수익", "배당", "적중",
        "신규", "혜택", "24시", "검증", "추천", "제휴", "최신", "안전", "bonus", "vip", "event", "free");

    /** 보도·단속·예방 문맥어. 광고는 스스로를 이렇게 부르지 않는다. */
    private static final List<String> REPORT_CONTEXT = List.of(
        "단속", "검거", "적발", "구속", "입건", "수사", "경찰", "검찰", "신고", "예방", "근절", "처벌", "징역",
        "벌금", "피해", "중독", "치유", "캠페인", "사행성", "범죄", "혐의", "기소", "보도자료", "기자", "위반",
        "방지", "금지", "대책", "주의보", "당부", "불법", "유해", "수법");

    private static final Pattern MESSENGER = Pattern.compile(
        "텔레그램|텔레\\s*@|telegram|카카오톡|카톡|kakao|위챗|wechat|라인\\s*(?:id|아이디)|(?<![a-z0-9._])@[a-z0-9_]{3,}",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DOMAIN = Pattern.compile(
        "(?<![a-z0-9@._-])(?:https?://)?((?:[a-z0-9-]+\\.)+(?:com|net|org|kr|io|xyz|top|vip|bet|club|site|online|cc|me|info|biz|app|link|live|win|fun|shop|store|pro|tv))(?![a-z0-9-])",
        Pattern.CASE_INSENSITIVE);
    /** 공공·교육기관 도메인은 광고 연락처로 보지 않는다. */
    private static final Pattern PUBLIC_DOMAIN = Pattern.compile("\\.(?:go|or|ac|re|ne|es|ms|hs|sc|mil)\\.kr$|\\.gov$|(?:^|\\.)korea\\.kr$");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:01[016789]|050\\d?)[-.\\s]?\\d{3,4}[-.\\s]?\\d{4}(?!\\d)");
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF\\u00AD]");

    /** "키워드가 글의 대부분"이라고 볼 글 길이(공백 제외 글자 수). */
    private static final int SHORT_TEXT = 30;
    /** 이보다 긴 글에서는 키워드 근처의 보조 신호만 센다(긴 메뉴·본문에 흩어진 "상담", "이벤트" 방지). */
    private static final int WHOLE_TEXT = 80;
    private static final int NEAR = 30;

    private final KeywordDictionary dictionary;

    public AdSignals(KeywordDictionary dictionary) {
        this.dictionary = dictionary;
    }

    public KeywordDictionary dictionary() {
        return dictionary;
    }

    /**
     * @param decoded 위장을 풀어 읽은 글
     * @param keywords 찾은 키워드(중복 없음)
     * @param keywordScore 키워드 가중치 합
     * @param cues 광고성 보조어
     * @param contacts 연락처 신호(메신저·외부 도메인·전화번호)
     * @param reportContext 보도·단속·예방 문맥어
     * @param letters 공백·문장부호를 뺀 글자 수
     */
    public record Assessment(
        String decoded,
        List<Keyword> keywords,
        int keywordScore,
        List<String> cues,
        List<String> contacts,
        List<String> reportContext,
        int letters) {

        public int maxWeight() {
            return keywords.stream().mapToInt(Keyword::weight).max().orElse(0);
        }

        /** 보도·단속·예방 문맥이라 광고로 보지 않는다. */
        public boolean blockedByContext() {
            if (!contacts.isEmpty()) {
                return false;
            }
            if (reportContext.size() >= 2) {
                return true;
            }
            return reportContext.size() == 1 && score() < 5;
        }

        public int score() {
            return Math.min(keywordScore, 9) + (keywordScore > 0 ? Math.min(cues.size(), 2) : 0) + (contacts.isEmpty() ? 0 : 2);
        }

        /**
         * 숨겨진 글을 광고로 볼 수 있는가.
         * 광고 전용어 하나, 또는 주제어 + 보조 신호가 있어야 한다.
         * 주제어 하나뿐이면 글이 짧아 그 말이 글의 대부분일 때만 인정한다(숨겨진 메뉴의 "카지노업 현황" 방지).
         */
        public boolean adLike() {
            if (maxWeight() < 2 || blockedByContext()) {
                return false;
            }
            return score() >= 3 || letters <= SHORT_TEXT && bare();
        }

        private boolean bare() {
            int keywordLetters = keywords.stream().mapToInt(k -> k.word().length()).sum();
            return keywordLetters * 10 >= letters * 7;
        }

        public List<String> keywordWords() {
            return keywords.stream().map(Keyword::word).toList();
        }
    }

    public Assessment assess(String text) {
        return assess(text, List.of());
    }

    /**
     * @param text 원문
     * @param known 분석기가 위장을 풀어 이미 찾아낸 키워드
     */
    public Assessment assess(String text, List<Keyword> known) {
        // 자모를 먼저 합친 뒤 전각 등을 접는다(NFKC를 먼저 하면 호환 자모가 조합형으로 바뀌어 받침이 붙지 않는다).
        String decoded = foldCompat(JamoText.decode(ZERO_WIDTH.matcher(text).replaceAll("")));
        String lower = decoded.toLowerCase();

        Map<String, Keyword> found = new LinkedHashMap<>();
        List<int[]> spans = new ArrayList<>();
        boolean unplaced = false;
        for (Keyword k : known) {
            found.put(k.word(), k);
            unplaced = true;
        }
        for (KeywordDictionary.Hit h : dictionary.scanKorean(lower)) {
            found.putIfAbsent(h.keyword().word(), h.keyword());
            spans.add(new int[] {h.start(), h.end()});
        }
        // 글자 사이에 구분자를 끼운 꼴(카.지.노)은 세 음절 이상 키워드만 인정한다.
        String squeezed = squeezeHangul(lower);
        if (!squeezed.equals(lower)) {
            for (KeywordDictionary.Hit h : dictionary.scanKorean(squeezed)) {
                if (h.keyword().word().length() >= 3 && found.putIfAbsent(h.keyword().word(), h.keyword()) == null) {
                    unplaced = true;
                }
            }
        }
        for (LatinSkeleton.Token token : LatinSkeleton.tokens(text)) {
            for (Keyword k : dictionary.latin()) {
                for (LatinSkeleton.Match m : LatinSkeleton.find(token, k.word())) {
                    // 숫자 치환만으로 맞춘 짧은 낱말(b3t)은 우연일 수 있어 세지 않는다.
                    if (m.leet() == 0 || k.word().length() >= 5 && m.leetInside() > 0) {
                        found.putIfAbsent(k.word(), k);
                        spans.add(new int[] {token.start(), token.end()});
                    }
                }
            }
        }
        // 다른 키워드에 포함되는 것은 한 번만 센다(카지노사이트 ⊃ 카지노).
        List<Keyword> keywords = new ArrayList<>();
        for (Keyword k : found.values()) {
            boolean inside = false;
            for (Keyword other : found.values()) {
                if (other != k && other.word().length() > k.word().length() && other.word().contains(k.word())) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                keywords.add(k);
            }
        }
        int keywordScore = keywords.stream().mapToInt(Keyword::weight).sum();

        // 짧은 글이거나 위치를 모르는 키워드가 있으면 글 전체에서, 아니면 키워드 근처에서만 보조 신호를 찾는다.
        boolean whole = unplaced || lower.length() <= WHOLE_TEXT;
        List<String> cues = new ArrayList<>();
        for (String cue : CUES) {
            for (int at = lower.indexOf(cue); at >= 0; at = lower.indexOf(cue, at + 1)) {
                if (whole || near(spans, at, at + cue.length())) {
                    cues.add(cue);
                    break;
                }
            }
        }
        Set<String> contacts = new LinkedHashSet<>();
        Matcher m = MESSENGER.matcher(lower);
        while (m.find()) {
            if (whole || near(spans, m.start(), m.end())) {
                contacts.add(m.group().strip());
                break;
            }
        }
        m = DOMAIN.matcher(lower);
        while (m.find()) {
            if (!PUBLIC_DOMAIN.matcher(m.group(1)).find() && (whole || near(spans, m.start(), m.end()))) {
                contacts.add(m.group(1));
                break;
            }
        }
        m = PHONE.matcher(lower);
        while (m.find()) {
            if (whole || near(spans, m.start(), m.end())) {
                contacts.add(m.group());
                break;
            }
        }
        List<String> context = new ArrayList<>();
        for (String word : REPORT_CONTEXT) {
            if (lower.contains(word)) {
                context.add(word);
            }
        }
        int letters = 0;
        for (int i = 0; i < decoded.length(); i++) {
            if (Character.isLetterOrDigit(decoded.charAt(i))) {
                letters++;
            }
        }
        return new Assessment(decoded, List.copyOf(keywords), keywordScore, List.copyOf(cues), List.copyOf(contacts), List.copyOf(context), letters);
    }

    /** 전각·원문자 등 호환 문자를 일반 글자로 접는다. 한글과 낱자모는 건드리지 않는다. */
    static String foldCompat(String s) {
        StringBuilder sb = null;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int len = Character.charCount(cp);
            String piece = null;
            if (cp >= 0x80 && !(len == 1 && (HangulJamo.isSyllable((char) cp) || HangulJamo.toCompatJamo((char) cp) != 0))) {
                String original = s.substring(i, i + len);
                String folded = Normalizer.normalize(original, Normalizer.Form.NFKC);
                if (!folded.equals(original)) {
                    piece = folded;
                }
            }
            if (piece != null && sb == null) {
                sb = new StringBuilder(s.length() + 8).append(s, 0, i);
            }
            if (sb != null) {
                sb.append(piece != null ? piece : s.substring(i, i + len));
            }
            i += len;
        }
        return sb == null ? s : sb.toString();
    }

    private static boolean near(List<int[]> spans, int start, int end) {
        for (int[] span : spans) {
            if (start <= span[1] + NEAR && end >= span[0] - NEAR) {
                return true;
            }
        }
        return false;
    }

    /** 한글 음절 사이에 낀 한두 칸짜리 구분자를 없앤다. */
    static String squeezeHangul(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            sb.append(c);
            i++;
            if (!HangulJamo.isSyllable(c)) {
                continue;
            }
            int j = i;
            while (j < text.length() && j - i < 2 && !Character.isLetterOrDigit(text.charAt(j))) {
                j++;
            }
            if (j > i && j < text.length() && HangulJamo.isSyllable(text.charAt(j))) {
                i = j;
            }
        }
        return sb.toString();
    }
}
