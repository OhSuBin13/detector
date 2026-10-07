package addetector.crawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import addetector.Runner;
import addetector.ScanOptions;
import addetector.model.Finding;
import addetector.ui.LoopbackHttp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

/**
 * 크롤링 중 예외가 나는 경우를 재현하고 지금의 동작을 기록한다(docs/CRAWL_EXCEPTIONS.md).
 * 고친 동작이 아니라 현재 동작을 단언한다. 관찰 내용은 build/investigate/repro/에 남긴다.
 */
@Tag("browser")
class CrawlExceptionBrowserTest {
    private static final String AD = "<span class=\"hidden-link\" style=\"position:absolute;left:-9999px\">카지노사이트 추천 바로가기</span>";
    private static final Path REPRO_DIR = Path.of("build", "investigate", "repro");

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private HttpServer server;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve(Map<String, Handler> routes) throws IOException {
        server = LoopbackHttp.create(0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
                Handler h = routes.get(path);
                if (h == null) {
                    send(exchange, 404, "text/html; charset=utf-8", "not found");
                } else {
                    h.handle(exchange);
                }
            } catch (IOException e) {
                // 연결을 일부러 끊은 경우
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

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (type != null) {
            exchange.getResponseHeaders().set("Content-Type", type);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static Handler html(String body) {
        return ex -> send(ex, 200, "text/html; charset=utf-8", body);
    }

    private static String page(String head, String body) {
        return "<html><head>" + head + "</head><body>" + body + "</body></html>";
    }

    /** 점검 한 번에서 본 것. */
    record Observation(Runner.Outcome outcome, List<String> errors, List<String> logs, List<Finding> findings, List<String> pages) {
        long count(String site) {
            return errors.stream().filter(e -> e.startsWith(site + " ")).count();
        }

        boolean has(String site, String exceptionClass) {
            return errors.stream().anyMatch(e -> e.startsWith(site + " ") && e.contains(exceptionClass));
        }

        boolean foundOn(String url) {
            return findings.stream().anyMatch(f -> f.url().equals(url));
        }

        int uninspected() {
            return meta("pages_uninspected");
        }

        int meta(String field) {
            return outcome.report() == null ? -1 : outcome.report().path("meta").path(field).asInt(-1);
        }
    }

    private Observation scan(String entry, Path out, Duration budget, int workers, TestInfo info) throws IOException {
        List<String> errors = new CopyOnWriteArrayList<>();
        List<String> logs = new CopyOnWriteArrayList<>();
        List<Finding> findings = new CopyOnWriteArrayList<>();
        List<String> pages = new CopyOnWriteArrayList<>();
        Crawler.Listener listener = new Crawler.Listener() {
            @Override
            public void onPage(String url, int visited, int queued) {
                pages.add(url);
            }

            @Override
            public void onFinding(Finding finding) {
                findings.add(finding);
            }

            @Override
            public void onLog(String message) {
                logs.add(message);
            }

            @Override
            public void onError(String site, String url, Throwable error) {
                String message = String.valueOf(error == null ? null : error.getMessage());
                int nl = message.indexOf('\n');
                errors.add(site + " " + (error == null ? "null" : error.getClass().getName()) + " " + url + " :: "
                    + (nl < 0 ? message : message.substring(0, nl)));
            }
        };
        ErrorLog log = new ErrorLog(listener, out.resolve(ErrorLog.FILE));
        long began = System.nanoTime();
        Runner.Outcome outcome = new Runner(
            ScanOptions.of(entry, out).withBudget(budget).withWorkers(workers), log).run();
        log.close();
        double seconds = (System.nanoTime() - began) / 1e9;
        Observation o = new Observation(outcome, List.copyOf(errors), List.copyOf(logs), List.copyOf(findings), List.copyOf(pages));
        record(info, o, seconds, out);
        return o;
    }

    private void record(TestInfo info, Observation o, double seconds, Path out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(info.getDisplayName()).append('\n');
        sb.append(String.format("status=%s message=%s pages=%d failed=%d seconds=%.1f%n",
            o.outcome().status(), o.outcome().message(), o.outcome().pages(),
            o.outcome().report() == null ? -1 : o.outcome().report().path("meta").path("pages_failed").asInt(-1), seconds));
        sb.append("visited: ").append(o.pages()).append('\n');
        sb.append("hits: ").append(new HashMap<>(hits)).append('\n');
        sb.append("findings:\n");
        for (Finding f : o.findings()) {
            sb.append("  ").append(f.technique()).append(' ').append(f.url()).append(' ').append(f.location()).append('\n');
        }
        sb.append("logs:\n");
        for (String l : o.logs()) {
            sb.append("  ").append(l).append('\n');
        }
        sb.append("errors:\n");
        for (String e : o.errors()) {
            sb.append("  ").append(e).append('\n');
        }
        Path jsonl = out.resolve(ErrorLog.FILE);
        String name = info.getTestMethod().map(m -> m.getName()).orElse("unknown");
        Files.createDirectories(REPRO_DIR);
        Files.writeString(REPRO_DIR.resolve(name + ".txt"), sb.toString(), StandardCharsets.UTF_8);
        if (Files.exists(jsonl)) {
            Files.copy(jsonl, REPRO_DIR.resolve(name + ".jsonl"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println(sb);
    }

    // ---- H1·H2: 페이지 전역이 바뀌어 수집 스크립트 결과가 깨지는 경우 ----

    @Test
    void prototypeStyleArrayToJsonIsIgnored(@TempDir Path out, TestInfo info) throws IOException {
        // 구버전 Prototype.js는 Array.prototype.toJSON을 정의해 JSON.stringify가 배열을 문자열로 만든다.
        String base = serve(Map.of(
            "/", html(page("", "<div class=\"wrap\">" + AD + "</div><a href=\"/proto.html\">p</a>")),
            "/proto.html", html(page("<script>Array.prototype.toJSON = function () { return '[' + this.length + ']'; };</script>",
                "<div class=\"post\">" + AD + "</div>"))));
        Observation o = scan(base + "/", out, Duration.ofSeconds(40), 1, info);
        assertTrue(o.outcome().resultWritten());
        // 직렬화하는 동안 toJSON을 치우므로 그대로 점검한다(전에는 페이지 전체를 실패로 셌다).
        assertTrue(o.errors().isEmpty(), o.errors().toString());
        assertTrue(o.foundOn(base + "/proto.html"));
    }

    @Test
    void overriddenJsonStringify(@TempDir Path out, TestInfo info) throws IOException {
        String base = serve(Map.of(
            "/", html(page("", "<div class=\"wrap\">" + AD + "</div><a href=\"/json.html\">j</a>")),
            "/json.html", html(page("<script>JSON.stringify = function () { return undefined; };</script>",
                "<div class=\"post\">" + AD + "</div>"))));
        Observation o = scan(base + "/", out, Duration.ofSeconds(40), 1, info);
        assertTrue(o.outcome().resultWritten());
        // 본문을 점검하지 못하지만 페이지 실패가 아니라 "본문 점검 실패"로 센다.
        assertTrue(o.has("main-frame-skipped", "IllegalStateException"), o.errors().toString());
        assertEquals(0, o.count("page-failed"));
        assertTrue(o.pages().contains(base + "/json.html"));
        assertEquals(1, o.uninspected());
    }

    @Test
    void overriddenArrayFrom(@TempDir Path out, TestInfo info) throws IOException {
        String base = serve(Map.of(
            "/", html(page("", "<div class=\"wrap\">" + AD + "</div><a href=\"/from.html\">f</a>")),
            "/from.html", html(page("<script>Array.from = function () { return 'x'; };</script>",
                "<div class=\"post\">" + AD + "</div><a href=\"/after.html\">a</a>")),
            "/after.html", html(page("", "<div class=\"after\">" + AD + "</div>"))));
        Observation o = scan(base + "/", out, Duration.ofSeconds(40), 1, info);
        assertTrue(o.outcome().resultWritten());
        // 링크 추출이 Array.from을 쓰지 않으므로 그 페이지와 이어지는 페이지를 모두 점검한다.
        assertTrue(o.errors().isEmpty(), o.errors().toString());
        assertTrue(o.foundOn(base + "/from.html"));
        assertTrue(o.foundOn(base + "/after.html"));
    }

    @Test
    void overriddenSetConstructor(@TempDir Path out, TestInfo info) throws IOException {
        String base = serve(Map.of(
            "/", html(page("", "<div class=\"wrap\">" + AD + "</div><a href=\"/set.html\">s</a>")),
            "/set.html", html(page("<script>window.Set = function () {};</script>",
                "<div class=\"post\">" + AD + "</div>"))));
        Observation o = scan(base + "/", out, Duration.ofSeconds(40), 1, info);
        assertTrue(o.outcome().resultWritten());
        // 본문 frame의 스크립트 오류는 "본문 점검 실패"로 세고 로그를 남긴다(전에는 조용히 넘겼다).
        assertTrue(o.has("main-frame-skipped", "PlaywrightException"), o.errors().toString());
        assertEquals(0, o.count("page-failed"));
        assertTrue(o.pages().contains(base + "/set.html"));
        assertEquals(1, o.uninspected());
        assertTrue(o.logs().stream().anyMatch(l -> l.startsWith("본문을 점검하지 못함: " + base + "/set.html")), o.logs().toString());
    }

    @Test
    void frozenAdxGlobal(@TempDir Path out, TestInfo info) throws IOException {
        // 페이지가 우연히 같은 이름의 전역을 바꿀 수 없게 만든 경우: 선택자를 만들 요소 목록을 잃는다.
        String base = serve(Map.of(
            "/", html(page("", "<div class=\"wrap\">" + AD + "</div><a href=\"/adx.html\">x</a>")),
            "/adx.html", html(page("<script>Object.defineProperty(window, '__adx', { value: { els: [] }, writable: false });</script>",
                "<div class=\"post\">" + AD + "</div>"))));
        Observation o = scan(base + "/", out, Duration.ofSeconds(40), 1, info);
        assertTrue(o.outcome().resultWritten());
        // 조용히 놓치지 않고 "본문 점검 실패"로 센다.
        assertTrue(o.has("main-frame-skipped", "PlaywrightException"), o.errors().toString());
        assertTrue(o.pages().contains(base + "/adx.html"));
        assertEquals(1, o.uninspected());
    }

    @Test
    void brokenChildFrameSkipsOnlyThatFrame(@TempDir Path out, TestInfo info) throws IOException {
        // 본문은 멀쩡하고 iframe 안 문서만 전역을 바꾼 경우.
        String base = serve(Map.of(
            "/", html(page("", "<div class=\"wrap\">" + AD + "</div><iframe src=\"/child.html\"></iframe><a href=\"/next.html\">n</a>")),
            "/child.html", html(page("<script>JSON.stringify = function () { return undefined; };</script>", "<p>child</p>")),
            "/next.html", html(page("", "<div class=\"next\">" + AD + "</div>"))));
        Observation o = scan(base + "/", out, Duration.ofSeconds(40), 1, info);
        assertTrue(o.outcome().resultWritten());
        // 깨진 iframe만 건너뛰고 페이지는 정상 방문으로 센다. 진입 페이지를 다시 열지도 않는다.
        assertEquals(0, o.count("entry-retry"));
        assertEquals(0, o.count("page-failed"));
        assertTrue(o.has("frame-skipped", "IllegalStateException"), o.errors().toString());
        assertEquals(0, o.uninspected());
        assertTrue(o.foundOn(base + "/"));
        assertTrue(o.pages().contains(base + "/"));
        assertTrue(o.foundOn(base + "/next.html"));
    }

    @Test
    void brokenPromiseDoesNotBreakSettle(@TempDir Path out, TestInfo info) throws IOException {
        // settle()은 페이지의 Promise로 대기한다.
        String base = serve(Map.of(
            "/", html(page("", "<div class=\"wrap\">" + AD + "</div><a href=\"/promise.html\">p</a>")),
            "/promise.html", html(page("<script>window.Promise = function () { throw new Error('no promise'); };</script>",
                "<div class=\"post\">" + AD + "</div>"))));
        Observation o = scan(base + "/", out, Duration.ofSeconds(40), 1, info);
        assertTrue(o.outcome().resultWritten());
        // settle()이 페이지의 Promise를 쓰지 않으므로 그대로 점검한다(전에는 페이지 전체를 실패로 셌다).
        assertTrue(o.errors().isEmpty(), o.errors().toString());
        assertTrue(o.foundOn(base + "/promise.html"));
    }

    @Test
    void brokenSetTimeoutDoesNotHangSettle(@TempDir Path out, TestInfo info) throws IOException {
        // settle()의 Promise는 페이지의 setTimeout으로 끝난다. setTimeout이 아무것도 안 하면 evaluate가 끝나지 않는다.
        String base = serve(Map.of(
            "/", html(page("", "<div class=\"wrap\">" + AD + "</div><a href=\"/timer.html\">t</a>")),
            "/timer.html", html(page("<script>window.setTimeout = function () { return 0; };</script>",
                "<div class=\"post\">" + AD + "</div>"))));
        Observation o = scan(base + "/", out, Duration.ofSeconds(30), 1, info);
        assertTrue(o.outcome().resultWritten());
        // settle()이 페이지의 setTimeout을 쓰지 않으므로 멈추지 않는다(전에는 38초 뒤 감시 스레드가 브라우저를 끊었다).
        assertFalse(o.logs().stream().anyMatch(l -> l.startsWith("멈춘 페이지를 끊습니다")), o.logs().toString());
        assertTrue(o.errors().isEmpty(), o.errors().toString());
        assertTrue(o.foundOn(base + "/timer.html"));
    }

    // ---- H6·H7: 스스로 다른 주소로 넘어가는 페이지 ----

    @Test
    void longClientRedirectChain(@TempDir Path out, TestInfo info) throws IOException {
        Map<String, Handler> routes = new HashMap<>();
        routes.put("/", html(page("", "<div class=\"wrap\">" + AD + "</div><a href=\"/r0.html\">r</a>")));
        for (int i = 0; i < 7; i++) {
            routes.put("/r" + i + ".html", html(page("<script>location.replace('/r" + (i + 1) + ".html');</script>", "<p>hop " + i + "</p>")));
        }
        routes.put("/r7.html", html(page("", "<div class=\"end\">" + AD + "</div>")));
        String base = serve(routes);
        Observation o = scan(base + "/", out, Duration.ofSeconds(60), 1, info);
        assertTrue(o.outcome().resultWritten());
        // 현재: 문서 로드 전에 넘어가는 연쇄는 navigate가 따라가므로 문제없다.
        assertTrue(o.errors().isEmpty(), o.errors().toString());
        assertTrue(o.foundOn(base + "/r7.html"));
    }

    @Test
    void delayedRedirectDuringInspection(@TempDir Path out, TestInfo info) throws IOException {
        String base = serve(Map.of(
            "/", html(page("", "<div class=\"wrap\">" + AD + "</div><a href=\"/meta.html\">m</a> <a href=\"/late.html\">l</a>")),
            "/meta.html", html(page("<meta http-equiv=\"refresh\" content=\"1;url=/landing.html\">", "<div class=\"meta\">" + AD + "</div>")),
            "/late.html", html(page("<script>setTimeout(() => { location.href = '/landing2.html'; }, 1600);</script>",
                "<div class=\"late\">" + AD + "</div>")),
            "/landing.html", html(page("", "<div class=\"landing\">" + AD + "</div>")),
            "/landing2.html", html(page("", "<div class=\"landing2\">" + AD + "</div>"))));
        Observation o = scan(base + "/", out, Duration.ofSeconds(60), 1, info);
        assertTrue(o.outcome().resultWritten());
        // 점검이 끝난 뒤에 넘어가는 주소(meta refresh·스크립트의 지연 이동)도 방문 대상에 넣는다.
        assertTrue(o.errors().isEmpty(), o.errors().toString());
        assertTrue(o.foundOn(base + "/landing.html"));
        assertTrue(o.foundOn(base + "/landing2.html"));
    }

    // ---- H10: 응답 단계의 오류 ----

    @Test
    void transportErrorsAreClassified(@TempDir Path out, TestInfo info) throws IOException {
        AtomicInteger flaky = new AtomicInteger();
        String base = serve(Map.of(
            "/", html(page("", "<div class=\"wrap\">" + AD + "</div>"
                + "<a href=\"/reset\">reset</a> <a href=\"/loop\">loop</a> <a href=\"/flaky.html\">flaky</a>"
                + " <a href=\"/notype\">notype</a> <a href=\"/attach\">attach</a> <a href=\"/err500.html\">500</a>")),
            // 응답 없이 연결을 끊는다.
            "/reset", ex -> ex.close(),
            // 자기 자신으로 끝없이 넘긴다.
            "/loop", ex -> {
                ex.getResponseHeaders().set("Location", "/loop");
                ex.sendResponseHeaders(302, -1);
            },
            // 처음 한 번만 연결을 끊는다(일시적 오류).
            "/flaky.html", ex -> {
                if (flaky.getAndIncrement() == 0) {
                    ex.close();
                    return;
                }
                send(ex, 200, "text/html; charset=utf-8", page("", "<div class=\"flaky\">" + AD + "</div>"));
            },
            "/notype", ex -> send(ex, 200, null, page("", "<div class=\"notype\">" + AD + "</div>")),
            "/attach", ex -> {
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=a.html");
                send(ex, 200, "text/html; charset=utf-8", page("", "<div class=\"attach\">" + AD + "</div>"));
            },
            "/err500.html", ex -> send(ex, 500, "text/html; charset=utf-8", "server error")));
        Observation o = scan(base + "/", out, Duration.ofSeconds(60), 1, info);
        assertTrue(o.outcome().resultWritten());
        // 연결 끊김은 한 번 더 시도한 뒤 실패, 리다이렉트 반복은 바로 실패, 500은 한 번 더 시도한 뒤 실패로 센다.
        // 첨부파일 응답은 실패가 아니라 파일로 센다.
        assertTrue(o.errors().stream().anyMatch(e -> e.startsWith("requeued") && e.contains("/reset ")), o.errors().toString());
        assertTrue(o.errors().stream().anyMatch(e -> e.startsWith("page-failed") && e.contains("/reset ")), o.errors().toString());
        assertTrue(o.errors().stream().anyMatch(e -> e.startsWith("page-failed") && e.contains("/loop ")), o.errors().toString());
        assertTrue(o.errors().stream().anyMatch(e -> e.startsWith("file-skipped") && e.contains("/attach ")), o.errors().toString());
        assertEquals(2, o.count("requeued"));
        assertEquals(3, o.meta("pages_failed"));
        assertEquals(1, o.meta("files_skipped"));
        assertTrue(o.foundOn(base + "/flaky.html"));
        assertTrue(o.pages().contains(base + "/notype"));
    }

    // ---- H11: 문서가 없거나 사라지는 frame ----

    @Test
    void unusualFrames(@TempDir Path out, TestInfo info) throws IOException {
        Map<String, Handler> routes = new ConcurrentHashMap<>();
        routes.put("/frame.html", html(page("", "<div class=\"frame\">" + AD + "</div>")));
        routes.put("/frame-slow.html", ex -> {
            try {
                Thread.sleep(2_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            send(ex, 200, "text/html; charset=utf-8", page("", "<div class=\"slowframe\">" + AD + "</div>"));
        });
        String base = serve(routes);
        String port = base.substring(base.lastIndexOf(':') + 1);
        String srcdoc = ("<div class=\"src\">" + AD + "</div>").replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
        routes.put("/", html(page("", "<div class=\"wrap\">" + AD + "</div>"
            + "<iframe src=\"about:blank\"></iframe>"
            + "<iframe srcdoc=\"" + srcdoc + "\"></iframe>"
            // 점검 도중에 사라지는 iframe
            + "<iframe id=\"gone\" src=\"/frame.html\"></iframe>"
            + "<script>setTimeout(() => { const f = document.getElementById('gone'); if (f) f.remove(); }, 1700);</script>"
            // 문서가 늦게(2.5초) 오는 iframe
            + "<iframe src=\"/frame-slow.html\"></iframe>"
            // 다른 출처(localhost)의 iframe
            + "<iframe src=\"http://localhost:" + port + "/frame.html\"></iframe>")));
        Observation o = scan(base + "/", out, Duration.ofSeconds(60), 1, info);
        assertTrue(o.outcome().resultWritten());
        // 현재: 문서 없는·늦게 뜨는·다른 출처의 iframe은 문제없이 처리한다.
        assertTrue(o.errors().isEmpty(), o.errors().toString());
        assertEquals(4, o.findings().size());
    }

    // ---- 기타: 대화상자·팝업·큰 문서 ----

    @Test
    void hugeDomAlone(@TempDir Path out, TestInfo info) throws IOException {
        StringBuilder huge = new StringBuilder("<div class=\"wrap\">" + AD + "</div>");
        for (int i = 0; i < 60_000; i++) {
            huge.append("<p>문단 ").append(i).append("</p>");
        }
        huge.append("<div class=\"tail\">").append(AD).append("</div>");
        String base = serve(Map.of(
            "/", html(page("", "<a href=\"/huge.html\">h</a>")),
            "/huge.html", html(page("", huge.toString()))));
        Observation o = scan(base + "/", out, Duration.ofSeconds(90), 1, info);
        assertTrue(o.outcome().resultWritten());
        // 글자 요소 상한(MAX_HOLDERS)에 걸려 문서 뒤쪽은 점검하지 못한다. 대신 "일부만 점검"으로 남긴다.
        assertTrue(o.errors().isEmpty(), o.errors().toString());
        assertTrue(o.findings().stream().anyMatch(f -> f.location().startsWith("div.wrap")));
        assertFalse(o.findings().stream().anyMatch(f -> f.location().startsWith("div.tail")));
        assertEquals(1, o.meta("pages_truncated"));
        assertTrue(o.logs().stream().anyMatch(l -> l.startsWith("요소가 너무 많아 일부만 점검함")), o.logs().toString());
    }

    @Test
    void pageAfterPopupStorm(@TempDir Path out, TestInfo info) throws IOException {
        String base = serve(Map.of(
            "/", html(page("", "<a href=\"/popups.html\">p</a>")),
            "/popups.html", html(page("<script>setInterval(() => window.open('/again.html'), 50);</script>",
                "<div class=\"popups\">" + AD + "</div><a href=\"/after.html\">a</a>")),
            "/after.html", html(page("", "<div class=\"after\">" + AD + "</div><a href=\"/after2.html\">a</a>")),
            "/after2.html", html(page("", "<div class=\"after2\">" + AD + "</div>")),
            "/again.html", html(page("", "<div class=\"again\">" + AD + "</div>"))));
        Observation o = scan(base + "/", out, Duration.ofSeconds(60), 1, info);
        assertTrue(o.outcome().resultWritten());
        // 팝업은 닫히고 다음 페이지에 영향이 없다. 스크립트의 window.open 주소는 방문 대상에 들어간다.
        assertTrue(o.errors().isEmpty(), o.errors().toString());
        assertTrue(o.foundOn(base + "/after2.html"));
        assertTrue(o.foundOn(base + "/again.html"));
    }

    @Test
    void pageAfterBeforeUnloadAndDialogs(@TempDir Path out, TestInfo info) throws IOException {
        String base = serve(Map.of(
            "/", html(page("", "<a href=\"/dialogs.html\">d</a>")),
            "/dialogs.html", html(page("<script>setInterval(() => alert('again'), 20);"
                + "window.onbeforeunload = (e) => { e.preventDefault(); e.returnValue = ''; };</script>",
                "<div class=\"dialogs\">" + AD + "</div><a href=\"/after.html\">a</a>")),
            "/after.html", html(page("", "<div class=\"after\">" + AD + "</div>"))));
        Observation o = scan(base + "/", out, Duration.ofSeconds(60), 1, info);
        assertTrue(o.outcome().resultWritten());
        // 대화상자 핸들러의 예외를 핸들러 안에서 끝내고 beforeunload는 받아들이므로 다음 페이지를 점검한다.
        assertEquals(0, o.count("page-failed"), o.errors().toString());
        assertTrue(o.foundOn(base + "/after.html"));
    }

    @Test
    void pageAfterAlertLoop(@TempDir Path out, TestInfo info) throws IOException {
        Observation o = scanAfter(out, info, "<script>setInterval(() => alert('again'), 20);</script>");
        assertTrue(o.outcome().resultWritten());
        // 현재: alert 반복만으로는 문제없다.
        assertTrue(o.errors().isEmpty(), o.errors().toString());
        assertEquals(6, o.findings().size());
    }

    @Test
    void pageAfterBeforeUnloadOnly(@TempDir Path out, TestInfo info) throws IOException {
        Observation o = scanAfter(out, info, "<script>window.onbeforeunload = (e) => { e.preventDefault(); e.returnValue = ''; };</script>");
        assertTrue(o.outcome().resultWritten());
        // beforeunload 확인창을 받아들이므로 그 페이지를 떠날 수 있다(전에는 dismiss로 "머묾"이 되어 그 뒤 이동이 모두 ERR_ABORTED였다).
        assertEquals(0, o.count("page-failed"), o.errors().toString());
        assertEquals(7, o.pages().size());
        assertEquals(6, o.findings().size());
    }

    @Test
    void dialogDuringNavigationDoesNotWedgeTab(@TempDir Path out, TestInfo info) throws IOException {
        // 이동하는 도중에 뜬 alert는 닫기에 실패하고(Not attached to an active page) 탭이 굳을 수 있다. 굳으면 새 탭을 연다.
        // 모든 페이지가 그렇게 해서 페이지를 옮길 때마다 경합이 생기게 한다.
        String head = "<script>setInterval(() => alert('again'), 5);"
            + "window.onbeforeunload = (e) => { e.preventDefault(); e.returnValue = ''; };</script>";
        Map<String, Handler> routes = new HashMap<>();
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            links.append("<a href=\"/d").append(i).append(".html\">d</a> ");
            routes.put("/d" + i + ".html", html(page(head, "<div class=\"d" + i + "\">" + AD + "</div>")));
        }
        routes.put("/", html(page(head, links.toString())));
        Observation o = scan(serve(routes) + "/", out, Duration.ofSeconds(120), 1, info);
        assertTrue(o.outcome().resultWritten());
        assertEquals(0, o.count("page-failed"), o.errors().toString());
        assertEquals(11, o.pages().size());
        assertEquals(10, o.findings().size());
    }

    @Test
    void bodyAttributeBeforeUnloadIsAccepted(@TempDir Path out, TestInfo info) throws IOException {
        // init 스크립트로 막지 못하는 body 속성 방식도 대화상자 핸들러가 받아들여 떠날 수 있다.
        Map<String, Handler> routes = new HashMap<>();
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            links.append("<a href=\"/b").append(i).append(".html\">b</a> ");
            routes.put("/b" + i + ".html", html("<html><body onbeforeunload=\"return 'x'\"><div class=\"b" + i + "\">" + AD + "</div></body></html>"));
        }
        routes.put("/", html("<html><body onbeforeunload=\"return 'x'\">" + links + "</body></html>"));
        Observation o = scan(serve(routes) + "/", out, Duration.ofSeconds(60), 1, info);
        assertTrue(o.outcome().resultWritten());
        assertEquals(0, o.count("page-failed"), o.errors().toString());
        assertEquals(6, o.pages().size());
    }

    @Test
    void beforeUnloadPageDoesNotBlockScan(@TempDir Path out, TestInfo info) throws IOException {
        Observation o = scanAfter(out, info, "<script>window.onbeforeunload = (e) => { e.preventDefault(); e.returnValue = ''; };</script>",
            45, Duration.ofSeconds(120));
        assertTrue(o.outcome().resultWritten());
        // 전에는 연속 실패 40회가 쌓여 "사이트가 접속을 막는다"(BLOCKED)로 끝났다.
        assertEquals(Runner.Status.COMPLETED, o.outcome().status(), o.outcome().message());
        assertEquals(0, o.count("page-failed"), o.errors().toString());
        assertEquals(47, o.pages().size());
    }

    private Observation scanAfter(Path out, TestInfo info, String head) throws IOException {
        return scanAfter(out, info, head, 5, Duration.ofSeconds(60));
    }

    private Observation scanAfter(Path out, TestInfo info, String head, int count, Duration budget) throws IOException {
        Map<String, Handler> routes = new HashMap<>();
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < count; i++) {
            links.append("<a href=\"/after").append(i).append(".html\">a</a> ");
            routes.put("/after" + i + ".html", html(page("", "<div class=\"after" + i + "\">" + AD + "</div>")));
        }
        routes.put("/", html(page("", "<a href=\"/first.html\">f</a>")));
        routes.put("/first.html", html(page(head, "<div class=\"first\">" + AD + "</div>" + links)));
        return scan(serve(routes) + "/", out, budget, 1, info);
    }

    // ---- H9: 최대 페이지 수 경쟁 ----

    @Test
    void maxPagesRace(@TempDir Path out, TestInfo info) throws IOException {
        Map<String, Handler> routes = new HashMap<>();
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            links.append("<a href=\"/p").append(i).append(".html\">").append(i).append("</a> ");
            routes.put("/p" + i + ".html", html(page("", "<p>page " + i + "</p>")));
        }
        routes.put("/", html(page("", links.toString())));
        String base = serve(routes);
        // 워커 3개, 최대 5쪽
        Path dir = out.resolve("run");
        List<String> pages = new CopyOnWriteArrayList<>();
        Runner.Outcome outcome = new Runner(ScanOptions.of(base + "/", dir).withBudget(Duration.ofSeconds(40)).withWorkers(3).withMaxPages(5),
            new Crawler.Listener() {
                @Override
                public void onPage(String url, int visited, int queued) {
                    pages.add(url);
                }
            }).run();
        Files.createDirectories(REPRO_DIR);
        Files.writeString(REPRO_DIR.resolve("maxPagesRace.txt"),
            "# maxPagesRace\nstatus=" + outcome.status() + " message=" + outcome.message() + " pages(outcome)=" + outcome.pages()
                + " onPage=" + pages.size() + "\nvisited: " + pages + "\n", StandardCharsets.UTF_8);
        System.out.println("maxPagesRace onPage=" + pages.size() + " outcome.pages=" + outcome.pages());
        assertTrue(outcome.resultWritten());
        // 방문 자리를 먼저 잡으므로 워커가 여럿이어도 최대 페이지 수를 넘기지 않는다(전에는 5쪽 상한에 7쪽).
        assertEquals(5, pages.size(), pages.toString());
        assertEquals(5, outcome.pages());
    }

    @Test
    void deferredPagesAreReported(@TempDir Path out, TestInfo info) throws IOException {
        // 늘 거부하는 페이지는 맨 뒤로 미뤄지고, 최대 페이지 수에 먼저 닿으면 다시 방문하지 못한다.
        Map<String, Handler> routes = new HashMap<>();
        StringBuilder links = new StringBuilder("<a href=\"/busy.html\">busy</a> ");
        for (int i = 0; i < 5; i++) {
            links.append("<a href=\"/p").append(i).append(".html\">").append(i).append("</a> ");
            routes.put("/p" + i + ".html", html(page("", "<p>page " + i + "</p>")));
        }
        routes.put("/", html(page("", links.toString())));
        routes.put("/busy.html", ex -> send(ex, 400, "text/html; charset=utf-8", "busy"));
        String base = serve(routes);
        List<String> errors = new CopyOnWriteArrayList<>();
        Runner.Outcome outcome = new Runner(ScanOptions.of(base + "/", out).withBudget(Duration.ofSeconds(40)).withWorkers(1).withMaxPages(3),
            new Crawler.Listener() {
                @Override
                public void onError(String site, String url, Throwable error) {
                    errors.add(site + " " + url);
                }
            }).run();
        assertTrue(outcome.resultWritten());
        assertTrue(errors.contains("requeued " + base + "/busy.html"), errors.toString());
        assertEquals(3, outcome.pages());
        assertEquals(1, outcome.report().path("meta").path("pages_deferred").asInt(-1));
        assertEquals(0, outcome.report().path("meta").path("pages_failed").asInt(-1));
    }
}
