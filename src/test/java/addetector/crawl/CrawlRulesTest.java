package addetector.crawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CrawlRulesTest {

    @Test
    void keyFollowsContestComparisonRules() {
        // scheme, 끝의 /, # 이하는 비교하지 않는다.
        assertEquals(UrlNormalizer.key("http://www.example.go.kr/board/"), UrlNormalizer.key("https://www.example.go.kr/board#top"));
        // 쿼리 순서만 다르면 같은 페이지
        assertEquals(UrlNormalizer.key("https://a.kr/v?id=1024&name=test"), UrlNormalizer.key("https://a.kr/v?name=test&id=1024"));
        // 쿼리 값이 다르면 다른 페이지
        assertNotEquals(UrlNormalizer.key("https://a.kr/v?id=1024"), UrlNormalizer.key("https://a.kr/v?id=1180"));
        // 호스트 대소문자·기본 포트·세션 표식
        assertEquals(UrlNormalizer.key("http://A.KR:80/x"), UrlNormalizer.key("https://a.kr/x;jsessionid=ABC123"));
        // 경로 대소문자는 구분한다.
        assertNotEquals(UrlNormalizer.key("http://a.kr/Board"), UrlNormalizer.key("http://a.kr/board"));
    }

    @Test
    void entryUrlIsValidated() {
        assertEquals("http://www.example.go.kr", UrlNormalizer.entry(" www.example.go.kr "));
        assertEquals("https://a.kr/x?y=1", UrlNormalizer.entry("https://a.kr/x?y=1"));
        assertNull(UrlNormalizer.entry(""));
        assertNull(UrlNormalizer.entry(null));
        assertNull(UrlNormalizer.entry("ftp://a.kr/"));
        assertNull(UrlNormalizer.entry("not a url"));
        assertNull(UrlNormalizer.entry("javascript:alert(1)"));
    }

    @Test
    void scopeIsEntryHostOnly() {
        CrawlScope scope = new CrawlScope("https://www.example.go.kr/main");
        assertTrue(scope.contains("http://example.go.kr/board/list"));
        assertTrue(scope.contains("https://WWW.EXAMPLE.GO.KR/a"));
        assertFalse(scope.contains("https://other.go.kr/"));
        assertFalse(scope.contains("https://sub.example.go.kr/"));
        assertFalse(scope.contains("mailto:a@example.go.kr"));
        // 입력 URL이 리다이렉트된 호스트는 범위에 넣는다.
        scope.addHostOf("https://portal.example.go.kr/index.do");
        assertTrue(scope.contains("https://portal.example.go.kr/board"));
    }

    @Test
    void filesAndDestructiveLinksAreNeverVisited() {
        CrawlScope scope = new CrawlScope("https://a.kr/");
        assertFalse(LinkExtractor.visitable("https://a.kr/files/notice.pdf", scope));
        assertFalse(LinkExtractor.visitable("https://a.kr/down/form.HWP?v=2", scope));
        assertFalse(LinkExtractor.visitable("https://a.kr/logout.do", scope));
        assertFalse(LinkExtractor.visitable("https://a.kr/board/delete.html?id=3", scope));
        assertFalse(LinkExtractor.visitable("https://a.kr/board.do?mode=delete&id=3", scope));
        assertFalse(LinkExtractor.visitable("https://b.kr/board/list", scope));
        assertTrue(LinkExtractor.visitable("https://a.kr/board/view?id=3", scope));
        // 낱말의 일부로 들어간 것은 막지 않는다.
        assertTrue(LinkExtractor.visitable("https://a.kr/model/list", scope));
        assertTrue(LinkExtractor.visitable("https://a.kr/delivery/info", scope));
    }

    @Test
    void boardsComeFirstAndDownloadsLast() {
        int board = UrlPriority.score("https://a.kr/board/view?id=3", 2);
        int plain = UrlPriority.score("https://a.kr/intro/greeting", 2);
        int paged = UrlPriority.score("https://a.kr/board/list?page=9", 2);
        int download = UrlPriority.score("https://a.kr/board/download?id=3", 2);
        int english = UrlPriority.score("https://a.kr/eng/intro", 2);
        assertTrue(board < plain);
        assertTrue(board < paged);
        assertTrue(plain < english);
        assertTrue(paged < download);
        // 깊이가 얕을수록 먼저
        assertTrue(UrlPriority.score("https://a.kr/intro", 1) < UrlPriority.score("https://a.kr/intro", 3));
    }

    @Test
    void frontierDeduplicatesAndCapsPagedLists() throws InterruptedException {
        Frontier frontier = new Frontier();
        assertTrue(frontier.offer("https://a.kr/", 0));
        Frontier.Entry first = frontier.take(() -> false);
        assertEquals("https://a.kr/", first.url());
        assertFalse(frontier.offer("http://a.kr", 1));
        // 폴더 주소와 index 문서는 같은 페이지로 본다.
        assertFalse(frontier.offer("https://a.kr/index.html", 1));
        assertTrue(frontier.offer("https://a.kr/index.php?id=3", 1));
        assertFalse(frontier.offer("https://a.kr/index.php?id=3#c", 1));

        int accepted = 0;
        for (int page = 1; page <= 100; page++) {
            if (frontier.offer("https://a.kr/board/list?page=" + page, 1)) {
                accepted++;
            }
        }
        assertEquals(Frontier.LIST_FAMILY_LIMIT, accepted);

        // 우선순위순으로 나온다: 게시판 목록이 일반 페이지보다 먼저.
        assertEquals("https://a.kr/board/list?page=1", frontier.take(() -> false).url());
        frontier.done();
        frontier.done();
        // 마감이면 기다리지 않고 null
        assertNull(frontier.take(() -> true));
        frontier.close();
        assertNull(frontier.take(() -> false));
    }

    @Test
    void refusedPagesAreRetriedOnceAtTheEnd() throws InterruptedException {
        Frontier frontier = new Frontier();
        frontier.offer("https://a.kr/board/view?id=1", 1);
        frontier.offer("https://a.kr/intro", 3);
        Frontier.Entry refused = frontier.take(() -> false);
        assertEquals("https://a.kr/board/view?id=1", refused.url());
        assertTrue(frontier.requeue(refused));
        frontier.done();
        // 다른 페이지를 다 본 뒤에 다시 나온다.
        assertEquals("https://a.kr/intro", frontier.take(() -> false).url());
        frontier.done();
        Frontier.Entry again = frontier.take(() -> false);
        assertEquals("https://a.kr/board/view?id=1", again.url());
        // 두 번은 돌리지 않는다.
        assertFalse(frontier.requeue(again));
        frontier.done();
        assertNull(frontier.take(() -> false));
    }

    @Test
    void frontierEndsWhenDrainedAndIdle() throws InterruptedException {
        Frontier frontier = new Frontier();
        frontier.offer("https://a.kr/", 0);
        Frontier.Entry e = frontier.take(() -> false);
        // 처리 중인 워커가 있으면 다른 워커는 기다리다가, 끝나면 null을 받는다.
        Thread other = new Thread(() -> {
            try {
                assertNull(frontier.take(() -> false));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        other.start();
        Thread.sleep(100);
        assertTrue(other.isAlive());
        frontier.done();
        other.join(2000);
        assertFalse(other.isAlive());
        assertEquals("https://a.kr/", e.url());
    }
}
