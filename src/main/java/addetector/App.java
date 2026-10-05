package addetector;

import addetector.crawl.Crawler;
import addetector.model.Finding;
import addetector.output.ReportWriter;
import addetector.output.ResultWriter;
import addetector.ui.UiServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** 명령줄 진입점. */
@Command(
    name = "ad-detector",
    mixinStandardHelpOptions = true,
    versionProvider = App.VersionProvider.class,
    description = "공공 웹사이트에 게시·은닉된 불법광고를 찾아 result.json으로 기록합니다.",
    footer = {
        "",
        "예:",
        "  ad-detector https://www.example.go.kr",
        "  ad-detector https://www.example.go.kr --out D:\\check --budget-min 20",
        "  ad-detector --ui",
    })
public final class App implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "URL", description = "점검할 웹사이트의 진입 URL")
    private String url;

    @Option(names = "--out", paramLabel = "DIR", description = "결과 파일을 쓸 폴더 (기본: 실행 파일이 있는 폴더)")
    private Path out;

    @Option(names = "--budget-min", paramLabel = "분", description = "시간 예산(분). 이 시간 안에 결과를 기록하고 끝냅니다 (기본: 25)")
    private double budgetMinutes = TimeBudget.DEFAULT.toMinutes();

    @Option(names = "--workers", paramLabel = "N", description = "동시에 쓰는 브라우저 수 (기본: PC 사양에 따라 1~3)")
    private Integer workers;

    @Option(names = "--max-pages", paramLabel = "N", description = "방문할 최대 페이지 수 (기본: 제한 없음)")
    private int maxPages;

    @Option(names = "--settle-ms", paramLabel = "ms", description = "로드 뒤 늦게 붙는 내용(댓글 등)을 기다리는 최대 시간 (기본: 1500)")
    private int settleMillis = ScanOptions.DEFAULT_SETTLE_MILLIS;

    @Option(names = "--no-extra", description = "추가 유형(ETC) 탐지와 result_extra.json 생성을 끕니다")
    private boolean noExtra;

    @Option(names = "--topic", paramLabel = "TEXT", description = "result.json의 meta.topic 값 (기본: TOPIC)")
    private String topic = ScanOptions.DEFAULT_TOPIC;

    @Option(names = "--headed", description = "브라우저 창을 띄워서 점검합니다 (확인용)")
    private boolean headed;

    @Option(names = "--ui", description = "결과 화면(이 PC에서만 접속되는 로컬 웹 화면)을 엽니다")
    private boolean ui;

    @Option(names = "--port", paramLabel = "N", description = "결과 화면 포트 (기본: 8787, 사용 중이면 빈 포트)")
    private int port = UiServer.DEFAULT_PORT;

    @Option(names = "--no-open", description = "결과 화면을 브라우저로 자동으로 열지 않습니다")
    private boolean noOpen;

    @Option(names = "--quiet", description = "진행 상황을 출력하지 않습니다")
    private boolean quiet;

    public static void main(String[] args) {
        int code = new CommandLine(new App()).execute(args);
        System.exit(code);
    }

    /** 결과 파일의 기본 위치: 구동 파일이 있는 폴더(공모전 원칙). 실행 스크립트가 addetector.home으로 알려 준다. */
    static Path defaultOutDir() {
        String home = System.getProperty("addetector.home");
        return home != null && !home.isBlank() ? Path.of(home) : Path.of("").toAbsolutePath();
    }

    /** 결과 폴더에 쓸 수 없을 때의 예비 폴더: 현재 폴더, 사용자 폴더의 ad-detector. */
    public static List<Path> fallbackDirs() {
        return List.of(Path.of("").toAbsolutePath(), Path.of(System.getProperty("user.home", "."), "ad-detector").toAbsolutePath());
    }

    ScanOptions options(String entryUrl) {
        ScanOptions o = ScanOptions.of(entryUrl, out != null ? out.toAbsolutePath() : defaultOutDir())
            .withBudget(Duration.ofMillis(Math.max(1_000, Math.round(budgetMinutes * 60_000))))
            .withMaxPages(maxPages)
            .withSettleMillis(settleMillis)
            .withExtras(!noExtra)
            .withHeadless(!headed)
            .withTopic(topic);
        return workers == null ? o : o.withWorkers(workers);
    }

    @Override
    public Integer call() throws Exception {
        if (ui) {
            UiServer server = UiServer.start(options(url == null ? "" : url), port);
            System.out.println("결과 화면: " + server.address());
            System.out.println("이 PC에서만 접속됩니다. 끝내려면 이 창에서 Ctrl+C를 누르세요.");
            if (url != null && !url.isBlank()) {
                server.service().start(url, null, null, null);
            }
            if (!noOpen) {
                server.openInBrowser();
            }
            server.awaitShutdown();
            return 0;
        }
        if (url == null || url.isBlank()) {
            url = prompt();
        }
        ScanOptions options = options(url == null ? "" : url);
        Runner runner = new Runner(options, quiet ? new Crawler.Listener() {} : new ConsoleProgress()).withFallbackDirs(fallbackDirs());
        if (!quiet) {
            System.out.println("탐지 시작: " + options.entryUrl());
            System.out.println("  시간 예산 " + options.budget().toSeconds() / 60.0 + "분, 워커 " + options.workers() + "개, 결과 폴더 " + options.outDir());
        }
        Runner.Outcome outcome = runner.run();
        printSummary(options, outcome);
        return outcome.resultWritten() ? outcome.status().exitCode() : 1;
    }

    private static String prompt() throws IOException {
        if (System.console() == null && System.in.available() == 0) {
            return null;
        }
        System.out.print("점검할 웹사이트 주소(URL)를 입력하세요: ");
        System.out.flush();
        Charset charset = System.console() != null ? System.console().charset() : Charset.defaultCharset();
        String line = new BufferedReader(new InputStreamReader(System.in, charset)).readLine();
        return line == null ? null : line.strip();
    }

    private void printSummary(ScanOptions options, Runner.Outcome outcome) {
        Map<addetector.model.Technique, Integer> counts = new EnumMap<>(addetector.model.Technique.class);
        for (Finding f : outcome.findings()) {
            counts.merge(f.technique(), 1, Integer::sum);
        }
        System.out.println();
        System.out.println("탐지 완료: " + outcome.status().label() + (outcome.message().isEmpty() ? "" : " - " + outcome.message()));
        System.out.printf("  %d개 페이지, %.1f초, 탐지 %d건 %s%n", outcome.pages(), outcome.elapsedSec(), outcome.findings().size(), counts);
        if (options.extras()) {
            System.out.println("  추가 유형(ETC) " + outcome.extras().size() + "건");
        }
        Path dir = outcome.outDir();
        if (outcome.resultWritten()) {
            System.out.println("  채점용 결과: " + dir.resolve(ResultWriter.RESULT_FILE));
            if (options.extras()) {
                System.out.println("  추가 탐지 결과: " + dir.resolve(ResultWriter.EXTRA_FILE));
            }
        } else {
            System.out.println("  [오류] result.json을 쓰지 못했습니다.");
        }
        if (outcome.report() != null) {
            System.out.println("  결과 화면(더블클릭): " + dir.resolve(ReportWriter.REPORT_HTML));
        }
    }

    /** 진행 상황을 한 줄씩 찍는다. */
    private static final class ConsoleProgress implements Crawler.Listener {
        private final long start = System.nanoTime();
        private int findings;

        private String clock() {
            long s = (System.nanoTime() - start) / 1_000_000_000L;
            return String.format("[%02d:%02d]", s / 60, s % 60);
        }

        @Override
        public synchronized void onPage(String url, int visited, int queued) {
            System.out.printf("%s %d쪽 방문, 대기 %d, 탐지 %d건 - %s%n", clock(), visited, queued, findings, url);
        }

        @Override
        public synchronized void onFinding(Finding finding) {
            findings++;
            System.out.printf("%s   + %s %s%n", clock(), finding.technique(), finding.location());
        }

        @Override
        public synchronized void onLog(String message) {
            System.out.printf("%s   %s%n", clock(), message);
        }
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"ad-detector " + Version.get()};
        }
    }
}
