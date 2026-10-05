package addetector.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import addetector.Runner;
import addetector.ScanOptions;
import addetector.crawl.Crawler;
import addetector.model.Finding;
import addetector.model.Technique;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class OutputTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Finding finding(String url, String location, Technique technique, String evidence) {
        return new Finding(url, location, technique, evidence, null,
            new Finding.Detail("풀어 읽은 문구", "방법", List.of("근거"), List.of("카지노")));
    }

    public static Set<ValidationMessage> validate(Path resultFile) throws IOException {
        try (InputStream in = OutputTest.class.getResourceAsStream("/result.schema.json")) {
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
            return schema.validate(MAPPER.readTree(Files.readAllBytes(resultFile)));
        }
    }

    @Test
    void collectorDeduplicatesByScoringUnit() {
        FindingCollector c = new FindingCollector();
        assertTrue(c.add(finding("https://a.kr/v?id=1&x=2", "div.a > span", Technique.JAMO, "ㅋㅏㅈㅣㄴㅗ")));
        // 같은 페이지(쿼리 순서·scheme·# 무시), 같은 위치, 같은 기법 → 한 건
        assertFalse(c.add(finding("http://a.kr/v?x=2&id=1#c", "div.a > span", Technique.JAMO, "ㅋㅏㅈㅣㄴㅗ")));
        // 같은 요소라도 기법이 다르면 별도 건
        assertTrue(c.add(finding("https://a.kr/v?id=1&x=2", "div.a > span", Technique.OFFSCREEN, "ㅋㅏㅈㅣㄴㅗ")));
        // 쿼리 값이 다르면 다른 페이지
        assertTrue(c.add(finding("https://a.kr/v?id=2&x=2", "div.a > span", Technique.JAMO, "ㅋㅏㅈㅣㄴㅗ")));
        // 위치가 없는 것은 받지 않는다.
        assertFalse(c.add(finding("https://a.kr/", " ", Technique.JAMO, "x")));
        assertEquals(3, c.core().size());
        assertTrue(c.extra().isEmpty());
    }

    @Test
    void collectorOrderDoesNotDependOnArrivalOrder() {
        Finding a = finding("https://a.kr/b", "p.x", Technique.TRANSPARENT, "1");
        Finding b = finding("https://a.kr/a", "p.y", Technique.JAMO, "2");
        Finding c = finding("https://a.kr/a", "p.x", Technique.OFFSCREEN, "3");
        FindingCollector one = new FindingCollector();
        FindingCollector two = new FindingCollector();
        List.of(a, b, c).forEach(one::add);
        List.of(c, a, b).forEach(two::add);
        assertEquals(one.core(), two.core());
        assertEquals("3", one.core().get(0).evidenceText());
    }

    @Test
    void extraFindingsAreKeptApart() {
        FindingCollector c = new FindingCollector();
        c.add(finding("https://a.kr/", "p.x", Technique.JAMO, "1"));
        c.add(new Finding("https://a.kr/", "p.x", Technique.ETC, "2", "ZERO_WIDTH(제로폭 문자 삽입)", null));
        assertEquals(1, c.core().size());
        assertEquals(1, c.extra().size());
    }

    @Test
    void resultFileMatchesContestSchema(@TempDir Path dir) throws IOException {
        ResultWriter.Meta meta = new ResultWriter.Meta("TOPIC", "https://www.example.go.kr", OffsetDateTime.parse("2026-10-26T10:00:00.123+09:00"),
            OffsetDateTime.parse("2026-10-26T10:19:42.9+09:00"), 1182.44, "0.1.0");
        List<Finding> findings = List.of(
            finding("https://www.example.go.kr/board/view?id=7", "div.notice-board > span.visually-hidden", Technique.JAMO,
                "ㅋㅏㅈㅣㄴㅗ ㅂㅗㄴㅓㅅㅡ 코드 LUCKY7"),
            finding("https://www.example.go.kr/main", "iframe[src=\"https://www.example.go.kr/widget.html\"] >>> span.hidden-link",
                Technique.HOMOGLYPH, "ｍｅｇａ－ＢＥＴ 첫충 30％ 지급 \"따옴표\" \\ 역슬래시\n줄바꿈"));
        Path file = dir.resolve("result.json");
        ResultWriter.write(file, meta, findings);

        byte[] bytes = Files.readAllBytes(file);
        // UTF-8, BOM 없음
        assertNotEquals((byte) 0xEF, bytes[0]);
        assertEquals('{', bytes[0]);
        assertTrue(validate(file).isEmpty(), validate(file).toString());

        JsonNode root = MAPPER.readTree(bytes);
        assertEquals("2026-10-26T10:00:00+09:00", root.at("/meta/started_at").asText());
        assertEquals(1182.4, root.at("/meta/elapsed_sec").asDouble());
        assertEquals("f_001", root.at("/findings/0/id").asText());
        assertEquals("f_002", root.at("/findings/1/id").asText());
        assertTrue(root.at("/findings/0/is_violation").asBoolean());
        assertEquals(findings.get(1).evidenceText(), root.at("/findings/1/evidence_text").asText());
        assertEquals(findings.get(1).location(), root.at("/findings/1/location").asText());
        // 사람용 근거는 채점 파일에 쓰지 않는다.
        assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("풀어 읽은 문구"));
        // 임시 파일이 남지 않는다.
        assertFalse(Files.exists(dir.resolve("result.json.tmp")));
    }

    @Test
    void emptyResultStillHasFindingsArray(@TempDir Path dir) throws IOException {
        ResultWriter.Meta meta = new ResultWriter.Meta("TOPIC", "https://a.kr", OffsetDateTime.now(), OffsetDateTime.now(), 0, "0.1.0");
        Path file = dir.resolve("result.json");
        ResultWriter.write(file, meta, List.of());
        assertTrue(validate(file).isEmpty());
        assertTrue(MAPPER.readTree(Files.readAllBytes(file)).get("findings").isArray());
    }

    @Test
    void invalidInputStillWritesResultOnce(@TempDir Path dir) throws IOException {
        for (String bad : new String[] {"", "   ", "not a url", "ftp://a.kr/"}) {
            Runner.Outcome outcome = new Runner(ScanOptions.of(bad, dir), new Crawler.Listener() {}).run();
            assertEquals(Runner.Status.INVALID_INPUT, outcome.status());
            assertEquals(2, outcome.status().exitCode());
            assertTrue(outcome.resultWritten());
            Path file = dir.resolve("result.json");
            assertTrue(validate(file).isEmpty(), validate(file).toString());
            JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
            assertEquals(0, root.get("findings").size());
            assertEquals(bad, root.at("/meta/entry_url").asText());
        }
        // 추가 탐지 결과 파일도 같은 꼴로 생긴다.
        assertTrue(Files.exists(dir.resolve("result_extra.json")));
    }

    @Test
    void unwritableOutputIsReportedNotThrown(@TempDir Path dir) throws IOException {
        // 결과 폴더 자리에 파일이 있어 쓸 수 없는 경우
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "x");
        Runner.Outcome outcome = new Runner(ScanOptions.of("", blocked), new Crawler.Listener() {}).run();
        assertFalse(outcome.resultWritten());
    }

    @Test
    void reportFilesCarryEvidenceAndSharedAreaFlag(@TempDir Path dir) throws IOException {
        ResultWriter.Meta meta = new ResultWriter.Meta("TOPIC", "https://a.kr", OffsetDateTime.now(), OffsetDateTime.now(), 12, "0.1.0");
        ReportWriter.RunInfo info = new ReportWriter.RunInfo(meta, "정상 완료", "", 3, 0, 0, "chromium", 2, false);
        List<Finding> core = List.of(
            finding("https://a.kr/1", "div.footer > a.partner", Technique.OFFSCREEN, "카지노사이트 추천"),
            finding("https://a.kr/2", "div.footer > a.partner", Technique.OFFSCREEN, "카지노사이트 추천"),
            finding("https://a.kr/2", "p.x", Technique.JAMO, "</script><script>alert(1)</script>"));
        ObjectNode report = ReportWriter.toNode(info, core, List.of(), Map.of());
        ReportWriter.write(dir, report);

        JsonNode json = MAPPER.readTree(Files.readAllBytes(dir.resolve("report.json")));
        assertEquals(2, json.at("/findings/0/shared_pages").asInt());
        assertEquals(0, json.at("/findings/2/shared_pages").asInt());
        assertEquals("풀어 읽은 문구", json.at("/findings/0/decoded").asText());
        assertEquals(ReviewStore.key(core.get(0)), json.at("/findings/0/key").asText());

        String html = Files.readString(dir.resolve("report.html"), StandardCharsets.UTF_8);
        assertFalse(html.contains("/*__REPORT_DATA__*/null"));
        // 점검한 사이트의 글이 화면 스크립트를 끊고 나오지 못한다.
        assertFalse(html.contains("</script><script>alert(1)"));
        assertTrue(html.contains("카지노사이트 추천"));
    }

    @Test
    void reviewStateSurvivesRescanAndFlagsReappearance(@TempDir Path dir) throws IOException {
        Finding f = finding("https://a.kr/1", "p.x", Technique.JAMO, "ㅋㅏㅈㅣㄴㅗ");
        Finding g = finding("https://a.kr/2", "p.y", Technique.JAMO, "ㅌㅗㅌㅗ");
        ReviewStore store = ReviewStore.open(dir);
        store.onScan(List.of(f, g), "2026-10-05T10:00:00+09:00");
        store.set(ReviewStore.key(f), ReviewStore.RESOLVED, "게시글 삭제", "2026-10-05T11:00:00+09:00");
        store.set(ReviewStore.key(g), ReviewStore.FALSE_POSITIVE, "", "2026-10-05T11:00:00+09:00");
        assertThrows(IllegalArgumentException.class, () -> store.set(ReviewStore.key(f), "bogus", "", "2026-10-05T11:00:00+09:00"));

        // 다시 열어도(재실행) 상태가 남아 있고, 같은 건이 또 나오면 조치 완료였던 것만 재발견으로 표시된다.
        ReviewStore reopened = ReviewStore.open(dir);
        reopened.onScan(List.of(f, g), "2026-10-06T10:00:00+09:00");
        ReviewStore.Entry resolved = reopened.all().get(ReviewStore.key(f));
        assertEquals(ReviewStore.RESOLVED, resolved.status());
        assertEquals("게시글 삭제", resolved.memo());
        assertTrue(resolved.reappeared());
        assertFalse(reopened.all().get(ReviewStore.key(g)).reappeared());

        // 담당자가 다시 손대면 재발견 표시는 지워진다.
        reopened.set(ReviewStore.key(f), ReviewStore.CONFIRMED, "재확인", "2026-10-06T11:00:00+09:00");
        assertFalse(ReviewStore.open(dir).all().get(ReviewStore.key(f)).reappeared());

        // 손상된 파일은 버리고 새로 시작한다.
        Files.writeString(dir.resolve(ReviewStore.FILE), "{broken");
        assertTrue(ReviewStore.open(dir).all().isEmpty());
    }
}
