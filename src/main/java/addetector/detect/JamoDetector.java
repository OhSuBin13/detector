package addetector.detect;

import addetector.model.Technique;

public final class JamoDetector extends TextDetector {
    private final JamoAnalyzer analyzer;

    public JamoDetector(AdSignals signals) {
        this.analyzer = new JamoAnalyzer(signals);
    }

    @Override
    Technique technique() {
        return Technique.JAMO;
    }

    @Override
    TextVerdict analyze(String text) {
        return analyzer.analyze(text);
    }
}
