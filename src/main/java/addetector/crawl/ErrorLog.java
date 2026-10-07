package addetector.crawl;

import addetector.model.Finding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * 점검 중 난 예외를 전부(스택 포함) 한 줄에 하나씩 JSON으로 남긴다(--debug-errors). 나머지 알림은 그대로 넘긴다.
 * 진단용이다. 기록하다 실패해도 점검은 계속한다.
 */
public final class ErrorLog implements Crawler.Listener {
    public static final String FILE = "crawl-errors.jsonl";
    private static final int MAX_FRAMES = 40;
    private static final int MAX_CAUSES = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Crawler.Listener delegate;
    private final Path file;
    private BufferedWriter writer;
    private boolean broken;

    public ErrorLog(Crawler.Listener delegate, Path file) {
        this.delegate = delegate;
        this.file = file;
    }

    public Path file() {
        return file;
    }

    @Override
    public void onPage(String url, int visited, int queued) {
        delegate.onPage(url, visited, queued);
    }

    @Override
    public void onFinding(Finding finding) {
        delegate.onFinding(finding);
    }

    @Override
    public void onLog(String message) {
        delegate.onLog(message);
    }

    @Override
    public void onError(String site, String url, Throwable error) {
        delegate.onError(site, url, error);
        ObjectNode line = MAPPER.createObjectNode();
        line.put("time", Instant.now().toString());
        line.put("thread", Thread.currentThread().getName());
        line.put("site", site);
        line.put("url", url == null ? "" : url);
        describe(line, error);
        ArrayNode causes = line.putArray("causes");
        Throwable c = error == null ? null : error.getCause();
        for (int i = 0; c != null && c != error && i < MAX_CAUSES; i++, c = c.getCause()) {
            describe(causes.addObject(), c);
        }
        write(line.toString());
    }

    private static void describe(ObjectNode node, Throwable e) {
        if (e == null) {
            node.putNull("class");
            return;
        }
        node.put("class", e.getClass().getName());
        node.put("message", String.valueOf(e.getMessage()));
        ArrayNode stack = node.putArray("stack");
        StackTraceElement[] frames = e.getStackTrace();
        for (int i = 0; i < frames.length && i < MAX_FRAMES; i++) {
            stack.add(frames[i].toString());
        }
    }

    private synchronized void write(String json) {
        if (broken) {
            return;
        }
        try {
            if (writer == null) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            writer.write(json);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            broken = true;
            delegate.onLog(FILE + "를 쓰지 못했습니다: " + e);
        }
    }

    /** 파일을 닫는다. 여러 번 불러도 된다. */
    public synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                // 진단 기록이므로 무시한다.
            }
            writer = null;
        }
    }
}
