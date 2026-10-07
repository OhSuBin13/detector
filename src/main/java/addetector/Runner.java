package addetector;

import addetector.crawl.Crawler;
import addetector.crawl.UrlNormalizer;
import addetector.model.Finding;
import addetector.output.FindingCollector;
import addetector.output.ReportWriter;
import addetector.output.ResultWriter;
import addetector.output.ReviewStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 점검 한 번을 처음부터 끝까지 맡는다. 명령줄에서 시작하든 결과 화면에서 시작하든 이 클래스를 거친다.
 * 정상 종료·예산 초과·예외·잘못된 입력·Ctrl+C 어느 경로로 끝나도 result.json을 정확히 한 번 쓴다.
 */
public final class Runner {
    public enum Status {
        COMPLETED("정상 완료", 0),
        BUDGET("시간 예산 도달", 0),
        STOPPED("사용자 중단", 0),
        BLOCKED("접속 차단 의심으로 중단", 0),
        ERROR("오류", 1),
        INVALID_INPUT("잘못된 입력", 2);

        private final String label;
        private final int exitCode;

        Status(String label, int exitCode) {
            this.label = label;
            this.exitCode = exitCode;
        }

        public String label() {
            return label;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    /**
     * @param resultWritten result.json을 썼는가
     * @param outDir 결과 파일을 실제로 쓴 폴더(지정한 폴더에 쓸 수 없으면 예비 폴더)
     * @param report 결과 화면용 데이터(부가 파일을 만들지 못했으면 null)
     */
    public record Outcome(
        Status status, String message, List<Finding> findings, List<Finding> extras, int pages, double elapsedSec,
        boolean resultWritten, Path outDir, ObjectNode report) {}

    private final ScanOptions options;
    private final Crawler.Listener listener;
    private final FindingCollector collector = new FindingCollector();
    private final AtomicBoolean written = new AtomicBoolean();
    private final OffsetDateTime startedAt = OffsetDateTime.now();
    private final TimeBudget budget;
    private final ReviewStore sharedReview;
    private final long startNanos = System.nanoTime();
    private volatile Crawler crawler;
    private volatile boolean stopRequested;
    private volatile Outcome outcome;
    private volatile List<Path> fallbackDirs = List.of();

    public Runner(ScanOptions options, Crawler.Listener listener) {
        this(options, listener, null);
    }

    /** @param sharedReview 결과 화면이 쓰고 있는 처리 상태 저장소. null이면 결과 폴더에서 읽는다. */
    public Runner(ScanOptions options, Crawler.Listener listener, ReviewStore sharedReview) {
        this.options = options;
        this.listener = listener;
        this.sharedReview = sharedReview;
        this.budget = new TimeBudget(options.budget());
    }

    /** 결과 폴더에 쓸 수 없을 때 차례로 시도할 예비 폴더. */
    public Runner withFallbackDirs(List<Path> dirs) {
        this.fallbackDirs = List.copyOf(dirs);
        return this;
    }

    public Outcome run() {
        // Ctrl+C로 끝나도 그때까지 찾은 것을 남긴다.
        Thread hook = new Thread(this::onShutdown, "result-on-shutdown");
        boolean hooked = addHook(hook);
        ExecutorService executor = null;
        Status status;
        String message = "";
        try {
            String entry = UrlNormalizer.entry(options.entryUrl());
            if (entry == null) {
                status = Status.INVALID_INPUT;
                message = "점검할 주소가 올바르지 않습니다: " + options.entryUrl();
            } else {
                Crawler c = new Crawler(options, entry, budget, collector, listener);
                crawler = c;
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "crawl-main");
                    t.setDaemon(true);
                    return t;
                });
                Future<?> crawl = executor.submit(() -> {
                    c.run();
                    return null;
                });
                try {
                    crawl.get(budget.millisToHard(), TimeUnit.MILLISECONDS);
                    if (stopRequested) {
                        status = Status.STOPPED;
                    } else if (c.launchError() != null) {
                        status = Status.ERROR;
                        message = c.launchError();
                    } else if (c.blocked() != null) {
                        status = Status.BLOCKED;
                        message = c.blocked();
                    } else if (c.visited() == 0 && c.entryError() != null) {
                        status = Status.ERROR;
                        message = "진입 주소에 접속하지 못했습니다: " + c.entryError();
                    } else if (options.maxPages() > 0 && c.visited() >= options.maxPages()) {
                        status = Status.COMPLETED;
                        message = "지정한 최대 페이지 수(" + options.maxPages() + ")까지만 점검했습니다.";
                    } else if (c.queued() > 0) {
                        status = Status.BUDGET;
                        message = "방문하지 못한 페이지가 " + c.queued() + "개 남았습니다.";
                    } else {
                        status = Status.COMPLETED;
                    }
                } catch (TimeoutException e) {
                    status = Status.BUDGET;
                    message = "시간 예산이 끝나 하던 작업을 끊고 결과를 기록했습니다.";
                } catch (ExecutionException e) {
                    listener.onError("runner", options.entryUrl(), e.getCause());
                    status = Status.ERROR;
                    message = String.valueOf(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    status = Status.STOPPED;
                }
            }
        } catch (RuntimeException | Error e) {
            listener.onError("runner", options.entryUrl(), e);
            status = Status.ERROR;
            message = String.valueOf(e);
        }
        // 결과를 먼저 쓰고, 브라우저는 그다음에 닫는다(닫다가 멈춰도 결과는 남는다).
        Outcome result = finish(status, message);
        Crawler c = crawler;
        if (c != null) {
            c.shutdown();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (hooked) {
            removeHook(hook);
        }
        return result;
    }

    /** JVM이 끝나는 중(Ctrl+C, 창 닫기)에 불린다: 그때까지 찾은 것을 기록하고 브라우저를 정리한다. */
    void onShutdown() {
        finish(Status.STOPPED, "실행이 중단되어 그때까지의 결과를 기록했습니다.");
        Crawler c = crawler;
        if (c != null) {
            c.shutdown();
        }
    }

    /** 점검을 멈추고 그때까지의 결과를 쓰게 한다(결과 화면의 "중지"). */
    public void stop() {
        stopRequested = true;
        Crawler c = crawler;
        if (c != null) {
            c.shutdown();
        }
    }

    public FindingCollector collector() {
        return collector;
    }

    public OffsetDateTime startedAt() {
        return startedAt;
    }

    public double elapsedSeconds() {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    public int visited() {
        Crawler c = crawler;
        return c == null ? 0 : c.visited();
    }

    public int queued() {
        Crawler c = crawler;
        return c == null ? 0 : c.queued();
    }

    public int failed() {
        Crawler c = crawler;
        return c == null ? 0 : c.failed();
    }

    public ReportWriter.Gaps gaps() {
        Crawler c = crawler;
        return c == null ? ReportWriter.Gaps.NONE : new ReportWriter.Gaps(c.uninspected(), c.truncated(), c.deferred(), c.files());
    }

    public String browserName() {
        Crawler c = crawler;
        return c == null ? "" : c.browserName();
    }

    /** 지금 상태로 결과 화면용 데이터를 만든다(진행 중 표시용). */
    public ReportWriter.RunInfo runInfo(Status status, String message, boolean running) {
        ResultWriter.Meta meta = new ResultWriter.Meta(
            options.topic(), options.entryUrl(), startedAt, OffsetDateTime.now(), elapsedSeconds(), Version.get());
        return new ReportWriter.RunInfo(
            meta, status == null ? "점검 중" : status.label(), message, visited(), failed(), gaps(), queued(), browserName(), options.workers(), running);
    }

    /** 결과를 쓴다. 여러 경로에서 불려도 처음 한 번만 실제로 쓴다. */
    private Outcome finish(Status status, String message) {
        if (!written.compareAndSet(false, true)) {
            Outcome done = outcome;
            // 다른 스레드가 쓰는 중이면 끝날 때까지 잠깐 기다린다.
            for (int i = 0; done == null && i < 100; i++) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                done = outcome;
            }
            return done != null ? done
                : new Outcome(status, message, List.of(), List.of(), visited(), elapsedSeconds(), false, options.outDir(), null);
        }
        List<Finding> core = collector.core();
        List<Finding> extras = collector.extra();
        ReportWriter.RunInfo info = runInfo(status, message, false);
        // 지정한 폴더에 쓸 수 없으면(읽기 전용 등) 예비 폴더에라도 남긴다.
        List<Path> candidates = new ArrayList<>();
        candidates.add(options.outDir());
        for (Path fallback : fallbackDirs) {
            if (!candidates.contains(fallback)) {
                candidates.add(fallback);
            }
        }
        Path dir = options.outDir();
        boolean ok = false;
        String note = message;
        for (Path candidate : candidates) {
            try {
                ResultWriter.write(candidate.resolve(ResultWriter.RESULT_FILE), info.meta(), core);
                dir = candidate;
                ok = true;
                break;
            } catch (IOException | RuntimeException e) {
                listener.onLog("result.json을 쓰지 못했습니다(" + candidate + "): " + e);
            }
        }
        if (!ok) {
            note = (message.isEmpty() ? "" : message + " / ") + "result.json을 쓰지 못했습니다.";
        } else if (!dir.equals(options.outDir())) {
            note = (message.isEmpty() ? "" : message + " / ") + "지정한 폴더에 쓸 수 없어 " + dir + " 에 기록했습니다.";
        }
        if (options.extras()) {
            try {
                ResultWriter.write(dir.resolve(ResultWriter.EXTRA_FILE), info.meta(), extras);
            } catch (IOException | RuntimeException e) {
                listener.onLog("result_extra.json을 쓰지 못했습니다: " + e);
            }
        }
        // 부가 파일은 실패해도 채점 파일과 종료 코드에 영향을 주지 않는다.
        ObjectNode report = null;
        try {
            ReviewStore review = sharedReview != null ? sharedReview : ReviewStore.open(dir);
            List<Finding> all = new ArrayList<>(core);
            all.addAll(extras);
            review.onScan(all, ResultWriter.timestamp(startedAt));
            report = ReportWriter.toNode(info, core, extras, review.all());
            ReportWriter.write(dir, report);
        } catch (IOException | RuntimeException | LinkageError e) {
            listener.onLog("결과 화면 파일(report.*)을 쓰지 못했습니다: " + e);
        }
        Outcome result = new Outcome(status, note, core, extras, info.visited(), info.meta().elapsedSec(), ok, dir, report);
        outcome = result;
        return result;
    }

    private static boolean addHook(Thread hook) {
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static void removeHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // 이미 종료 중이다.
        }
    }
}
