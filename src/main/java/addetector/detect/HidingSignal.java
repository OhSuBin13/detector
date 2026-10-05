package addetector.detect;

/**
 * CSS로 글자를 숨긴 신호 하나.
 *
 * @param root 숨김이 걸린 요소 번호
 * @param method 방법 요약(CSS 표기)
 * @param reason 사람용 설명
 */
public record HidingSignal(int root, String method, String reason) {}
