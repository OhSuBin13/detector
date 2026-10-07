package addetector.crawl;

import addetector.ScanOptions;
import addetector.TimeBudget;
import addetector.crawl.BrowserLauncher.Session;
import addetector.detect.AdSignals;
import addetector.detect.Detector;
import addetector.detect.Detector.Candidate;
import addetector.detect.ExtraDetectors;
import addetector.detect.FrameContext;
import addetector.detect.FrameSnapshot;
import addetector.detect.HomoglyphDetector;
import addetector.detect.JamoDetector;
import addetector.detect.KeywordDictionary;
import addetector.detect.OffscreenDetector;
import addetector.detect.SelectorService;
import addetector.detect.TransparentDetector;
import addetector.model.Finding;
import addetector.output.FindingCollector;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 진입 URL에서 시작해 같은 호스트의 페이지를 돌며 frame마다 탐지기를 돌린다.
 * 워커마다 스레드 하나 = Playwright 하나 = 브라우저 하나 = 탐지기 한 벌이다(Playwright 객체는 스레드 안전하지 않다).
 * 감시 스레드가 페이지 하나에 너무 오래 머문 워커의 브라우저를 끊어 회수한다(Playwright evaluate에는 시간 제한이 없다).
 */
public final class Crawler {
    /** 진행 상황 알림. 워커 스레드에서 불린다. */
    public interface Listener {
        default void onPage(String url, int visited, int queued) {}

        default void onFinding(Finding finding) {}

        default void onLog(String message) {}

        /**
         * 예외가 났을 때(그냥 넘기는 경우도) 원래 예외를 그대로 알린다. 진단용이며 동작에는 영향이 없다.
         *
         * @param site 예외가 난 곳(page-failed, frame-skipped 등)
         * @param url 그때 보던 주소(모르면 빈 문자열)
         */
        default void onError(String site, String url, Throwable error) {}
    }

    private static final int NAV_TIMEOUT_MS = 15_000;
    private static final int LOAD_WAIT_MS = 3_000;
    /** 페이지 하나에 머물 수 있는 최대 시간: 로딩 15초 + 탐지 23초. 넘으면 브라우저를 끊는다. */
    private static final long STUCK_NANOS = TimeUnit.SECONDS.toNanos(15 + 23);
    private static final long WATCH_INTERVAL_MS = 500;
    /** 이보다 빨리 실패하면 사이트가 막고 있을 수 있으므로 잠깐 쉰다. */
    private static final long FAST_FAIL_NANOS = TimeUnit.MILLISECONDS.toNanos(1_000);
    private static final long FAST_FAIL_PAUSE_MS = 500;
    private static final int MAX_RESTARTS = 5;
    /** 한 워커가 이만큼 연달아 실패하면 요청 간격을 크게 벌린다. */
    private static final int SLOWDOWN_STREAK = 5;
    private static final long SLOWDOWN_PAUSE_MS = 3_000;
    /** 모든 워커를 통틀어 이만큼 연달아 실패하면 점검을 멈춘다. */
    private static final int ABORT_STREAK = 40;
    private static final int POLITE_MIN_MS = 300;
    private static final int POLITE_MAX_MS = 3_000;
    private static final int POLITE_RECOVERY = 30;
    /** iframe의 문서가 뜨기를 기다리는 시간. 넘으면 문서 없는 frame으로 보고 건너뛴다. */
    private static final int FRAME_READY_MS = 2_000;
    private static final int ENTRY_ATTEMPTS = 3;
    /** 페이지가 스스로 다른 주소로 넘어가는 것을 몇 번까지 따라갈지. */
    private static final int MAX_CLIENT_REDIRECTS = 4;
    private static final long ENTRY_RETRY_PAUSE_MS = 2_000;
    /**
     * 모든 문서에서 페이지 스크립트보다 먼저 돌아 대화상자를 띄우지 못하게 한다. 광고 판정과는 상관없다.
     * 이동하는 도중에 뜬 대화상자는 닫기에 실패해(Not attached to an active page) 탭이 굳고, 그 뒤 이동이 시간초과로 끝난다.
     * 그래도 뜨는 대화상자(body의 onbeforeunload 속성 등)는 {@code answer()}가 받는다.
     */
    private static final String NO_DIALOGS = """
        (() => {
          try {
            window.alert = () => undefined;
            window.confirm = () => false;
            window.prompt = () => null;
            const add = EventTarget.prototype.addEventListener;
            EventTarget.prototype.addEventListener = function (type, listener, options) {
              if (type === 'beforeunload' && this === window) return undefined;
              return add.call(this, type, listener, options);
            };
            Object.defineProperty(window, 'onbeforeunload', { get: () => null, set: () => {}, configurable: false });
          } catch (e) { /* 막지 못하면 answer()가 받는다 */ }
        })();
        """;
    private static final Pattern PLAYWRIGHT_MESSAGE = Pattern.compile("message='([^\\n']*)");
    /** 속도를 위해 받지 않는 것(이미지·미디어·폰트). 글자 은닉 판정에는 필요 없다. */
    private static final Pattern BLOCKED = Pattern.compile(
        "\\.(?:png|jpe?g|gif|webp|bmp|ico|svg|avif|tiff?|woff2?|ttf|otf|eot|mp4|mp3|webm|ogg|wav|avi|mov|wmv|flv|m4a|m4v)(?:[?#].*)?$",
        Pattern.CASE_INSENSITIVE);

    private final ScanOptions options;
    private final String entryUrl;
    private final TimeBudget budget;
    private final FindingCollector collector;
    private final Listener listener;
    private final Frontier frontier = new Frontier();
    private final CrawlScope scope;
    private final AdSignals signals;
    private final List<Worker> workers = new ArrayList<>();
    private final AtomicInteger visited = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    /** 열었지만 본문 frame을 점검하지 못한 페이지 수. */
    private final AtomicInteger uninspected = new AtomicInteger();
    /** 요소가 너무 많거나 시간이 모자라 일부만 점검한 페이지 수. */
    private final AtomicInteger truncated = new AtomicInteger();
    /** 확장자로는 몰랐지만 열어 보니 내려받는 파일이었던 주소 수. */
    private final AtomicInteger files = new AtomicInteger();
    /** 최대 페이지 수가 있을 때 잡아 둔 방문 자리 수({@link #reserve()}). */
    private final AtomicInteger slots = new AtomicInteger();
    private final AtomicInteger launched = new AtomicInteger();
    private final CountDownLatch entryDone = new CountDownLatch(1);
    private volatile boolean stopping;
    private volatile String browserName = "";
    private volatile String launchError;
    private volatile String entryError;
    private volatile String blocked;
    private volatile List<Cookie> sessionCookies = List.of();
    private final AtomicInteger globalFailureStreak = new AtomicInteger();
    private final AtomicInteger successesSinceRefusal = new AtomicInteger();
    /** 요청 사이에 두는 간격. 사이트가 요청을 거부하면 늘리고, 잘 열리면 줄인다. */
    private volatile int politeDelayMs;

    /** @param entryUrl 다듬은 진입 URL({@link UrlNormalizer#entry}) */
    public Crawler(ScanOptions options, String entryUrl, TimeBudget budget, FindingCollector collector, Listener listener) {
        this.options = options;
        this.entryUrl = entryUrl;
        this.budget = budget;
        this.collector = collector;
        this.listener = listener;
        this.scope = new CrawlScope(entryUrl);
        this.signals = new AdSignals(KeywordDictionary.get());
    }

    /** 대기열이 비거나 soft 마감이 될 때까지 돈다. 부른 스레드를 막는다. */
    public void run() throws InterruptedException {
        frontier.offer(entryUrl, 0);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < options.workers(); i++) {
            Worker worker = new Worker(i);
            workers.add(worker);
            Thread t = new Thread(worker, "crawl-worker-" + i);
            t.setDaemon(true);
            threads.add(t);
        }
        Thread watchdog = new Thread(this::watch, "crawl-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join();
            }
        } finally {
            stopping = true;
            watchdog.interrupt();
        }
    }

    /** 하던 일을 끊는다(hard 마감·중단). 어느 스레드에서 불러도 된다. */
    public void shutdown() {
        stopping = true;
        frontier.close();
        for (Worker w : List.copyOf(workers)) {
            w.kill();
        }
    }

    public int visited() {
        return visited.get();
    }

    public int failed() {
        return failed.get();
    }

    public int uninspected() {
        return uninspected.get();
    }

    public int truncated() {
        return truncated.get();
    }

    public int files() {
        return files.get();
    }

    /** 다시 시도하려고 미뤄 둔 페이지 중 끝내 다시 방문하지 못한 수. */
    public int deferred() {
        return frontier.requeuedLeft();
    }

    public int queued() {
        return frontier.size();
    }

    public String browserName() {
        return browserName;
    }

    /** 브라우저를 하나도 띄우지 못했으면 그 이유, 아니면 null. */
    public String launchError() {
        return launched.get() == 0 ? launchError : null;
    }

    /** 연속 실패로 점검을 멈췄으면 그 이유, 아니면 null. */
    public String blocked() {
        return blocked;
    }

    /** 진입 주소를 끝내 열지 못했으면 그 이유, 아니면 null. */
    public String entryError() {
        return entryError;
    }

    private boolean pageLimitReached() {
        return options.maxPages() > 0 && visited.get() >= options.maxPages();
    }

    /**
     * 최대 페이지 수가 있으면 방문할 자리를 먼저 잡는다. 워커들이 상한 확인과 방문 사이에 겹쳐 상한을 넘기지 않게 한다.
     * 자리가 다 찼지만 아직 방문으로 세지지 않은 것이 있으면(실패하면 자리를 돌려준다) 기다린다.
     *
     * @return 방문해도 되면 true. true면 방문으로 세지지 않았을 때 {@link #release()}를 불러야 한다.
     */
    private boolean reserve() {
        int max = options.maxPages();
        if (max <= 0) {
            return true;
        }
        while (!stopping && !budget.softExpired() && visited.get() < max) {
            int n = slots.get();
            if (n < max) {
                if (slots.compareAndSet(n, n + 1)) {
                    return true;
                }
                continue;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void release() {
        if (options.maxPages() > 0) {
            slots.decrementAndGet();
        }
    }

    /** 페이지를 열지 못한 이유의 갈래. 갈래마다 다시 시도할지, 실패로 셀지가 다르다. */
    private enum Failure {
        /** 사이트가 거부함(404·410 밖의 4xx·5xx. 요청이 몰리면 400·403·429로 답하는 사이트가 있다): 간격을 늘리고 한 번 더 시도한다. */
        REFUSED,
        /** 일시적인 연결 오류·시간초과: 한 번 더 시도한다. */
        TRANSIENT,
        /** 페이지가 아니라 내려받는 파일: 실패로 세지 않는다. */
        FILE,
        /** 그 밖(없는 페이지, 리다이렉트 반복 등): 실패로 센다. */
        OTHER
    }

    private static final Pattern TRANSIENT_NET = Pattern.compile(
        "net::ERR_(?:CONNECTION_RESET|CONNECTION_CLOSED|CONNECTION_ABORTED|EMPTY_RESPONSE|TIMED_OUT|NETWORK_CHANGED|HTTP2_PROTOCOL_ERROR"
            + "|QUIC_PROTOCOL_ERROR|SOCKET_NOT_CONNECTED)\\b");

    static Failure classify(RuntimeException e) {
        String message = String.valueOf(e.getMessage());
        if (message.startsWith("HTTP ")) {
            return message.equals("HTTP 404") || message.equals("HTTP 410") ? Failure.OTHER : Failure.REFUSED;
        }
        if (message.contains("Download is starting")) {
            return Failure.FILE;
        }
        if (e instanceof TimeoutError || TRANSIENT_NET.matcher(message).find()) {
            return Failure.TRANSIENT;
        }
        return Failure.OTHER;
    }

    private void watch() {
        try {
            while (!stopping) {
                Thread.sleep(WATCH_INTERVAL_MS);
                long now = System.nanoTime();
                for (Worker w : List.copyOf(workers)) {
                    long since = w.pageStart;
                    if (since != 0 && now - since > STUCK_NANOS) {
                        listener.onLog("멈춘 페이지를 끊습니다: " + w.currentUrl);
                        w.kill();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Worker implements Runnable {
        private final int id;
        private final List<Detector> detectors = new ArrayList<>();
        private volatile Session session;
        private volatile boolean killed;
        /** 지금 페이지를 잡은 시각(nanoTime). 쉬는 중이면 0. */
        private volatile long pageStart;
        private volatile String currentUrl = "";
        private Page page;
        private BrowserContext context;
        private int restarts;
        /** 이 워커가 연달아 실패한 횟수. */
        private int failureStreak;
        /** 끝나지 않은 요청 수. 늦게 붙는 내용(댓글 등)을 기다릴지 정하는 데 쓴다. */
        private int inflight;
        /** 지금 페이지의 어느 frame을 요소·시간 상한 때문에 일부만 점검했는가. */
        private boolean pageTruncated;
        /** 우리가 탭을 여는 중인가(팝업으로 보고 닫지 않게). */
        private boolean creatingPage;
        /** 대화상자를 닫지 못해 탭이 굳었을 수 있는가. */
        private boolean pageWedged;

        Worker(int id) {
            this.id = id;
        }

        void kill() {
            killed = true;
            pageStart = 0;
            Session s = session;
            if (s != null) {
                s.kill();
            }
        }

        @Override
        public void run() {
            try {
                detectors.add(new HomoglyphDetector(signals));
                detectors.add(new JamoDetector(signals));
                detectors.add(new TransparentDetector(signals));
                detectors.add(new OffscreenDetector(signals));
                if (options.extras()) {
                    detectors.addAll(ExtraDetectors.all(signals));
                }
                if (!open()) {
                    return;
                }
                // 입력 URL이 리다이렉트된 호스트를 범위에 넣어야 하므로, 첫 워커가 입력 URL을 끝낸 뒤에 나머지가 시작한다.
                while (id != 0 && !stopping && !entryDone.await(200, TimeUnit.MILLISECONDS)) {
                    if (budget.softExpired()) {
                        return;
                    }
                }
                joinSession();
                while (!stopping && reserve()) {
                    Frontier.Entry entry = frontier.take(() -> stopping || budget.softExpired() || pageLimitReached());
                    if (entry == null) {
                        release();
                        break;
                    }
                    // 사이트가 요청을 거부하기 시작했으면 간격을 두고 요청한다.
                    int delay = politeDelayMs;
                    if (delay > 0) {
                        pause(delay);
                    }
                    if (pageWedged && alive()) {
                        // 굳은 탭은 닫기에서 멈출 수도 있으므로 감시 스레드가 보게 한다.
                        pageStart = System.nanoTime();
                        resetPage();
                        pageStart = 0;
                    }
                    long began = System.nanoTime();
                    boolean counted = false;
                    // null = 열었음
                    Failure failure = null;
                    try {
                        // 진입 주소는 일시적인 접속 실패로 점검 전체를 잃지 않게 몇 번 더 해 본다.
                        int attempts = entry.depth() == 0 ? ENTRY_ATTEMPTS : 1;
                        for (int attempt = 1; ; attempt++) {
                            try {
                                counted = visit(entry);
                                if (entry.depth() == 0) {
                                    shareSession();
                                }
                                break;
                            } catch (RuntimeException e) {
                                failure = classify(e);
                                // 이동 자체가 실패했으면 탭이 굳었을 수 있다. 다시 시도하든 넘어가든 새 탭에서 한다.
                                if (e instanceof PlaywrightException && !stopping && alive()) {
                                    resetPage();
                                }
                                if (failure != Failure.FILE && attempt < attempts && !stopping && !budget.softExpired() && (alive() || reopen())) {
                                    listener.onError("entry-retry", entry.url(), e);
                                    listener.onLog("진입 주소 접속 실패, 다시 시도합니다 (" + brief(e) + ")");
                                    pause(ENTRY_RETRY_PAUSE_MS);
                                    continue;
                                }
                                if (failure == Failure.FILE) {
                                    // 확장자로는 알 수 없던 첨부파일(Content-Disposition: attachment). 페이지가 아니므로 실패가 아니다.
                                    listener.onError("file-skipped", entry.url(), e);
                                    files.incrementAndGet();
                                    listener.onLog("파일이라 건너뜀: " + entry.url());
                                    break;
                                }
                                if (failure != Failure.OTHER && entry.depth() != 0 && frontier.requeue(entry)) {
                                    listener.onError("requeued", entry.url(), e);
                                    listener.onLog("나중에 다시 시도: " + entry.url() + " (" + brief(e) + ")");
                                    break;
                                }
                                listener.onError("page-failed", entry.url(), e);
                                failed.incrementAndGet();
                                listener.onLog("건너뜀: " + entry.url() + " (" + brief(e) + ")");
                                if (entry.depth() == 0) {
                                    entryError = brief(e);
                                }
                                break;
                            }
                        }
                    } finally {
                        pageStart = 0;
                        if (!counted) {
                            release();
                        }
                        frontier.done();
                        if (entry.depth() == 0) {
                            entryDone.countDown();
                        }
                    }
                    if (!stopping && !alive() && !reopen()) {
                        break;
                    }
                    if (!stopping && !paceAfter(failure, System.nanoTime() - began)) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                listener.onError("worker-died", currentUrl, e);
                listener.onLog("워커 " + id + " 종료: " + brief(e));
            } catch (Error e) {
                listener.onError("worker-error", currentUrl, e);
                throw e;
            } finally {
                if (id == 0) {
                    entryDone.countDown();
                }
                close();
            }
        }

        private boolean open() {
            try {
                killed = false;
                Session s = BrowserLauncher.launch(options.headless());
                session = s;
                if (stopping) {
                    s.kill();
                    return false;
                }
                browserName = s.name();
                BrowserContext context = s.browser().newContext(new Browser.NewContextOptions()
                    // 다크모드에 따라 색 판정이 흔들리지 않게 고정한다.
                    .setColorScheme(ColorScheme.LIGHT)
                    .setAcceptDownloads(false)
                    .setIgnoreHTTPSErrors(true)
                    .setLocale("ko-KR")
                    .setViewportSize(1366, 900));
                context.setDefaultTimeout(NAV_TIMEOUT_MS);
                context.setDefaultNavigationTimeout(NAV_TIMEOUT_MS);
                // 핸들러에서 난 예외는 Playwright가 다음 호출(다른 페이지의 navigate 등)에서 던지므로 핸들러 안에서 끝낸다.
                context.route(BLOCKED, route -> {
                    try {
                        route.abort();
                    } catch (PlaywrightException e) {
                        listener.onError("route-abort", currentUrl, e);
                    }
                });
                context.addInitScript(NO_DIALOGS);
                context.onPage(popup -> {
                    if (!creatingPage && popup != page) {
                        try {
                            popup.close();
                        } catch (PlaywrightException ignored) {
                            // 이미 닫힌 팝업
                            listener.onError("popup-close", currentUrl, ignored);
                        }
                    }
                });
                this.context = context;
                page = newPage();
                joinSession();
                launched.incrementAndGet();
                return true;
            } catch (RuntimeException e) {
                listener.onError("launch-failed", currentUrl, e);
                launchError = brief(e);
                listener.onLog("워커 " + id + ": 브라우저 실행 실패 - " + brief(e));
                kill();
                return false;
            }
        }

        private Page newPage() {
            creatingPage = true;
            Page p;
            try {
                p = context.newPage();
            } finally {
                creatingPage = false;
            }
            p.onDialog(this::answer);
            p.onRequest(r -> inflight++);
            p.onRequestFinished(r -> inflight--);
            p.onRequestFailed(r -> inflight--);
            return p;
        }

        /**
         * 탭을 새로 연다. 닫지 못한 대화상자 등으로 굳은 탭은 그 뒤의 이동이 모두 시간초과·ERR_ABORTED로 끝나는데,
         * 브라우저는 살아 있어 {@link #reopen()}으로는 풀리지 않는다.
         *
         * @return 새 탭을 열었으면 true
         */
        private boolean resetPage() {
            pageWedged = false;
            try {
                page.close();
            } catch (PlaywrightException e) {
                listener.onError("page-close", currentUrl, e);
            }
            try {
                page = newPage();
                return true;
            } catch (PlaywrightException e) {
                listener.onError("page-reset", currentUrl, e);
                return false;
            }
        }

        /**
         * 대화상자를 닫는다. beforeunload는 받아들여야 한다: dismiss는 "이 페이지에 머묾"이라서
         * 그 뒤의 이동이 모두 net::ERR_ABORTED로 취소되고 워커가 그 페이지에 갇힌다.
         */
        private void answer(Dialog dialog) {
            try {
                if ("beforeunload".equals(dialog.type())) {
                    dialog.accept();
                } else {
                    dialog.dismiss();
                }
            } catch (PlaywrightException e) {
                // 이동하는 도중에 뜬 대화상자: 닫지 못한 채 남으면 탭이 굳으므로 다음 방문 전에 탭을 새로 연다.
                listener.onError("dialog", currentUrl, e);
                pageWedged = true;
            }
        }

        /** 진입 주소를 본 뒤의 쿠키를 다른 워커와 나눈다(접속 확인 쿠키가 없으면 하위 페이지를 거부하는 사이트가 있다). */
        private void shareSession() {
            try {
                sessionCookies = context.cookies();
            } catch (PlaywrightException e) {
                // 쿠키 없이 계속한다.
                listener.onError("cookie-share", currentUrl, e);
            }
        }

        private void joinSession() {
            List<Cookie> cookies = sessionCookies;
            if (context == null || cookies.isEmpty()) {
                return;
            }
            try {
                context.addCookies(cookies);
            } catch (PlaywrightException e) {
                // 쿠키 없이 계속한다.
                listener.onError("cookie-join", currentUrl, e);
            }
        }

        /**
         * 실패한 뒤에는 쉬어 간다. 빠른 실패가 이어지면 사이트가 접속을 막기 시작한 것일 수 있으므로 몰아치지 않는다.
         *
         * @return 계속해도 되면 true, 점검을 멈춰야 하면 false
         */
        private boolean paceAfter(Failure failure, long tookNanos) {
            if (failure == Failure.FILE) {
                // 페이지가 아니었을 뿐 사이트가 막은 것은 아니다.
                return true;
            }
            if (failure == null) {
                failureStreak = 0;
                globalFailureStreak.set(0);
                // 한동안 문제없이 열리면 간격을 다시 줄인다.
                if (politeDelayMs > 0 && successesSinceRefusal.incrementAndGet() >= POLITE_RECOVERY) {
                    successesSinceRefusal.set(0);
                    int halved = politeDelayMs / 2;
                    politeDelayMs = halved < POLITE_MIN_MS ? 0 : halved;
                }
                return true;
            }
            if (failure == Failure.REFUSED) {
                successesSinceRefusal.set(0);
                politeDelayMs = Math.min(POLITE_MAX_MS, Math.max(POLITE_MIN_MS, politeDelayMs * 2));
            }
            failureStreak++;
            if (globalFailureStreak.incrementAndGet() >= ABORT_STREAK) {
                blocked = "페이지 " + ABORT_STREAK + "개가 연달아 열리지 않습니다. 사이트가 접속을 막는 것으로 보여 점검을 멈춥니다.";
                listener.onLog(blocked);
                stopping = true;
                frontier.close();
                return false;
            }
            if (failureStreak >= SLOWDOWN_STREAK) {
                pause(SLOWDOWN_PAUSE_MS);
            } else if (tookNanos < FAST_FAIL_NANOS) {
                pause(FAST_FAIL_PAUSE_MS);
            }
            return true;
        }

        private boolean alive() {
            if (killed) {
                return false;
            }
            try {
                return session.browser().isConnected() && !page.isClosed();
            } catch (RuntimeException e) {
                return false;
            }
        }

        private boolean reopen() {
            if (++restarts > MAX_RESTARTS) {
                listener.onLog("워커 " + id + ": 브라우저를 여러 번 다시 띄웠지만 계속 끊깁니다. 이 워커를 멈춥니다.");
                return false;
            }
            Session old = session;
            if (old != null) {
                old.kill();
            }
            return open();
        }

        private void close() {
            Session s = session;
            if (s == null) {
                return;
            }
            if (killed || stopping) {
                s.kill();
            } else {
                s.close();
            }
        }

        /** @return 방문한 페이지로 셌으면 true(파일·범위 밖·이미 본 페이지로 넘어간 경우는 false) */
        private boolean visit(Frontier.Entry entry) {
            pageStart = System.nanoTime();
            currentUrl = entry.url();
            inflight = 0;
            pageTruncated = false;
            Response response = page.navigate(entry.url(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(Math.max(1_000, Math.min(NAV_TIMEOUT_MS, budget.millisToHard()))));
            if (response != null) {
                if (response.status() >= 400) {
                    throw new IllegalStateException("HTTP " + response.status());
                }
                String type = contentType(response);
                if (type != null && !type.contains("html") && !type.contains("xml")) {
                    return false;
                }
            }
            // 페이지가 스크립트·meta refresh로 스스로 다른 주소로 넘어가면 점검 중이던 문서가 사라진다.
            // 새 문서가 뜰 때까지 기다렸다가 다시 본다.
            boolean counted = false;
            for (int hop = 0; ; hop++) {
                try {
                    waitFor(LoadState.LOAD, LOAD_WAIT_MS);
                    settle();
                    String pageUrl = UrlNormalizer.clean(page.url());
                    if (entry.depth() == 0) {
                        scope.addHostOf(pageUrl);
                    }
                    if (!scope.contains(pageUrl)) {
                        listener.onLog("범위 밖으로 이동해 건너뜀: " + entry.url() + " → " + pageUrl);
                        return false;
                    }
                    // 다른 주소에서 이미 본 페이지로 리다이렉트된 경우(로그인 페이지 등)
                    if (!counted && !Frontier.visitKey(pageUrl).equals(Frontier.visitKey(entry.url())) && !frontier.markSeen(pageUrl)) {
                        return false;
                    }
                    for (Frame frame : page.frames()) {
                        inspect(frame, pageUrl, entry.depth());
                    }
                    if (!counted) {
                        counted = true;
                        if (pageTruncated) {
                            truncated.incrementAndGet();
                            listener.onLog("요소가 너무 많아 일부만 점검함: " + pageUrl);
                        }
                        listener.onPage(pageUrl, visited.incrementAndGet(), frontier.size());
                    }
                    return true;
                } catch (PlaywrightException e) {
                    if (hop >= MAX_CLIENT_REDIRECTS || !navigated(e) || !alive()) {
                        throw e;
                    }
                    listener.onError("redirect-followed", entry.url(), e);
                    waitFor(LoadState.DOMCONTENTLOADED, NAV_TIMEOUT_MS);
                }
            }
        }

        /** 점검 중 문서가 바뀌어서 난 오류인가. */
        private boolean navigated(PlaywrightException e) {
            String message = String.valueOf(e.getMessage());
            return message.contains("context was destroyed") || message.contains("navigation") || message.contains("Cannot find context")
                || message.contains("detached");
        }

        /**
         * 로드 뒤에 붙는 내용(AJAX 댓글 등)과 lazy iframe을 기다린다. 진행 중인 요청이 없으면 바로 넘어간다.
         * 페이지가 바꿔 두었을 수 있는 Promise·setTimeout은 쓰지 않는다(바꾼 페이지에서는 실패하거나 끝나지 않았다).
         * 기다리기는 덤이므로 실패해도 지금 상태로 점검한다.
         */
        private void settle() {
            if (options.settleMillis() <= 0) {
                return;
            }
            Object lazy;
            try {
                lazy = page.evaluate("""
                    () => {
                      let n = 0;
                      for (const f of document.querySelectorAll('iframe[loading="lazy"]')) { f.loading = 'eager'; n++; }
                      return n;
                    }
                    """);
            } catch (PlaywrightException e) {
                if (navigated(e) || !alive()) {
                    throw e;
                }
                listener.onError("settle", currentUrl, e);
                return;
            }
            boolean woke = lazy instanceof Number n && n.intValue() > 0;
            if (woke) {
                // 깨운 iframe이 요청을 시작할 틈을 준다.
                page.waitForTimeout(50);
            }
            if (woke || inflight > 0) {
                waitFor(LoadState.NETWORKIDLE, options.settleMillis());
            }
        }

        private void waitFor(LoadState state, int timeoutMs) {
            try {
                page.waitForLoadState(state, new Page.WaitForLoadStateOptions().setTimeout(timeoutMs));
            } catch (TimeoutError e) {
                // 다 기다리지 않고 지금 상태로 본다.
                listener.onError("wait-timeout-" + state.name().toLowerCase(java.util.Locale.ROOT), currentUrl, e);
            }
        }

        private void inspect(Frame frame, String pageUrl, int depth) {
            try {
                if (frame.isDetached()) {
                    return;
                }
                boolean main = frame == page.mainFrame();
                if (!main) {
                    // 문서가 아직(또는 끝내) 뜨지 않은 frame에서는 evaluate가 끝없이 기다린다. 시간 제한이 있는 대기로 먼저 확인한다.
                    try {
                        frame.waitForLoadState(LoadState.DOMCONTENTLOADED, new Frame.WaitForLoadStateOptions().setTimeout(FRAME_READY_MS));
                    } catch (TimeoutError e) {
                        listener.onError("frame-timeout", frame.url(), e);
                        return;
                    }
                }
                if (main || scope.contains(frame.url())) {
                    for (String link : LinkExtractor.extract(frame)) {
                        if (LinkExtractor.visitable(link, scope)) {
                            frontier.offer(link, depth + 1);
                        }
                    }
                }
                FrameSnapshot snapshot = FrameSnapshot.collect(frame);
                if (snapshot.truncated()) {
                    pageTruncated = true;
                }
                if (snapshot.holders().isEmpty()) {
                    return;
                }
                FrameContext context = new FrameContext(snapshot);
                List<Candidate> candidates = new ArrayList<>();
                for (Detector detector : detectors) {
                    candidates.addAll(detector.detect(context));
                }
                if (candidates.isEmpty()) {
                    return;
                }
                LinkedHashSet<Integer> elements = new LinkedHashSet<>();
                candidates.forEach(c -> elements.add(c.element()));
                List<Integer> order = new ArrayList<>(elements);
                List<String> selectors = SelectorService.selectors(frame, order);
                Map<Integer, String> selectorOf = new HashMap<>();
                for (int i = 0; i < order.size() && i < selectors.size(); i++) {
                    selectorOf.put(order.get(i), selectors.get(i));
                }
                String prefix = main ? "" : SelectorService.framePrefix(frame);
                if (prefix == null) {
                    return;
                }
                for (Candidate c : candidates) {
                    // 한 곳만 가리키는 선택자를 만들지 못한 요소는 보고하지 않는다(두 곳 이상 가리키면 오답).
                    String selector = selectorOf.get(c.element());
                    if (selector == null) {
                        continue;
                    }
                    Finding finding = new Finding(pageUrl, prefix + selector, c.technique(), c.evidence(), c.extraType(), c.detail());
                    if (collector.add(finding)) {
                        listener.onFinding(finding);
                    }
                }
            } catch (PlaywrightException e) {
                if (!alive() || frame == page.mainFrame() && navigated(e)) {
                    throw e;
                }
                // 문서가 없거나 점검 중 사라진 frame: 이 frame만 건너뛴다.
                skipped(frame, pageUrl, e);
            } catch (RuntimeException e) {
                // 페이지가 바꿔 둔 전역 때문에 수집 결과를 읽지 못한 경우 등. 이 frame만 건너뛰고 나머지 frame은 계속 본다.
                skipped(frame, pageUrl, e);
            }
        }

        /** 점검하지 못한 frame을 알린다. 본문 frame이면 그 페이지의 광고를 놓쳤을 수 있으므로 따로 센다. */
        private void skipped(Frame frame, String pageUrl, RuntimeException e) {
            if (frame != page.mainFrame()) {
                listener.onError("frame-skipped", pageUrl, e);
                return;
            }
            listener.onError("main-frame-skipped", pageUrl, e);
            uninspected.incrementAndGet();
            listener.onLog("본문을 점검하지 못함: " + pageUrl + " (" + brief(e) + ")");
        }

        private String contentType(Response response) {
            try {
                return response.headerValue("content-type");
            } catch (PlaywrightException e) {
                listener.onError("content-type", currentUrl, e);
                return null;
            }
        }

        private void pause(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String brief(Throwable e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        // Playwright 오류는 여러 줄짜리 묶음으로 온다. 그 안의 message만 꺼낸다.
        Matcher m = PLAYWRIGHT_MESSAGE.matcher(message);
        if (m.find()) {
            message = m.group(1);
        }
        int nl = message.indexOf('\n');
        message = (nl < 0 ? message : message.substring(0, nl)).strip();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
