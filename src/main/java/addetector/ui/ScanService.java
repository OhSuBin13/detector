package addetector.ui;

import addetector.Runner;
import addetector.ScanOptions;
import addetector.crawl.Crawler;
import addetector.model.Finding;
import addetector.output.ReportWriter;
import addetector.output.ReviewStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** 결과 화면에서 시작하는 점검. 한 번에 하나만 돌린다. 엔진은 명령줄과 같은 {@link Runner}다. */
public final class ScanService {
    private static final int LOG_LINES = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScanOptions defaults;
    private final ReviewStore review;
    private final Deque<String> log = new ArrayDeque<>();
    private Runner runner;
    private ScanOptions current;
    private Runner.Outcome lastOutcome;
    private String lastUrl = "";
    private String currentPage = "";

    public ScanService(ScanOptions defaults) {
        this.defaults = defaults;
        this.review = ReviewStore.open(defaults.outDir());
    }

    public ScanOptions defaults() {
        return defaults;
    }

    public ReviewStore review() {
        return review;
    }

    public synchronized boolean running() {
        return runner != null;
    }

    /**
     * 점검을 시작한다.
     *
     * @param budgetMinutes null이면 기본값
     * @return 시작했으면 true, 이미 돌고 있으면 false
     */
    public synchronized boolean start(String url, Double budgetMinutes, Integer workers, Integer maxPages) {
        if (runner != null) {
            return false;
        }
        ScanOptions options = new ScanOptions(
            url == null ? "" : url.strip(), defaults.outDir(), defaults.budget(), defaults.workers(), defaults.maxPages(),
            defaults.settleMillis(), defaults.headless(), defaults.extras(), defaults.topic());
        if (budgetMinutes != null && budgetMinutes > 0) {
            options = options.withBudget(Duration.ofMillis(Math.round(Math.min(budgetMinutes, 29) * 60_000)));
        }
        if (workers != null && workers > 0) {
            options = options.withWorkers(workers);
        }
        if (maxPages != null && maxPages >= 0) {
            options = options.withMaxPages(maxPages);
        }
        log.clear();
        lastOutcome = null;
        lastUrl = options.entryUrl();
        currentPage = "";
        current = options;
        Runner r = new Runner(options, new Crawler.Listener() {
            @Override
            public void onPage(String pageUrl, int visited, int queued) {
                synchronized (ScanService.this) {
                    currentPage = pageUrl;
                }
            }

            @Override
            public void onFinding(Finding finding) {
                // 진행 중 목록은 collector에서 바로 읽는다.
            }

            @Override
            public void onLog(String message) {
                addLog(message);
            }
        }, review).withFallbackDirs(addetector.App.fallbackDirs());
        runner = r;
        Thread t = new Thread(() -> {
            Runner.Outcome outcome;
            try {
                outcome = r.run();
            } catch (RuntimeException | Error e) {
                outcome = null;
                addLog("점검이 비정상 종료했습니다: " + e);
            }
            synchronized (ScanService.this) {
                lastOutcome = outcome;
                runner = null;
            }
        }, "ui-scan");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public void stop() {
        Runner r;
        synchronized (this) {
            r = runner;
        }
        if (r != null) {
            r.stop();
        }
    }

    private synchronized void addLog(String message) {
        if (log.size() >= LOG_LINES) {
            log.removeFirst();
        }
        log.addLast(message);
    }

    /** 진행 상태(가벼운 값만). */
    public synchronized ObjectNode state() {
        ObjectNode o = MAPPER.createObjectNode();
        boolean running = runner != null;
        o.put("running", running);
        o.put("entry_url", lastUrl);
        o.put("current_page", currentPage);
        if (running) {
            o.put("status", "점검 중");
            o.put("message", "");
            o.put("visited", runner.visited());
            o.put("queued", runner.queued());
            o.put("failed", runner.failed());
            o.put("findings", runner.collector().size());
            o.put("elapsed_sec", Math.round(runner.elapsedSeconds()));
            o.put("budget_sec", current.budget().toSeconds());
        } else if (lastOutcome != null) {
            o.put("status", lastOutcome.status().label());
            o.put("message", lastOutcome.message());
            o.put("visited", lastOutcome.pages());
            o.put("findings", lastOutcome.findings().size() + lastOutcome.extras().size());
            o.put("elapsed_sec", Math.round(lastOutcome.elapsedSec()));
        } else {
            o.put("status", "대기");
            o.put("message", "");
        }
        o.put("default_budget_min", defaults.budget().toSeconds() / 60.0);
        o.put("default_workers", defaults.workers());
        o.put("out_dir", (lastOutcome != null ? lastOutcome.outDir() : defaults.outDir()).toString());
        o.set("log", MAPPER.valueToTree(List.copyOf(log)));
        return o;
    }

    /**
     * 결과 화면용 데이터: 점검 중이면 지금까지 찾은 것, 끝났으면 최종 결과,
     * 이번에 점검한 적이 없으면 결과 폴더에 남아 있는 지난 report.json.
     *
     * @return 보여 줄 것이 없으면 null
     */
    public ObjectNode report() throws IOException {
        Runner r;
        Runner.Outcome outcome;
        synchronized (this) {
            r = runner;
            outcome = lastOutcome;
        }
        ObjectNode node;
        if (r != null) {
            node = ReportWriter.toNode(r.runInfo(null, "", true), r.collector().core(), r.collector().extra(), review.all());
        } else if (outcome != null && outcome.report() != null) {
            node = outcome.report().deepCopy();
        } else {
            Path previous = defaults.outDir().resolve(ReportWriter.REPORT_JSON);
            if (!Files.isRegularFile(previous)) {
                return null;
            }
            node = (ObjectNode) MAPPER.readTree(Files.readAllBytes(previous));
        }
        // 처리 상태는 항상 지금 저장된 것으로 보여 준다.
        ObjectNode latest = node.putObject("review");
        review.all().forEach((key, entry) -> latest.set(key, MAPPER.valueToTree(entry)));
        return node;
    }

    public ReviewStore.Entry setReview(String key, String status, String memo) throws IOException {
        return review.set(key, status, memo, addetector.output.ResultWriter.timestamp(OffsetDateTime.now()));
    }
}
