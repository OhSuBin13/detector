package addetector.detect;

import addetector.text.AhoCorasick;
import addetector.text.HangulJamo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 불법광고 키워드 사전. 만든 뒤에는 읽기 전용이라 워커들이 함께 쓴다. */
public final class KeywordDictionary {
    /**
     * @param word 키워드
     * @param weight 3 = 광고 전용어, 2 = 주제어, 1 = 약한 주제어
     * @param excludes 이 문구의 일부로 나오면 세지 않는다
     * @param jamo 낱자모 열(한글 키워드만)
     */
    public record Keyword(String word, int weight, String category, List<String> excludes, String jamo) {}

    /** 텍스트에서 찾은 키워드. end는 포함하지 않는다. */
    public record Hit(Keyword keyword, int start, int end) {}

    private final List<Keyword> korean;
    private final List<Keyword> latin;
    private final List<String> koreanJamo;
    private final AhoCorasick koreanMatcher;

    private KeywordDictionary(List<Keyword> korean, List<Keyword> latin) {
        this.korean = List.copyOf(korean);
        this.latin = List.copyOf(latin);
        this.koreanJamo = this.korean.stream().map(Keyword::jamo).toList();
        this.koreanMatcher = new AhoCorasick(this.korean.stream().map(Keyword::word).toList());
    }

    private static volatile KeywordDictionary shared;

    /** 기본 사전. 실행 폴더의 conf\keywords_*.txt 가 있으면 그것을, 없으면 내장 사전을 읽는다. */
    public static KeywordDictionary get() {
        KeywordDictionary d = shared;
        if (d == null) {
            synchronized (KeywordDictionary.class) {
                d = shared;
                if (d == null) {
                    d = load(confDir());
                    shared = d;
                }
            }
        }
        return d;
    }

    private static Path confDir() {
        String home = System.getProperty("addetector.home");
        return home == null ? null : Path.of(home, "conf");
    }

    public static KeywordDictionary load(Path confDir) {
        return new KeywordDictionary(read("keywords_ko.txt", confDir, true), read("keywords_en.txt", confDir, false));
    }

    private static List<Keyword> read(String name, Path confDir, boolean hangul) {
        List<Keyword> list = new ArrayList<>();
        try (BufferedReader reader = open(name, confDir)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 메모장으로 고친 사전 파일 맨 앞의 BOM을 뗀다.
                line = line.replace(String.valueOf((char) 0xFEFF), "").strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t");
                String word = f[0].strip().toLowerCase();
                if (word.isEmpty()) {
                    continue;
                }
                int weight = f.length > 1 ? parseWeight(f[1]) : 2;
                String category = f.length > 2 ? f[2].strip() : "기타";
                List<String> excludes = new ArrayList<>();
                if (f.length > 3) {
                    for (String e : f[3].split(",")) {
                        if (!e.isBlank()) {
                            excludes.add(e.strip().toLowerCase());
                        }
                    }
                }
                list.add(new Keyword(word, weight, category, List.copyOf(excludes), hangul ? HangulJamo.toJamo(word) : ""));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return list;
    }

    private static int parseWeight(String s) {
        try {
            return Math.max(1, Math.min(3, Integer.parseInt(s.strip())));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    private static BufferedReader open(String name, Path confDir) throws IOException {
        if (confDir != null) {
            Path external = confDir.resolve(name);
            if (Files.isRegularFile(external)) {
                return Files.newBufferedReader(external, StandardCharsets.UTF_8);
            }
        }
        InputStream in = KeywordDictionary.class.getResourceAsStream("/" + name);
        if (in == null) {
            throw new IOException("내장 사전이 없습니다: " + name);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    public List<Keyword> korean() {
        return korean;
    }

    public List<Keyword> latin() {
        return latin;
    }

    /** {@link #korean()}과 같은 순서의 낱자모 열. */
    public List<String> koreanJamo() {
        return koreanJamo;
    }

    /**
     * 한글 키워드를 찾는다. 제외 문맥에 걸린 것과 더 긴 키워드에 포함된 것은 뺀다.
     *
     * @param text 소문자로 바꾼 텍스트
     */
    public List<Hit> scanKorean(String text) {
        List<Hit> raw = new ArrayList<>();
        for (AhoCorasick.Hit h : koreanMatcher.search(text)) {
            Keyword k = korean.get(h.pattern());
            if (!excluded(text, k, h.start(), h.end())) {
                raw.add(new Hit(k, h.start(), h.end()));
            }
        }
        // 긴 것부터 보며, 이미 고른 구간에 완전히 들어가는 짧은 키워드는 버린다(먹튀검증 ⊃ 먹튀).
        raw.sort(Comparator.comparingInt((Hit h) -> h.start() - h.end()).thenComparingInt(Hit::start));
        List<Hit> kept = new ArrayList<>();
        for (Hit h : raw) {
            boolean inside = false;
            for (Hit k : kept) {
                if (h.start() >= k.start() && h.end() <= k.end()) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                kept.add(h);
            }
        }
        kept.sort(Comparator.comparingInt(Hit::start));
        return kept;
    }

    private static boolean excluded(String text, Keyword k, int start, int end) {
        for (String phrase : k.excludes()) {
            int offset = phrase.indexOf(k.word());
            int from = Math.max(0, start - phrase.length());
            int at = text.indexOf(phrase, from);
            while (at >= 0 && at <= start) {
                if (at + phrase.length() >= end && (offset < 0 || at <= start)) {
                    return true;
                }
                at = text.indexOf(phrase, at + 1);
            }
        }
        return false;
    }
}
