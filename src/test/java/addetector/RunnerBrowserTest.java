package addetector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import addetector.crawl.BrowserLauncher;
import addetector.crawl.Crawler;
import addetector.detect.SelectorService;
import addetector.model.Finding;
import addetector.model.Technique;
import addetector.output.OutputTest;
import addetector.output.ReportWriter;
import addetector.output.ResultWriter;
import addetector.ui.LoopbackHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 브라우저를 띄우는 종료 경로·선택자·결과 화면 시험. */
@Tag("browser")
class RunnerBrowserTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private final java.util.Set<String> refusedOnce = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve(Map<String, String> pages) throws IOException {
        server = LoopbackHttp.create(0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/slow")) {
                    // 응답하지 않는 페이지
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                String body = pages.get(path);
                // "/busy..." 는 처음 한 번은 거부한다(요청이 몰리면 400으로 답하는 사이트 흉내).
                boolean refuse = path.startsWith("/busy") && refusedOnce.add(path);
                byte[] bytes = (body == null ? "not found" : body).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(refuse ? 400 : body == null ? 404 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-site");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static final String AD = "<span class=\"hidden-link\" style=\"position:absolute;left:-9999px\">카지노사이트 추천 바로가기</span>";

    @Test
    void budgetExceededWithHungPageStillWritesResult(@TempDir Path out) throws IOException {
        String base = serve(Map.of(
            "/", "<html><body><div class=\"wrap\">" + AD + "</div>"
                + "<a href=\"/hang.html\">멈추는 페이지</a> <a href=\"/slow\">응답 없는 페이지</a> <a href=\"/missing.html\">없는 페이지</a></body></html>",
            // 스크립트가 끝나지 않아 브라우저가 멈추는 페이지
            "/hang.html", "<html><body><p>hang</p><script>while (true) {}</script></body></html>"));
        long began = System.nanoTime();
        Runner.Outcome outcome = new Runner(
            ScanOptions.of(base + "/", out).withBudget(Duration.ofSeconds(8)).withWorkers(2), new Crawler.Listener() {}).run();
        double seconds = (System.nanoTime() - began) / 1e9;

        assertEquals(Runner.Status.BUDGET, outcome.status());
        assertTrue(outcome.resultWritten());
        // hard 마감(8초)에서 끊고 바로 기록한다.
        assertTrue(seconds < 15, "걸린 시간 " + seconds);
        assertTrue(OutputTest.validate(out.resolve("result.json")).isEmpty());
        JsonNode root = MAPPER.readTree(Files.readAllBytes(out.resolve("result.json")));
        assertEquals(1, root.get("findings").size());
        assertEquals("OFFSCREEN", root.at("/findings/0/technique").asText());
        assertEquals("div.wrap > span.hidden-link", root.at("/findings/0/location").asText());
        assertEquals(base + "/", root.at("/findings/0/url").asText());
    }

    @Test
    void refusedPagesAreRetriedAndDeadFramesDoNotBlock(@TempDir Path out) throws IOException {
        String base = serve(Map.of(
            // 응답하지 않는 iframe이 있어도 그 frame만 건너뛰고 본문은 점검한다.
            "/", "<html><body><div class=\"wrap\">" + AD + "</div><iframe src=\"/slow\"></iframe>"
                + "<a href=\"/busy.html\">처음에는 거부되는 페이지</a></body></html>",
            "/busy.html", "<html><body><div class=\"post\">" + AD + "</div></body></html>"));
        long began = System.nanoTime();
        Runner.Outcome outcome = new Runner(
            ScanOptions.of(base + "/", out).withBudget(Duration.ofSeconds(60)).withWorkers(1), new Crawler.Listener() {}).run();
        double seconds = (System.nanoTime() - began) / 1e9;

        assertEquals(Runner.Status.COMPLETED, outcome.status(), outcome.message());
        assertTrue(seconds < 30, "걸린 시간 " + seconds);
        JsonNode root = MAPPER.readTree(Files.readAllBytes(out.resolve("result.json")));
        assertEquals(2, root.get("findings").size());
        assertEquals(base + "/", root.at("/findings/0/url").asText());
        assertEquals(base + "/busy.html", root.at("/findings/1/url").asText());
        assertEquals("div.post > span.hidden-link", root.at("/findings/1/location").asText());
    }

    @Test
    void shutdownDuringScanWritesPartialResultExactlyOnce(@TempDir Path out) throws Exception {
        String base = serve(Map.of(
            "/", "<html><body><div class=\"wrap\">" + AD + "</div><a href=\"/slow\">응답 없는 페이지</a></body></html>"));
        Runner runner = new Runner(ScanOptions.of(base + "/", out).withBudget(Duration.ofSeconds(120)).withWorkers(1), new Crawler.Listener() {});
        java.util.concurrent.CompletableFuture<Runner.Outcome> running = java.util.concurrent.CompletableFuture.supplyAsync(runner::run);
        // 진입 페이지의 광고를 찾고, 응답 없는 페이지에 걸려 있는 동안
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (runner.collector().size() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(1, runner.collector().size());

        // Ctrl+C: 셧다운 훅이 하는 일을 그대로 부른다.
        runner.onShutdown();
        Path file = out.resolve("result.json");
        assertTrue(OutputTest.validate(file).isEmpty());
        JsonNode first = MAPPER.readTree(Files.readAllBytes(file));
        assertEquals(1, first.get("findings").size());
        java.nio.file.attribute.FileTime written = Files.getLastModifiedTime(file);

        // 본래 흐름이 뒤늦게 끝나도 다시 쓰지 않는다(정확히 1회).
        Runner.Outcome outcome = running.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(Runner.Status.STOPPED, outcome.status());
        assertTrue(outcome.resultWritten());
        assertEquals(written, Files.getLastModifiedTime(file));
        assertEquals(first, MAPPER.readTree(Files.readAllBytes(file)));
    }

    @Test
    void unreachableEntryIsAnErrorButStillWritesResult(@TempDir Path out) throws IOException {
        // 아무도 듣지 않는 포트
        String base = serve(Map.of());
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;
        Runner.Outcome outcome = new Runner(
            ScanOptions.of("http://127.0.0.1:" + port + "/", out).withBudget(Duration.ofSeconds(40)).withWorkers(1), new Crawler.Listener() {}).run();
        assertFalse(base.isEmpty());
        assertEquals(Runner.Status.ERROR, outcome.status());
        assertTrue(outcome.resultWritten());
        assertTrue(OutputTest.validate(out.resolve("result.json")).isEmpty());
        assertEquals(0, MAPPER.readTree(Files.readAllBytes(out.resolve("result.json"))).get("findings").size());
    }

    @Test
    void selectorsFollowContestNotationAndAreUnique() {
        BrowserLauncher.Session session = BrowserLauncher.launch(true);
        try {
            Page page = session.browser().newPage();
            page.setContent("""
                <html><body>
                <div class="notice-board"><span class="visually-hidden" data-t>1</span></div>
                <div id="only-id"><p>a</p><p data-t>2</p><p>c</p></div>
                <table class="board"><tbody>
                  <tr><td class="title"><a>x</a></td></tr>
                  <tr><td class="title"><a data-t>3</a></td></tr>
                </tbody></table>
                <ul class="list"><li class="item">a</li><li class="item on" data-t>4</li><li class="item">c</li></ul>
                <div class="md:flex 2col ok"><b data-t>5</b></div>
                <section><div class="box"><i data-t>6</i></div></section>
                <section><div class="box"><i data-t>7</i></div></section>
                </body></html>
                """);
            page.evaluate("() => { window.__adx = { els: Array.from(document.querySelectorAll('[data-t]')) }; }");
            List<String> selectors = SelectorService.selectors(page.mainFrame(), List.of(0, 1, 2, 3, 4, 5, 6));
            assertEquals(List.of(
                "div.notice-board > span.visually-hidden",
                // id는 쓰지 않고, 클래스 없는 요소는 순서로 지정한다.
                "div:nth-of-type(2) > p:nth-of-type(2)",
                "tr:nth-of-type(2) > td.title > a",
                // 같은 꼴 형제가 없으면 클래스만으로 충분하다.
                "ul.list > li.item.on",
                // 선택자로 쓰기 곤란한 클래스(md:flex, 2col)는 뺀다.
                "div.ok > b",
                "section:nth-of-type(1) > div.box > i",
                "section:nth-of-type(2) > div.box > i"), selectors);
            for (int i = 0; i < selectors.size(); i++) {
                Object count = page.evaluate("(s) => document.querySelectorAll(s).length", selectors.get(i));
                assertEquals(1, ((Number) count).intValue(), selectors.get(i));
                Object same = page.evaluate("([s, i]) => document.querySelector(s) === window.__adx.els[i]", List.of(selectors.get(i), i));
                assertEquals(Boolean.TRUE, same, selectors.get(i));
            }
        } finally {
            session.close();
        }
    }

    private static void writeSampleReport(Path out) throws IOException {
        ResultWriter.Meta meta = new ResultWriter.Meta("TOPIC", "https://a.kr", OffsetDateTime.now(), OffsetDateTime.now(), 12, "0.1.0");
        ReportWriter.RunInfo info = new ReportWriter.RunInfo(meta, "정상 완료", "", 3, 0, 0, "chromium", 2, false);
        Finding.Detail detail = new Finding.Detail("카지노 바로가기", "자모 분해", List.of("풀어 쓴 자모를 다시 합치면 광고 키워드가 됩니다."), List.of("카지노"));
        List<Finding> core = List.of(
            new Finding("https://a.kr/1", "div.ad-box", Technique.JAMO, "ㅋㅏㅈㅣㄴㅗ 바로가기", null, detail),
            new Finding("https://a.kr/2", "p.x", Technique.OFFSCREEN, "<img src=x onerror=\"document.title='xss'\">카지노사이트 추천", null, detail));
        ReportWriter.write(out, ReportWriter.toNode(info, core, List.of(), Map.of()));
    }

    @Test
    void staticReportRendersAndKeepsReviewState(@TempDir Path out) throws IOException {
        writeSampleReport(out);
        BrowserLauncher.Session session = BrowserLauncher.launch(true);
        try {
            checkStaticReport(session.browser(), out);
        } finally {
            session.close();
        }
    }

    /** 평가 PC의 기본 브라우저(Edge)에서도 결과 화면이 동작하는가. Edge가 없는 PC에서는 건너뛴다. */
    @Test
    void staticReportWorksInEdge(@TempDir Path out) throws IOException {
        writeSampleReport(out);
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Browser edge;
            try {
                edge = playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("msedge").setHeadless(true));
            } catch (PlaywrightException e) {
                Assumptions.abort("Edge가 설치되어 있지 않습니다: " + e.getMessage());
                return;
            }
            checkStaticReport(edge, out);
        }
    }

    private static void checkStaticReport(Browser browser, Path out) {
        {
            Page page = browser.newPage();
            String url = out.resolve("report.html").toUri().toString();
            page.navigate(url);
            assertEquals(2, page.locator(".item").count());
            assertTrue(page.locator("#cards").innerText().contains("2"));
            // 서버 없이 여는 화면에는 점검 시작 양식이 없다.
            assertFalse(page.locator("#scan").isVisible());
            // 점검한 사이트의 글은 글자로만 들어간다(스크립트로 실행되지 않는다).
            assertFalse(page.title().contains("xss"));
            assertEquals(0, page.locator(".item img").count());

            page.locator(".item .row").first().click();
            assertTrue(page.locator(".item.open mark").first().innerText().contains("카지노"));
            page.locator("select.status").first().selectOption("confirmed");
            page.locator(".item.open textarea").first().fill("10/5 확인");
            page.locator(".item.open textarea").first().dispatchEvent("change");

            // 다시 열어도 이 브라우저에 저장한 상태·메모가 남아 있다.
            page.reload();
            assertEquals("confirmed", page.locator("select.status").first().inputValue());
            assertEquals("10/5 확인", page.locator(".item textarea").first().inputValue());
            assertTrue(page.locator("#cards").innerText().contains("· 확인 1"));

            // CSV 내보내기: Excel이 읽는 꼴(BOM + 머리글)로 내려받아진다.
            Download csv = page.waitForDownload(() -> page.locator("#csv").click());
            assertTrue(csv.suggestedFilename().endsWith(".csv"), csv.suggestedFilename());
            String text = new String(readAll(csv), StandardCharsets.UTF_8);
            assertEquals(0xFEFF, text.charAt(0));
            assertTrue(text.contains("처리 상태"));
            assertTrue(text.contains("불법광고 확인"));
            assertTrue(text.contains("div.ad-box"));
        }
    }

    private static byte[] readAll(Download download) {
        try (java.io.InputStream in = download.createReadStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
