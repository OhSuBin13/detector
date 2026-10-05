package addetector.detect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Frame;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * frame 하나에서 공통 수집기(collector.js)가 모은 것.
 * 요소는 브라우저 쪽 {@code window.__adx.els}에 남아 있고 여기서는 번호로만 가리킨다.
 *
 * @param truncated 요소 수·시간 상한에 걸려 일부만 모았는가
 * @param holders 글자를 직접 가진 요소들
 * @param roots 숨김이 걸린 요소들(번호 → 내용)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FrameSnapshot(boolean truncated, List<Holder> holders, Map<Integer, Root> roots) {

    /**
     * @param e 요소 번호
     * @param t 직접 가진 글(공백 정리)
     * @param f 자식 요소의 글까지 합친 글(짧을 때만), 없으면 null
     * @param ph 가장 가까운 holder 조상의 요소 번호, 없으면 -1
     * @param dn display:none인 가장 가까운 조상(자신 포함), 없으면 -1
     * @param fs font-size 0~1px
     * @param off 화면 밖 위치
     * @param op 누적 opacity
     * @param ca 투명한 글자색
     * @param sc 배경과 같은 글자색
     * @param vis visibility:hidden이 걸린 요소, 없으면 -1
     * @param clip 잘라내 숨긴 요소, 없으면 -1
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Holder(
        int e, String t, String f, int ph, int dn, Value fs, Off off, Value op, Value ca, SameColor sc, int vis, int clip, String clipHow) {}

    /**
     * @param v 측정값
     * @param r 숨김이 걸린 요소
     * @param how 어떤 속성인가
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Value(double v, int r, String how) {}

    /**
     * @param how left / top / right
     * @param indent 글자만 밀어냈는가(text-indent)
     * @param x 글자의 문서 좌표
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Off(int r, String how, boolean indent, double x, double y) {}

    /** @param v 대비(1이면 같은 색) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SameColor(double v, int r, String fg, String bg) {}

    /**
     * @param t 요소 전체 글(상한까지)
     * @param n 전체 글 길이
     * @param d DOM 깊이
     * @param p 가장 가까운 root 조상, 없으면 -1
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root(String t, int n, int d, int p) {}

    /** 수집 상한. */
    public static final int MAX_HOLDERS = 20_000;
    public static final int MAX_TEXT = 2_000;
    public static final int MAX_FULL = 600;
    public static final int BUDGET_MS = 6_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTOR = resource("/js/collector.js");

    public static FrameSnapshot collect(Frame frame) {
        // 임계값은 분석기와 같은 값을 쓴다.
        Map<String, Object> opts = Map.of(
            "maxHolders", MAX_HOLDERS,
            "maxText", MAX_TEXT,
            "maxFull", MAX_FULL,
            "budgetMs", BUDGET_MS,
            "fontSizeMax", OffscreenAnalyzer.FONT_SIZE_MAX,
            "farRight", OffscreenAnalyzer.FAR_RIGHT,
            "opacityMax", TransparentAnalyzer.OPACITY_MAX,
            "alphaMax", TransparentAnalyzer.ALPHA_MAX,
            "contrastMax", TransparentAnalyzer.CONTRAST_MAX);
        Object json = frame.evaluate(COLLECTOR, opts);
        return parse((String) json);
    }

    public static FrameSnapshot parse(String json) {
        try {
            return MAPPER.readValue(json, FrameSnapshot.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String resource(String path) {
        try (InputStream in = FrameSnapshot.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("리소스가 없습니다: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
