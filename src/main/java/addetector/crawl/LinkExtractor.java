package addetector.crawl;

import com.microsoft.playwright.Frame;
import java.util.ArrayList;
import java.util.List;

/** frame에서 다음에 방문할 주소를 모은다. 범위·파일·파괴적 링크 걸러내기는 {@link #visitable}이 한다. */
public final class LinkExtractor {
    private LinkExtractor() {}

    /**
     * a·area의 href와, onclick·data-* 속성·meta refresh·인라인 스크립트에 적힌 이동 주소를 절대 주소로 모은다.
     * download 속성이 붙은 링크(첨부파일)는 넣지 않는다. iframe의 src는 페이지가 아니라 그 페이지의 일부이므로 넣지 않는다.
     */
    private static final String EXTRACT = """
        () => {
          const out = new Set();
          const add = (u) => {
            if (!u) return;
            u = String(u).trim();
            if (!u || u.startsWith('#') || /^(javascript|mailto|tel|sms|data|blob|about):/i.test(u)) return;
            try { out.add(new URL(u, document.baseURI).href); } catch (e) { /* 주소가 아님 */ }
          };
          for (const a of document.querySelectorAll('a[href], area[href]')) {
            if (a.hasAttribute('download')) continue;
            add(a.getAttribute('href'));
          }
          const go = /(?:location(?:\\.href)?\\s*=\\s*|location\\.(?:assign|replace)\\(\\s*|window\\.open\\(\\s*)['"]([^'"]+)['"]/;
          for (const el of document.querySelectorAll('[onclick]')) {
            const m = go.exec(el.getAttribute('onclick') || '');
            if (m) add(m[1]);
          }
          for (const el of document.querySelectorAll('[data-href], [data-url], [data-link]')) {
            add(el.getAttribute('data-href') || el.getAttribute('data-url') || el.getAttribute('data-link'));
          }
          // 점검이 끝난 뒤에 넘어가는 주소(meta refresh, 스크립트의 지연 이동)는 링크가 아니라서 따로 모은다.
          for (const m of document.querySelectorAll('meta[http-equiv]')) {
            if ((m.getAttribute('http-equiv') || '').toLowerCase() !== 'refresh') continue;
            const r = /url\\s*=\\s*['"]?([^'";\\s]+)/i.exec(m.getAttribute('content') || '');
            if (r) add(r[1]);
          }
          const goAll = new RegExp(go.source, 'g');
          for (const s of document.querySelectorAll('script:not([src])')) {
            const text = s.textContent || '';
            if (text.length > 200000) continue;
            goAll.lastIndex = 0;
            for (let m, n = 0; n < 50 && (m = goAll.exec(text)); n++) add(m[1]);
          }
          // 페이지가 바꿔 두었을 수 있는 Array.from 대신 펼침 문법을 쓴다.
          return [...out].slice(0, 5000);
        }
        """;

    public static List<String> extract(Frame frame) {
        Object result = frame.evaluate(EXTRACT);
        if (!(result instanceof List<?> list)) {
            throw new IllegalStateException("링크 목록이 배열이 아닙니다: " + (result == null ? "null" : result.getClass().getSimpleName()));
        }
        List<String> links = new ArrayList<>();
        for (Object o : list) {
            links.add(String.valueOf(o));
        }
        return links;
    }

    /** 방문해도 되는 주소인가: 범위 안의 http(s) 페이지이고, 파일이나 상태를 바꾸는 링크가 아니다. */
    public static boolean visitable(String url, CrawlScope scope) {
        return scope.contains(url) && !UrlPriority.file(url) && !UrlPriority.destructive(url);
    }
}
