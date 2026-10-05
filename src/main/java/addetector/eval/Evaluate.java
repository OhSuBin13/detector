package addetector.eval;

import addetector.Runner;
import addetector.ScanOptions;
import addetector.Version;
import addetector.crawl.BrowserLauncher;
import addetector.crawl.Crawler;
import addetector.crawl.UrlNormalizer;
import addetector.detect.FrameSnapshot;
import addetector.model.Finding;
import addetector.model.Technique;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 품질 측정(설계 S4).
 * <pre>
 *   모의 사이트:  --site DIR --out DIR [--history FILE] [--strict]
 *       모의 사이트를 띄워 점검하고, 요소의 data-expect 정답과 비교해 기법별 precision/recall을 낸다.
 *   실사이트:    --result result.json                 → 손으로 판정할 review.csv를 만든다
 *                --result result.json --truth review.csv → verdict(TP/FP/FN)로 채점한다
 * </pre>
 */
public final class Evaluate {
    private Evaluate() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 빈 포트를 쓴다(다른 프로그램과 겹치지 않게). */
    private static final int MOCK_PORT = 0;
    /** 크롤러가 요청하면 안 되는 주소(첨부파일·상태를 바꾸는 링크). */
    private static final List<String> FORBIDDEN = List.of("/files/", "logout", "delete");
    private static final String JUDGE_JS = FrameSnapshot.resource("/js/judge.js");

    /** 기법별 집계. */
    private static final class Count {
        int expected;
        int tp;
        int fp;

        int fn() {
            return expected - tp;
        }

        double precision() {
            return tp + fp == 0 ? 1.0 : (double) tp / (tp + fp);
        }

        double recall() {
            return expected == 0 ? 1.0 : (double) tp / expected;
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
                opts.put(args[i].substring(2), hasValue ? args[++i] : "true");
            }
        }
        int code;
        if (opts.containsKey("result")) {
            code = realSite(Path.of(opts.get("result")), opts.containsKey("truth") ? Path.of(opts.get("truth")) : null);
        } else if (opts.containsKey("site")) {
            code = mockSite(
                Path.of(opts.get("site")),
                Path.of(opts.getOrDefault("out", "build/evaluate")),
                opts.containsKey("history") ? Path.of(opts.get("history")) : null,
                opts.containsKey("workers") ? Integer.parseInt(opts.get("workers")) : ScanOptions.defaultWorkers(),
                opts.containsKey("strict"));
        } else {
            System.out.println("사용법: --site DIR --out DIR [--history FILE] [--strict] | --result result.json [--truth review.csv]");
            code = 2;
        }
        System.exit(code);
    }

    // ───────────────────────── 모의 사이트

    static int mockSite(Path site, Path out, Path history, int workers, boolean strict) throws Exception {
        Files.createDirectories(out);
        try (StaticSiteServer server = StaticSiteServer.start(site, MOCK_PORT)) {
            String base = server.baseUrl();
            ScanOptions options = ScanOptions.of(base + "/", out.toAbsolutePath()).withBudget(Duration.ofMinutes(5)).withWorkers(workers);
            Runner.Outcome outcome = new Runner(options, new Crawler.Listener() {
                @Override
                public void onLog(String message) {
                    System.out.println("  " + message);
                }
            }).run();
            List<String> forbidden = server.requests().stream()
                .filter(r -> FORBIDDEN.stream().anyMatch(r::contains))
                .distinct().toList();

            List<Finding> all = new ArrayList<>(outcome.findings());
            all.addAll(outcome.extras());
            Map<String, Count> counts = new LinkedHashMap<>();
            for (Technique t : Technique.values()) {
                counts.put(t.name(), new Count());
            }
            List<String> problems = new ArrayList<>();
            judge(site, base, all, counts, problems);

            System.out.println();
            System.out.printf("모의 사이트 평가 (ad-detector %s) - %s, %d쪽, %.1f초%n",
                Version.get(), outcome.status().label(), outcome.pages(), outcome.elapsedSec());
            System.out.printf("%-12s %6s %6s %6s %6s %10s %8s%n", "기법", "정답", "정탐", "오탐", "미탐", "precision", "recall");
            Count total = new Count();
            for (Map.Entry<String, Count> e : counts.entrySet()) {
                Count c = e.getValue();
                print(e.getKey(), c);
                if (!e.getKey().equals(Technique.ETC.name())) {
                    total.expected += c.expected;
                    total.tp += c.tp;
                    total.fp += c.fp;
                }
            }
            print("핵심 4종 합계", total);
            problems.forEach(p -> System.out.println("  ! " + p));
            if (!forbidden.isEmpty()) {
                System.out.println("  ! 요청하면 안 되는 주소를 요청했습니다: " + forbidden);
            }
            if (history != null) {
                appendHistory(history, outcome, counts, total, forbidden.size());
            }
            boolean perfect = total.fp == 0 && total.fn() == 0 && forbidden.isEmpty() && outcome.status() == Runner.Status.COMPLETED;
            return strict && !perfect ? 1 : 0;
        }
    }

    private static void print(String name, Count c) {
        System.out.printf("%-12s %6d %6d %6d %6d %10.3f %8.3f%n", name, c.expected, c.tp, c.fp, c.fn(), c.precision(), c.recall());
    }

    /** 페이지를 직접 열어, 탐지 결과의 location이 가리키는 요소에 정답 표시가 있는지 본다. */
    private static void judge(Path site, String base, List<Finding> findings, Map<String, Count> counts, List<String> problems)
        throws IOException {
        List<String> pages = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(site)) {
            walk.filter(p -> p.toString().endsWith(".html")).forEach(p -> {
                String rel = site.relativize(p).toString().replace('\\', '/');
                // iframe 안에만 쓰는 문서와 방문하면 안 되는 페이지는 독립 페이지 정답이 아니다.
                if (!rel.startsWith("widget/") && FORBIDDEN.stream().noneMatch(("/" + rel)::contains)) {
                    pages.add(rel);
                }
            });
        }
        Map<String, List<Finding>> byPage = new LinkedHashMap<>();
        for (Finding f : findings) {
            byPage.computeIfAbsent(UrlNormalizer.key(f.url()), k -> new ArrayList<>()).add(f);
        }
        BrowserLauncher.Session session = BrowserLauncher.launch(true);
        try {
            Page page = session.browser().newPage();
            for (String rel : pages) {
                String url = base + "/" + rel;
                List<Finding> here = new ArrayList<>(byPage.getOrDefault(UrlNormalizer.key(url), List.of()));
                if (rel.equals("index.html")) {
                    // 진입 주소(/)로 보고된 것도 같은 페이지다.
                    here.addAll(byPage.getOrDefault(UrlNormalizer.key(base + "/"), List.of()));
                    byPage.remove(UrlNormalizer.key(base + "/"));
                }
                byPage.remove(UrlNormalizer.key(url));
                page.navigate(url);
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(3000));
                } catch (TimeoutError e) {
                    // 그대로 본다.
                }
                List<Map<String, String>> input = new ArrayList<>();
                for (Finding f : here) {
                    input.add(Map.of("location", f.location(), "technique", label(f)));
                }
                JsonNode verdict = MAPPER.readTree((String) page.evaluate(JUDGE_JS, input));
                for (JsonNode e : verdict.get("expected")) {
                    count(counts, e.asText()).expected++;
                }
                int i = 0;
                for (JsonNode r : verdict.get("results")) {
                    Finding f = here.get(i++);
                    Count c = count(counts, label(f));
                    if (r.get("ok").asBoolean()) {
                        c.tp++;
                    } else {
                        c.fp++;
                        problems.add("오탐 " + label(f) + " " + rel + " | " + f.location() + " | " + r.get("why").asText() + " | " + clip(f.evidenceText()));
                    }
                }
                for (JsonNode m : verdict.get("missing")) {
                    problems.add("미탐 " + m.get("technique").asText() + " " + rel + " | " + m.get("snippet").asText());
                }
            }
        } finally {
            session.close();
        }
        // 정답 페이지가 아닌 주소로 보고된 것은 모두 오탐이다.
        for (List<Finding> rest : byPage.values()) {
            for (Finding f : rest) {
                count(counts, label(f)).fp++;
                problems.add("오탐 " + label(f) + " (정답에 없는 페이지) " + f.url() + " | " + f.location());
            }
        }
    }

    /** 집계 이름: 핵심 기법은 코드, 추가 유형은 ETC. 정답 비교에는 추가 유형 이름의 괄호 앞부분을 쓴다. */
    private static String label(Finding f) {
        if (f.extraType() == null) {
            return f.technique().name();
        }
        int paren = f.extraType().indexOf('(');
        return "ETC:" + (paren < 0 ? f.extraType() : f.extraType().substring(0, paren));
    }

    private static Count count(Map<String, Count> counts, String label) {
        return counts.get(label.startsWith("ETC") ? "ETC" : label);
    }

    private static String clip(String s) {
        return s == null ? "" : s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static void appendHistory(Path history, Runner.Outcome outcome, Map<String, Count> counts, Count total, int forbidden)
        throws IOException {
        if (history.toAbsolutePath().getParent() != null) {
            Files.createDirectories(history.toAbsolutePath().getParent());
        }
        StringBuilder sb = new StringBuilder();
        if (!Files.exists(history)) {
            sb.append("time,version,pages,elapsed_sec,expected,tp,fp,fn,precision,recall");
            for (String t : counts.keySet()) {
                sb.append(',').append(t).append("_tp,").append(t).append("_fp,").append(t).append("_fn");
            }
            sb.append(",forbidden_requests\n");
        }
        sb.append(OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)).append(',').append(Version.get()).append(',')
            .append(outcome.pages()).append(',').append(String.format(Locale.ROOT, "%.1f", outcome.elapsedSec())).append(',')
            .append(total.expected).append(',').append(total.tp).append(',').append(total.fp).append(',').append(total.fn()).append(',')
            .append(String.format(Locale.ROOT, "%.3f,%.3f", total.precision(), total.recall()));
        for (Count c : counts.values()) {
            sb.append(',').append(c.tp).append(',').append(c.fp).append(',').append(c.fn());
        }
        sb.append(',').append(forbidden).append('\n');
        Files.writeString(history, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ───────────────────────── 실사이트

    /** Excel이 UTF-8로 읽게 하는 표식. */
    private static final String BOM = String.valueOf((char) 0xFEFF);
    private static final String[] REVIEW_COLUMNS = {"id", "technique", "url", "location", "evidence_text", "verdict"};

    static int realSite(Path result, Path truth) throws IOException {
        if (truth == null) {
            JsonNode root = MAPPER.readTree(Files.readAllBytes(result));
            Path review = result.toAbsolutePath().resolveSibling("review.csv");
            StringBuilder sb = new StringBuilder(BOM).append(String.join(",", REVIEW_COLUMNS)).append("\r\n");
            int n = 0;
            for (JsonNode f : root.path("findings")) {
                sb.append(Csv.line(List.of(
                    f.path("id").asText(), f.path("technique").asText(), f.path("url").asText(),
                    f.path("location").asText(), f.path("evidence_text").asText(), "")));
                n++;
            }
            Files.writeString(review, sb.toString(), StandardCharsets.UTF_8);
            System.out.println(n + "건을 " + review + " 에 썼습니다.");
            System.out.println("verdict 열에 TP(정탐) 또는 FP(오탐)를 적고, 놓친 것은 id를 비운 행을 추가해 verdict에 FN을 적으세요.");
            System.out.println("그다음: evaluate --result " + result + " --truth " + review);
            return 0;
        }
        List<List<String>> rows = Csv.parse(Files.readString(truth, StandardCharsets.UTF_8));
        if (rows.isEmpty()) {
            System.out.println("truth 파일이 비어 있습니다.");
            return 2;
        }
        List<String> header = rows.get(0).stream().map(h -> h.replace(BOM, "").strip().toLowerCase(Locale.ROOT)).toList();
        int techniqueAt = header.indexOf("technique");
        int verdictAt = header.indexOf("verdict");
        if (techniqueAt < 0 || verdictAt < 0) {
            System.out.println("truth 파일에 technique, verdict 열이 있어야 합니다.");
            return 2;
        }
        Map<String, Count> counts = new LinkedHashMap<>();
        Count total = new Count();
        int unjudged = 0;
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.size() <= Math.max(techniqueAt, verdictAt)) {
                continue;
            }
            String verdict = row.get(verdictAt).strip().toUpperCase(Locale.ROOT);
            Count c = counts.computeIfAbsent(row.get(techniqueAt).strip().toUpperCase(Locale.ROOT), k -> new Count());
            switch (verdict) {
                case "TP", "O", "정탐" -> {
                    c.tp++;
                    c.expected++;
                    total.tp++;
                    total.expected++;
                }
                case "FP", "X", "오탐" -> {
                    c.fp++;
                    total.fp++;
                }
                case "FN", "미탐" -> {
                    c.expected++;
                    total.expected++;
                }
                default -> unjudged++;
            }
        }
        System.out.printf("%-12s %6s %6s %6s %6s %10s %8s%n", "기법", "정답", "정탐", "오탐", "미탐", "precision", "recall");
        counts.forEach(Evaluate::print);
        print("합계", total);
        if (unjudged > 0) {
            System.out.println("  verdict가 비어 있는 행 " + unjudged + "개는 집계하지 않았습니다.");
        }
        System.out.println("  recall은 FN 행을 직접 추가했을 때만 의미가 있습니다.");
        return 0;
    }

    /** 최소한의 CSV 읽기·쓰기(RFC 4180). */
    static final class Csv {
        private Csv() {}

        static String line(List<String> cells) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(cells.get(i).replace("\"", "\"\"")).append('"');
            }
            return sb.append("\r\n").toString();
        }

        static List<List<String>> parse(String text) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else if (c == '"') {
                        quoted = false;
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    row.add(cell.toString());
                    cell.setLength(0);
                    if (row.size() > 1 || !row.get(0).isEmpty()) {
                        rows.add(row);
                    }
                    row = new ArrayList<>();
                } else {
                    cell.append(c);
                }
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                rows.add(row);
            }
            return rows;
        }
    }
}
