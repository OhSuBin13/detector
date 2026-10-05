package addetector.detect;

import addetector.detect.FrameSnapshot.Holder;
import java.util.HashMap;
import java.util.Map;

/** frame 하나를 점검하는 동안 탐지기들이 함께 쓰는 것. */
public final class FrameContext {
    private final FrameSnapshot snapshot;
    private Map<Integer, Holder> byElement;

    public FrameContext(FrameSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public FrameSnapshot snapshot() {
        return snapshot;
    }

    /** 요소 번호로 holder를 찾는다. 없으면 null. */
    public Holder holder(int element) {
        if (byElement == null) {
            byElement = new HashMap<>(snapshot.holders().size() * 2);
            for (Holder h : snapshot.holders()) {
                byElement.put(h.e(), h);
            }
        }
        return byElement.get(element);
    }
}
