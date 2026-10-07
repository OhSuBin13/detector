package addetector.crawl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** 방문 대기열. 우선순위순으로 꺼내고, 같은 페이지(정규화 키 기준)는 한 번만 넣는다. 스레드 안전. */
public final class Frontier {
    /** 쪽수만 다른 목록 한 묶음에서 방문할 최대 수. */
    public static final int LIST_FAMILY_LIMIT = 30;
    private static final int REQUEUE_PENALTY = 1_000;
    private static final Pattern INDEX_DOCUMENT = Pattern.compile("(?i)/(?:index|default)[.](?:html?|php|jsp|do|aspx?)$");

    /** @param depth 진입 URL에서 몇 번 건너왔는가 */
    public record Entry(String url, int depth, int score, long order) {}

    private final PriorityQueue<Entry> queue = new PriorityQueue<>((a, b) -> a.score() != b.score()
        ? Integer.compare(a.score(), b.score())
        : Long.compare(a.order(), b.order()));
    private final Set<String> seen = new HashSet<>();
    private final Set<String> requeued = new HashSet<>();
    private final Map<String, Integer> families = new HashMap<>();
    private long order;
    private int inProgress;
    private boolean closed;

    /** @return 새로 넣었으면 true */
    public synchronized boolean offer(String url, int depth) {
        if (closed || !seen.add(visitKey(url))) {
            return false;
        }
        String family = UrlPriority.listFamily(url);
        if (family != null) {
            int count = families.merge(family, 1, Integer::sum);
            if (count > LIST_FAMILY_LIMIT) {
                return false;
            }
        }
        queue.add(new Entry(UrlNormalizer.clean(url), depth, UrlPriority.score(url, depth), order++));
        notifyAll();
        return true;
    }

    /**
     * 사이트가 거부한 페이지를 맨 뒤로 돌려 한 번 더 시도하게 한다.
     *
     * @return 다시 넣었으면 true, 이미 한 번 돌린 페이지면 false
     */
    public synchronized boolean requeue(Entry entry) {
        if (closed || !requeued.add(visitKey(entry.url()))) {
            return false;
        }
        queue.add(new Entry(entry.url(), entry.depth(), entry.score() + REQUEUE_PENALTY, order++));
        notifyAll();
        return true;
    }

    /** 방문한 것으로 표시한다(리다이렉트로 도착한 주소). @return 처음 보는 주소면 true */
    public synchronized boolean markSeen(String url) {
        return seen.add(visitKey(url));
    }

    /**
     * 방문 중복 판정 키. 공모전 비교 규칙의 키에 더해, 폴더 주소와 그 폴더의 index 문서를 같은 페이지로 본다
     * (/ 와 /index.html 을 둘 다 방문해 같은 내용을 두 주소로 보고하지 않게). 먼저 만난 주소로 보고한다.
     */
    public static String visitKey(String url) {
        String key = UrlNormalizer.key(url);
        return key.indexOf('?') < 0 ? INDEX_DOCUMENT.matcher(key).replaceFirst("") : key;
    }

    /**
     * 다음 방문 대상을 꺼낸다. 대기열이 비어도 다른 워커가 처리 중이면 새 링크가 나올 수 있으므로 기다린다.
     *
     * @param stop 참이 되면 기다리지 않고 null을 돌려준다(마감)
     * @return 다음 대상, 더 없으면 null. null이 아니면 처리 후 반드시 {@link #done()}을 불러야 한다.
     */
    public synchronized Entry take(BooleanSupplier stop) throws InterruptedException {
        while (true) {
            if (closed || stop.getAsBoolean()) {
                return null;
            }
            Entry e = queue.poll();
            if (e != null) {
                inProgress++;
                return e;
            }
            if (inProgress == 0) {
                notifyAll();
                return null;
            }
            wait(200);
        }
    }

    public synchronized void done() {
        inProgress--;
        notifyAll();
    }

    /** 더 꺼내지 못하게 한다. 기다리던 워커는 null을 받는다. */
    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    public synchronized int size() {
        return queue.size();
    }

    /** 다시 시도하려고 미뤄 둔 페이지 중 아직 대기열에 남은 수. */
    public synchronized int requeuedLeft() {
        int n = 0;
        for (Entry e : queue) {
            if (requeued.contains(visitKey(e.url()))) {
                n++;
            }
        }
        return n;
    }

    public synchronized int seenCount() {
        return seen.size();
    }
}
