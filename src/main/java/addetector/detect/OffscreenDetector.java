package addetector.detect;

import addetector.detect.FrameSnapshot.Holder;
import addetector.model.Technique;
import java.util.List;

public final class OffscreenDetector extends HiddenDetector {
    public OffscreenDetector(AdSignals signals) {
        super(signals);
    }

    @Override
    Technique technique() {
        return Technique.OFFSCREEN;
    }

    @Override
    List<HidingSignal> signals(Holder holder) {
        return OffscreenAnalyzer.signals(holder);
    }
}
