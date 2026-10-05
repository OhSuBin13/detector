package addetector.crawl;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * URL 정규화. 크롤러의 중복 방문 제거와 결과의 중복 제거가 같은 키를 쓴다.
 * 공모전 비교 규칙(붙임4): scheme·끝의 /·# 이하는 무시, 쿼리 값이 다르면 다른 페이지, 쿼리 순서만 다르면 같은 페이지.
 */
public final class UrlNormalizer {
    private UrlNormalizer() {}

    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");
    private static final Pattern NON_HTTP_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:(?![0-9])");
    private static final Pattern SESSION_ID = Pattern.compile("(?i);jsessionid=[^?#/]*");

    /** 비교용 키. 형식이 이상한 주소도 예외 없이 무언가를 돌려준다. */
    public static String key(String url) {
        String s = clean(url);
        s = SCHEME.matcher(s).replaceFirst("");
        String query = "";
        int q = s.indexOf('?');
        if (q >= 0) {
            query = s.substring(q + 1);
            s = s.substring(0, q);
        }
        int slash = s.indexOf('/');
        String host = slash < 0 ? s : s.substring(0, slash);
        String path = slash < 0 ? "" : s.substring(slash);
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(":80") || host.endsWith(":443")) {
            host = host.substring(0, host.lastIndexOf(':'));
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!query.isEmpty()) {
            String[] params = Arrays.stream(query.split("&")).filter(p -> !p.isEmpty()).sorted().toArray(String[]::new);
            query = String.join("&", params);
        }
        return query.isEmpty() ? host + path : host + path + "?" + query;
    }

    /** 방문·보고용 주소: # 이하와 세션 표식(;jsessionid=)을 뗀다. */
    public static String clean(String url) {
        String s = url.strip();
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        return SESSION_ID.matcher(s).replaceAll("");
    }

    /** 소문자 호스트(포트 제외). 주소가 아니면 빈 문자열. */
    public static String host(String url) {
        String s = url.strip();
        java.util.regex.Matcher m = SCHEME.matcher(s);
        if (!m.find()) {
            return "";
        }
        s = s.substring(m.end());
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        s = s.substring(0, end);
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        int colon = s.lastIndexOf(':');
        if (colon >= 0 && !s.endsWith("]")) {
            s = s.substring(0, colon);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    public static boolean isHttp(String url) {
        String s = url.strip().toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /**
     * 사용자가 넣은 진입 주소를 다듬는다. scheme이 없으면 http://를 붙인다.
     *
     * @return 다듬은 주소, 주소로 볼 수 없으면 null
     */
    public static String entry(String input) {
        if (input == null) {
            return null;
        }
        String s = input.strip();
        if (s.isEmpty() || s.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        if (!SCHEME.matcher(s).find()) {
            // javascript:, mailto: 같은 것은 주소가 아니다(host:8080 꼴은 허용).
            if (NON_HTTP_SCHEME.matcher(s).find()) {
                return null;
            }
            s = "http://" + s;
        }
        return isHttp(s) && !host(s).isEmpty() ? s : null;
    }
}
