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
import com.microsoft.playwright.Route;
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
                while (!stopping && !pageLimitReached()) {
                    Frontier.Entry entry = frontier.take(() -> stopping || budget.softExpired() || pageLimitReached());
                    if (entry == null) {
                        break;
                    }
                    // 사이트가 요청을 거부하기 시작했으면 간격을 두고 요청한다.
                    int delay = politeDelayMs;
                    if (delay > 0) {
                        pause(delay);
                    }
                    long began = System.nanoTime();
                    boolean ok = false;
                    boolean refused = false;
                    try {
                        // 진입 주소는 일시적인 접속 실패로 점검 전체를 잃지 않게 몇 번 더 해 본다.
                        int attempts = entry.depth() == 0 ? ENTRY_ATTEMPTS : 1;
                        for (int attempt = 1; ; attempt++) {
                            try {
                                visit(entry);
                                ok = true;
                                if (entry.depth() == 0) {
                                    shareSession();
                                }
                                break;
                            } catch (RuntimeException e) {
                                if (attempt < attempts && !stopping && !budget.softExpired() && (alive() || reopen())) {
                                    listener.onLog("진입 주소 접속 실패, 다시 시도합니다 (" + brief(e) + ")");
                                    pause(ENTRY_RETRY_PAUSE_MS);
                                    continue;
                                }
                                refused = refusal(e);
                                if (refused && entry.depth() != 0 && frontier.requeue(entry)) {
                                    listener.onLog("나중에 다시 시도: " + entry.url() + " (" + brief(e) + ")");
                                    break;
                                }
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
                        frontier.done();
                        if (entry.depth() == 0) {
                            entryDone.countDown();
                        }
                    }
                    if (!stopping && !alive() && !reopen()) {
                        break;
                    }
                    if (!stopping && !paceAfter(ok, refused, System.nanoTime() - began)) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                listener.onLog("워커 " + id + " 종료: " + brief(e));
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
                context.route(BLOCKED, Route::abort);
                page = context.newPage();
                page.onDialog(Dialog::dismiss);
                page.onRequest(r -> inflight++);
                page.onRequestFinished(r -> inflight--);
                page.onRequestFailed(r -> inflight--);
                Page main = page;
                context.onPage(popup -> {
                    if (popup != main) {
                        try {
                            popup.close();
                        } catch (PlaywrightException ignored) {
                            // 이미 닫힌 팝업
                        }
                    }
                });
                this.context = context;
                joinSession();
                launched.incrementAndGet();
                return true;
            } catch (RuntimeException e) {
                launchError = brief(e);
                listener.onLog("워커 " + id + ": 브라우저 실행 실패 - " + brief(e));
                kill();
                return false;
            }
        }

        /** 진입 주소를 본 뒤의 쿠키를 다른 워커와 나눈다(접속 확인 쿠키가 없으면 하위 페이지를 거부하는 사이트가 있다). */
        private void shareSession() {
            try {
                sessionCookies = context.cookies();
            } catch (PlaywrightException e) {
                // 쿠키 없이 계속한다.
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
            }
        }

        /**
         * 실패한 뒤에는 쉬어 간다. 빠른 실패가 이어지면 사이트가 접속을 막기 시작한 것일 수 있으므로 몰아치지 않는다.
         *
         * @return 계속해도 되면 true, 점검을 멈춰야 하면 false
         */
        private boolean paceAfter(boolean ok, boolean refused, long tookNanos) {
            if (ok) {
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
            if (refused) {
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

        /** 사이트가 요청을 거부한 것인가(없는 페이지 404·410은 아니다). 요청이 몰릴 때 400·403·429·5xx로 답하는 사이트가 있다. */
        private boolean refusal(RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            if (!message.startsWith("HTTP ")) {
                return false;
            }
            return !message.equals("HTTP 404") && !message.equals("HTTP 410");
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

        private void visit(Frontier.Entry entry) {
            pageStart = System.nanoTime();
            currentUrl = entry.url();
            inflight = 0;
            Response response = page.navigate(entry.url(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(Math.max(1_000, Math.min(NAV_TIMEOUT_MS, budget.millisToHard()))));
            if (response != null) {
                if (response.status() >= 400) {
                    throw new IllegalStateException("HTTP " + response.status());
                }
                String type = contentType(response);
                if (type != null && !type.contains("html") && !type.contains("xml")) {
                    return;
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
                        return;
                    }
                    // 다른 주소에서 이미 본 페이지로 리다이렉트된 경우(로그인 페이지 등)
                    if (!counted && !Frontier.visitKey(pageUrl).equals(Frontier.visitKey(entry.url())) && !frontier.markSeen(pageUrl)) {
                        return;
                    }
                    for (Frame frame : page.frames()) {
                        inspect(frame, pageUrl, entry.depth());
                    }
                    if (!counted) {
                        counted = true;
                        listener.onPage(pageUrl, visited.incrementAndGet(), frontier.size());
                    }
                    return;
                } catch (PlaywrightException e) {
                    if (hop >= MAX_CLIENT_REDIRECTS || !navigated(e) || !alive()) {
                        throw e;
                    }
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

        /** 로드 뒤에 붙는 내용(AJAX 댓글 등)과 lazy iframe을 기다린다. 진행 중인 요청이 없으면 바로 넘어간다. */
        private void settle() {
            if (options.settleMillis() <= 0) {
                return;
            }
            Object lazy = page.evaluate("""
                () => new Promise((resolve) => {
                  let n = 0;
                  for (const f of document.querySelectorAll('iframe[loading="lazy"]')) { f.loading = 'eager'; n++; }
                  setTimeout(() => resolve(n), 50);
                })
                """);
            boolean woke = lazy instanceof Number n && n.intValue() > 0;
            if (woke || inflight > 0) {
                waitFor(LoadState.NETWORKIDLE, options.settleMillis());
            }
        }

        private void waitFor(LoadState state, int timeoutMs) {
            try {
                page.waitForLoadState(state, new Page.WaitForLoadStateOptions().setTimeout(timeoutMs));
            } catch (TimeoutError e) {
                // 다 기다리지 않고 지금 상태로 본다.
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
            }
        }

        private String contentType(Response response) {
            try {
                return response.headerValue("content-type");
            } catch (PlaywrightException e) {
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
