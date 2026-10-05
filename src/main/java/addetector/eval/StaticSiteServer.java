package addetector.eval;

import addetector.ui.LoopbackHttp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** 모의 사이트를 로컬에서 내주는 정적 파일 서버. 어떤 주소가 요청됐는지 기록해 둔다(첨부파일·파괴적 링크 미요청 확인용). */
public final class StaticSiteServer implements AutoCloseable {
    private static final Map<String, String> TYPES = Map.of(
        "html", "text/html; charset=utf-8",
        "css", "text/css; charset=utf-8",
        "js", "text/javascript; charset=utf-8",
        "json", "application/json; charset=utf-8",
        "pdf", "application/pdf",
        "hwp", "application/x-hwp",
        "png", "image/png",
        "svg", "image/svg+xml",
        "txt", "text/plain; charset=utf-8");

    private final HttpServer server;
    private final Path root;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private StaticSiteServer(HttpServer server, Path root) {
        this.server = server;
        this.root = root.toAbsolutePath().normalize();
    }

    /** @param port 원하는 포트. 쓰는 중이면 빈 포트를 고른다. */
    public static StaticSiteServer start(Path root, int port) throws IOException {
        HttpServer http = LoopbackHttp.create(port);
        StaticSiteServer site = new StaticSiteServer(http, root);
        http.createContext("/", site::handle);
        http.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "mock-site");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        return site;
    }

    /** 모의 사이트만 띄워 둔다(손으로 시험할 때): {@code StaticSiteServer mock-site 8765} */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "mock-site");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8765;
        StaticSiteServer site = start(root, port);
        System.out.println("모의 사이트: " + site.baseUrl() + "/  (끝내려면 Ctrl+C)");
        Thread.currentThread().join();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 지금까지 요청된 경로(쿼리 포함). */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    public void clearRequests() {
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(query == null ? path : path + "?" + query);
            if (path.endsWith("/")) {
                path += "index.html";
            }
            Path file = root.resolve(path.substring(1)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                send(exchange, 404, "text/plain; charset=utf-8", "404 Not Found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String name = file.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
            send(exchange, 200, TYPES.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(file));
        }
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
