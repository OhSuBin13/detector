package addetector.ui;

import addetector.ScanOptions;
import addetector.Version;
import addetector.output.ReportWriter;
import addetector.output.ReviewStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * 결과 화면 서버. JDK 내장 HTTP 서버만 쓰고(새 의존성 없음), 루프백(127.0.0.1)에만 연다.
 * 다른 PC에서는 접속할 수 없고, 다른 웹사이트가 이 PC의 브라우저를 통해 조작하지 못하게
 * Host 헤더와 화면에 심은 토큰을 확인한다.
 */
public final class UiServer {
    public static final int DEFAULT_PORT = 8787;
    private static final String TOKEN_PLACEHOLDER = "__UI_TOKEN__";
    private static final String TOKEN_HEADER = "X-Ad-Detector-Token";
    private static final int MAX_BODY = 64 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final ScanService service;
    private final String token;
    private final CountDownLatch stopped = new CountDownLatch(1);

    private UiServer(HttpServer server, ScanService service) {
        this.server = server;
        this.service = service;
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        this.token = HexFormat.of().formatHex(random);
    }

    /** @param port 원하는 포트. 쓰는 중이면 빈 포트를 고른다. */
    public static UiServer start(ScanOptions defaults, int port) throws IOException {
        HttpServer http = LoopbackHttp.create(port);
        UiServer ui = new UiServer(http, new ScanService(defaults));
        http.createContext("/", ui::handle);
        http.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ui-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        return ui;
    }

    public ScanService service() {
        return service;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String address() {
        return "http://127.0.0.1:" + port() + "/";
    }

    public void stop() {
        service.stop();
        server.stop(0);
        stopped.countDown();
    }

    public void awaitShutdown() throws InterruptedException {
        stopped.await();
    }

    /** 기본 브라우저로 화면을 연다. 실패해도 주소는 콘솔에 찍혀 있다. */
    public void openInBrowser() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", address()).start();
            } else {
                new ProcessBuilder(os.contains("mac") ? "open" : "xdg-open", address()).start();
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("브라우저를 자동으로 열지 못했습니다. 주소를 직접 여세요: " + address());
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!localHost(exchange)) {
                send(exchange, 403, "text/plain; charset=utf-8", "이 PC에서만 접속할 수 있습니다.");
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method)) {
                get(exchange, path);
            } else if ("POST".equals(method)) {
                if (!token.equals(exchange.getRequestHeaders().getFirst(TOKEN_HEADER))) {
                    send(exchange, 403, "text/plain; charset=utf-8", "화면을 새로 고친 뒤 다시 시도하세요.");
                    return;
                }
                post(exchange, path);
            } else {
                send(exchange, 405, "text/plain; charset=utf-8", "지원하지 않는 요청입니다.");
            }
        } catch (IllegalArgumentException e) {
            json(exchange, 400, error(e.getMessage()));
        } catch (IOException | RuntimeException e) {
            json(exchange, 500, error(String.valueOf(e)));
        } finally {
            exchange.close();
        }
    }

    /** Host 헤더가 이 서버의 루프백 주소인지 확인한다(DNS 리바인딩 방지). */
    private boolean localHost(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        return ("127.0.0.1:" + port()).equals(host) || ("localhost:" + port()).equals(host);
    }

    private void get(HttpExchange exchange, String path) throws IOException {
        switch (path) {
            case "/", "/index.html" -> send(exchange, 200, "text/html; charset=utf-8",
                ReportWriter.html(null).replace(TOKEN_PLACEHOLDER, token));
            case "/api/state" -> {
                ObjectNode state = service.state();
                state.put("tool_version", Version.get());
                json(exchange, 200, state);
            }
            case "/api/report" -> {
                ObjectNode report = service.report();
                json(exchange, 200, report == null ? MAPPER.nullNode() : report);
            }
            default -> send(exchange, 404, "text/plain; charset=utf-8", "없는 주소입니다.");
        }
    }

    private void post(HttpExchange exchange, String path) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            throw new IllegalArgumentException("요청이 너무 큽니다.");
        }
        JsonNode in = body.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        ObjectNode out = MAPPER.createObjectNode();
        switch (path) {
            case "/api/scan" -> {
                String url = in.path("url").asText("").strip();
                if (url.isEmpty()) {
                    throw new IllegalArgumentException("점검할 주소를 입력하세요.");
                }
                boolean started = service.start(
                    url,
                    in.hasNonNull("budget_min") ? in.get("budget_min").asDouble() : null,
                    in.hasNonNull("workers") ? in.get("workers").asInt() : null,
                    in.hasNonNull("max_pages") ? in.get("max_pages").asInt() : null);
                if (!started) {
                    json(exchange, 409, error("이미 점검 중입니다."));
                    return;
                }
                out.put("ok", true);
            }
            case "/api/stop" -> {
                service.stop();
                out.put("ok", true);
            }
            case "/api/review" -> {
                String key = in.path("key").asText("");
                if (!key.matches("[0-9a-f]{16}")) {
                    throw new IllegalArgumentException("잘못된 건 식별자입니다.");
                }
                String memo = in.path("memo").asText("");
                if (memo.length() > 2000) {
                    memo = memo.substring(0, 2000);
                }
                ReviewStore.Entry entry = service.setReview(key, in.path("status").asText(ReviewStore.NEW), memo);
                out.set("entry", MAPPER.valueToTree(entry));
            }
            default -> {
                send(exchange, 404, "text/plain; charset=utf-8", "없는 주소입니다.");
                return;
            }
        }
        json(exchange, 200, out);
    }

    private static ObjectNode error(String message) {
        return MAPPER.createObjectNode().put("error", message == null ? "오류" : message);
    }

    private static void json(HttpExchange exchange, int status, JsonNode node) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", MAPPER.writeValueAsString(node));
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
