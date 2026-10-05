package addetector.model;

import java.util.List;

/**
 * 검출 한 건. 채점 단위는 (url, location, technique)이다.
 *
 * @param url 검출 항목이 있는 페이지의 전체 URL
 * @param location 요소 위치(CSS 선택자, iframe은 {@code iframe[src="…"] >>> 선택자})
 * @param evidenceText 근거 원문(정규화 전)
 * @param extraType ETC일 때 추가 탐지 유형 이름, 아니면 null
 * @param detail 사람용 근거. result.json에는 쓰지 않는다.
 */
public record Finding(String url, String location, Technique technique, String evidenceText, String extraType, Detail detail) {

    /**
     * @param decoded 위장을 풀어 읽은 문구
     * @param method 숨긴·위장한 방법(예: {@code left:-9999px})
     * @param reasons 탐지 근거 설명
     * @param keywords 광고로 판단한 키워드·연락처
     */
    public record Detail(String decoded, String method, List<String> reasons, List<String> keywords) {
        public Detail {
            reasons = List.copyOf(reasons);
            keywords = List.copyOf(keywords);
        }
    }

    public Finding withLocation(String newLocation) {
        return new Finding(url, newLocation, technique, evidenceText, extraType, detail);
    }
}
