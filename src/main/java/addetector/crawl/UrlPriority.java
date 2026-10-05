package addetector.crawl;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 방문 순서와 방문 제외 규칙. 큰 사이트에서도 광고가 있을 법한 곳(게시판)부터 본다.
 * 점수가 낮을수록 먼저 방문한다.
 */
public final class UrlPriority {
    private UrlPriority() {}

    private static final Pattern BOARD = Pattern.compile(
        "bbs|board|notice|article|post|view|list|comment|reply|qna|faq|free|gallery|forum|community|talk|review|guest|news|press"
            + "|nttid|bbsid|articleno|boardid|게시|공지");
    private static final Pattern PAGE_PARAM = Pattern.compile(
        "(?:^|[?&])(page|pageindex|pageno|page_no|pagenum|pg|cpage|curpage|currentpage|nowpage|offset|start)=(\\d+)");
    private static final Pattern DOWNLOAD = Pattern.compile("down(?:load)?|filedown|attach|getfile|fileid|atchfile|export");
    private static final Pattern LOW_VALUE = Pattern.compile("login|logon|signin|signup|join|member|sitemap|print|popup|privacy|policy|terms|rss");
    private static final Pattern LANGUAGE = Pattern.compile("/(?:eng?|english|chn?|chinese|jpn?|japanese|cn|jp)(?:/|$)|[?&]lang=");
    /** 상태를 바꿀 수 있는 링크. GET이라도 누르지 않는다. */
    private static final Pattern DESTRUCTIVE = Pattern.compile(
        "(?:^|[/_.\\-=?&])(?:logout|log_out|signout|sign_out|delete|del|remove|destroy|unsubscribe|withdraw)(?:[/_.\\-=&]|$)");
    /** 페이지가 아닌 파일. 요청하지 않는다. */
    private static final Pattern FILE = Pattern.compile(
        "\\.(?:pdf|hwpx?|docx?|xlsx?|pptx?|zip|rar|7z|gz|tar|tgz|alz|egg|jpe?g|png|gif|bmp|svg|webp|ico|tiff?|mp3|mp4|avi|mov|wmv|mkv|flv|wav|ogg|webm"
            + "|exe|msi|apk|dmg|iso|bin|csv|txt|xml|json|rss|atom|css|js|map|woff2?|ttf|eot|ics|vcf)$");

    private static final int DEPTH_STEP = 10;

    public static int score(String url, int depth) {
        String s = pathAndQuery(url);
        int score = depth * DEPTH_STEP;
        if (BOARD.matcher(s).find()) {
            score -= 25;
        }
        int page = pageNumber(s);
        if (page > 1) {
            score += 15 + Math.min(page, 30);
        }
        int segments = 0;
        int q = s.indexOf('?');
        String path = q < 0 ? s : s.substring(0, q);
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                segments++;
            }
        }
        if (segments > 5) {
            score += 10;
        }
        if (DOWNLOAD.matcher(s).find()) {
            score += 100;
        }
        if (LOW_VALUE.matcher(s).find()) {
            score += 60;
        }
        if (LANGUAGE.matcher(s).find()) {
            score += 50;
        }
        return score;
    }

    /** 쪽수 매개변수 값. 없으면 0. */
    static int pageNumber(String pathAndQuery) {
        Matcher m = PAGE_PARAM.matcher(pathAndQuery);
        if (!m.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 쪽수만 다른 목록들을 한 묶음으로 보는 키. 쪽수 매개변수가 없으면 null.
     * 묶음마다 방문 수에 상한을 둔다(끝없는 목록에 예산을 다 쓰지 않게).
     */
    public static String listFamily(String url) {
        String key = UrlNormalizer.key(url).toLowerCase(Locale.ROOT);
        Matcher m = PAGE_PARAM.matcher(key);
        if (!m.find()) {
            return null;
        }
        return key.substring(0, m.start(1)) + m.group(1) + "=" + key.substring(m.end());
    }

    public static boolean destructive(String url) {
        return DESTRUCTIVE.matcher(pathAndQuery(url)).find();
    }

    public static boolean file(String url) {
        String s = pathAndQuery(url);
        int q = s.indexOf('?');
        return FILE.matcher(q < 0 ? s : s.substring(0, q)).find();
    }

    private static String pathAndQuery(String url) {
        String s = UrlNormalizer.clean(url);
        int scheme = s.indexOf("://");
        int slash = s.indexOf('/', scheme < 0 ? 0 : scheme + 3);
        return (slash < 0 ? "/" : s.substring(slash)).toLowerCase(Locale.ROOT);
    }
}
