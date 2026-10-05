package addetector.crawl;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 브라우저를 띄운다. 점검 중에는 브라우저를 내려받지 않고(오프라인 평가 PC),
 * 함께 배포한 Chromium → 설치된 Edge → 설치된 Chrome 순으로 시도한다.
 */
public final class BrowserLauncher {
    private BrowserLauncher() {}

    /**
     * @param driver Playwright 드라이버 프로세스. 멈춘 브라우저를 다른 스레드에서 끊을 때 쓴다(없을 수 있음).
     * @param name 어떤 브라우저가 떴는가
     */
    public record Session(Playwright playwright, Browser browser, ProcessHandle driver, String name) {
        /** 드라이버와 브라우저 프로세스를 강제로 끝낸다. 어느 스레드에서 불러도 된다. */
        public void kill() {
            if (driver != null) {
                driver.descendants().forEach(ProcessHandle::destroyForcibly);
                driver.destroyForcibly();
            }
        }

        /** 정상 종료. 이 세션을 만든 스레드에서만 부른다. */
        public void close() {
            try {
                playwright.close();
            } catch (RuntimeException e) {
                kill();
            }
        }
    }

    private static final Object CREATE_LOCK = new Object();
    private static final int LAUNCH_TIMEOUT_MS = 30_000;

    public static Session launch(boolean headless) {
        Playwright playwright;
        ProcessHandle driver;
        // 새로 생긴 자식 프로세스를 드라이버로 본다. 워커들이 동시에 띄우면 구분할 수 없으므로 한 번에 하나씩 만든다.
        synchronized (CREATE_LOCK) {
            Set<Long> before = new HashSet<>();
            ProcessHandle.current().children().forEach(p -> before.add(p.pid()));
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(environment()));
            driver = ProcessHandle.current().children().filter(p -> !before.contains(p.pid())).findFirst().orElse(null);
        }
        List<String> errors = new ArrayList<>();
        // channel null = 함께 배포한 Chromium
        for (String channel : new String[] {null, "msedge", "chrome"}) {
            try {
                BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setTimeout(LAUNCH_TIMEOUT_MS)
                    // lazy iframe도 바로 불러오게 한다(화면 아래쪽 iframe 안의 광고를 놓치지 않게).
                    .setArgs(List.of("--disable-features=LazyFrameLoading,LazyImageLoading", "--mute-audio"));
                if (channel != null) {
                    options.setChannel(channel);
                }
                Browser browser = playwright.chromium().launch(options);
                return new Session(playwright, browser, driver, channel == null ? "chromium" : channel);
            } catch (PlaywrightException e) {
                errors.add((channel == null ? "chromium" : channel) + ": " + firstLine(e.getMessage()));
            }
        }
        try {
            playwright.close();
        } catch (RuntimeException ignored) {
            // 이미 실패한 상태다.
        }
        throw new IllegalStateException("브라우저를 실행할 수 없습니다 (Chromium → Edge → Chrome 모두 실패). " + String.join(" | ", errors));
    }

    /** 드라이버에 넘길 환경 변수: 브라우저를 내려받지 않고, 배포 폴더의 browsers\\ 가 있으면 그것을 쓴다. */
    private static Map<String, String> environment() {
        Map<String, String> env = new HashMap<>();
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        String home = System.getProperty("addetector.home");
        if (home != null) {
            Path browsers = Path.of(home, "browsers");
            if (Files.isDirectory(browsers)) {
                env.put("PLAYWRIGHT_BROWSERS_PATH", browsers.toAbsolutePath().toString());
            }
        }
        return env;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        return (nl < 0 ? message : message.substring(0, nl)).strip();
    }
}
