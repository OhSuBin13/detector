package addetector.detect;

import addetector.detect.FrameSnapshot.Holder;
import java.util.ArrayList;
import java.util.List;

/**
 * OFFSCREEN(화면 밖 은닉): font-size 0~1px, 화면 밖 위치(left:-9999px 등), display:none.
 * 가림(z-index로 덮기, 이미지 뒤 글자)과 marquee는 판정 비용·오탐에 비해 이득이 작아 보지 않는다.
 */
public final class OffscreenAnalyzer {
    private OffscreenAnalyzer() {}

    /** 이 값 이하의 글자 크기는 읽을 수 없는 것으로 본다(공모전 정의: 0~1px). */
    public static final double FONT_SIZE_MAX = 1.0;
    /** 화면 오른쪽 끝에서 이만큼 더 나가면 화면 밖으로 본다(가로 스크롤로 닿는 넓은 표와 구분). */
    public static final int FAR_RIGHT = 2000;

    public static List<HidingSignal> signals(Holder h) {
        List<HidingSignal> out = new ArrayList<>(3);
        if (h.dn() >= 0) {
            out.add(new HidingSignal(h.dn(), "display:none", "display:none으로 화면에 그려지지 않습니다."));
        }
        if (h.fs() != null && h.fs().v() <= FONT_SIZE_MAX) {
            String v = TransparentAnalyzer.trim(h.fs().v());
            out.add(new HidingSignal(h.fs().r(), "font-size:" + v + "px", "글자 크기가 " + v + "px이라 읽을 수 없습니다."));
        }
        if (h.off() != null) {
            String side = switch (h.off().how()) {
                case "left" -> "왼쪽";
                case "top" -> "위쪽";
                default -> "오른쪽";
            };
            String at = "x=" + Math.round(h.off().x()) + "px, y=" + Math.round(h.off().y()) + "px";
            out.add(h.off().indent()
                ? new HidingSignal(h.off().r(), "text-indent (" + at + ")", "text-indent로 글자를 화면 " + side + " 밖으로 밀어냈습니다(" + at + ").")
                : new HidingSignal(h.off().r(), "화면 " + side + " 밖 (" + at + ")", "요소를 화면 " + side + " 밖에 배치했습니다(" + at + ")."));
        }
        return out;
    }
}
