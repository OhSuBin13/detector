package addetector.detect;

import addetector.detect.FrameSnapshot.Holder;
import addetector.model.Technique;
import java.util.List;

public final class TransparentDetector extends HiddenDetector {
    public TransparentDetector(AdSignals signals) {
        super(signals);
    }

    @Override
    Technique technique() {
        return Technique.TRANSPARENT;
    }

    @Override
    List<HidingSignal> signals(Holder holder) {
        return TransparentAnalyzer.signals(holder);
    }
}
