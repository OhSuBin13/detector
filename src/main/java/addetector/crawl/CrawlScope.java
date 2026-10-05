package addetector.crawl;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 점검 범위: 입력 URL의 호스트와, 입력 URL이 리다이렉트된 호스트뿐이다. 다른 호스트는 방문하지 않는다. */
public final class CrawlScope {
    private final Set<String> hosts = ConcurrentHashMap.newKeySet();

    public CrawlScope(String entryUrl) {
        addHostOf(entryUrl);
    }

    public void addHostOf(String url) {
        String host = bare(UrlNormalizer.host(url));
        if (!host.isEmpty()) {
            hosts.add(host);
        }
    }

    public boolean contains(String url) {
        return UrlNormalizer.isHttp(url) && hosts.contains(bare(UrlNormalizer.host(url)));
    }

    /** www.example.go.kr과 example.go.kr은 같은 곳으로 본다. */
    private static String bare(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }
}
