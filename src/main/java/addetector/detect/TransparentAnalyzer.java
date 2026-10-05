package addetector.detect;

import addetector.detect.FrameSnapshot.Holder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TRANSPARENT(투명 텍스트): 배경과 같은 글자색, opacity:0, color:transparent 등으로 보이지 않게 한 글자.
 * 측정은 collector.js가 하고, 여기서는 임계값으로 판정하고 방법을 말로 풀어 준다.
 */
public final class TransparentAnalyzer {
    private TransparentAnalyzer() {}

    /** 누적 opacity가 이 값 이하면 보이지 않는 것으로 본다(opacity:0.001 등). */
    public static final double OPACITY_MAX = 0.1;
    /** 글자색 알파가 이 값 이하면 투명색으로 본다. */
    public static final double ALPHA_MAX = 0.1;
    /** 글자색과 배경색의 대비가 이 값 이하면 같은 색으로 본다(#fff 위 #f5f5f5 ≈ 1.09). */
    public static final double CONTRAST_MAX = 1.1;

    public static List<HidingSignal> signals(Holder h) {
        List<HidingSignal> out = new ArrayList<>(3);
        if (h.op() != null && h.op().v() <= OPACITY_MAX) {
            String v = trim(h.op().v());
            out.add(new HidingSignal(h.op().r(), "opacity:" + v, "투명도(opacity)를 " + v + "로 낮춰 글자가 보이지 않습니다."));
        }
        if (h.ca() != null && h.ca().v() <= ALPHA_MAX) {
            String prop = h.ca().how() == null ? "color" : h.ca().how();
            out.add(new HidingSignal(h.ca().r(), prop + ":transparent", "글자색(" + prop + ")이 투명합니다(알파 " + trim(h.ca().v()) + ")."));
        }
        if (h.sc() != null && h.sc().v() <= CONTRAST_MAX) {
            out.add(new HidingSignal(h.sc().r(), "color:" + h.sc().fg() + " / background:" + h.sc().bg(),
                "글자색 " + h.sc().fg() + "이 배경색 " + h.sc().bg() + "과 같아 보이지 않습니다(대비 " + trim(h.sc().v()) + ")."));
        }
        return out;
    }

    static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
        }
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
