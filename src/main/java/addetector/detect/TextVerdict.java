package addetector.detect;

import addetector.model.Finding;
import java.util.List;

/**
 * 텍스트 분석기의 판정.
 *
 * @param decoded 위장을 풀어 읽은 문구
 * @param method 위장 방법 요약
 * @param reasons 탐지 근거
 * @param keywords 광고로 판단한 키워드·연락처
 */
public record TextVerdict(String decoded, String method, List<String> reasons, List<String> keywords) {
    public Finding.Detail toDetail() {
        return new Finding.Detail(decoded, method, reasons, keywords);
    }
}
