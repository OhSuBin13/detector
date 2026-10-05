package addetector.detect;

import addetector.model.Technique;

public final class HomoglyphDetector extends TextDetector {
    private final HomoglyphAnalyzer analyzer;

    public HomoglyphDetector(AdSignals signals) {
        this.analyzer = new HomoglyphAnalyzer(signals);
    }

    @Override
    Technique technique() {
        return Technique.HOMOGLYPH;
    }

    @Override
    TextVerdict analyze(String text) {
        return analyzer.analyze(text);
    }
}
