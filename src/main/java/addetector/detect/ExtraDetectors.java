package addetector.detect;

import addetector.detect.FrameSnapshot.Holder;
import addetector.detect.KeywordDictionary.Keyword;
import addetector.model.Technique;
import addetector.text.QwertyKorean;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 추가 제안 유형(ETC). 공모전 4기법의 정의에 들지 않는 은닉·위장으로, result_extra.json에만 쓴다.
 * 4기법 분류가 불확실한 것을 result.json에 넣으면 오탐이 되므로 여기로 분리했다.
 */
public final class ExtraDetectors {
    private ExtraDetectors() {}

    public static final String VISIBILITY_HIDDEN = "VISIBILITY_HIDDEN(visibility:hidden 은닉)";
    public static final String CLIPPED = "CLIPPED(clip·overflow로 잘라낸 은닉)";
    public static final String ZERO_WIDTH = "ZERO_WIDTH(제로폭 문자 삽입)";
    public static final String KEYBOARD_LAYOUT = "KEYBOARD_LAYOUT(한/영 자판 바꿔치기)";

    public static List<Detector> all(AdSignals signals) {
        return List.of(new VisibilityDetector(signals), new ClipDetector(signals), new ZeroWidthDetector(signals), new KeyboardLayoutDetector(signals));
    }

    /** visibility:hidden — 자리는 차지하지만 그려지지 않는다. */
    static final class VisibilityDetector extends HiddenDetector {
        VisibilityDetector(AdSignals signals) {
            super(signals);
        }

        @Override
        Technique technique() {
            return Technique.ETC;
        }

        @Override
        String extraType() {
            return VISIBILITY_HIDDEN;
        }

        @Override
        List<HidingSignal> signals(Holder h) {
            return h.vis() < 0 ? List.of()
                : List.of(new HidingSignal(h.vis(), "visibility:hidden", "visibility:hidden으로 화면에 그려지지 않습니다."));
        }
    }

    /** clip, clip-path, 크기 0~1px + overflow:hidden — 화면 안에 있지만 잘려서 보이지 않는다. */
    static final class ClipDetector extends HiddenDetector {
        ClipDetector(AdSignals signals) {
            super(signals);
        }

        @Override
        Technique technique() {
            return Technique.ETC;
        }

        @Override
        String extraType() {
            return CLIPPED;
        }

        @Override
        List<HidingSignal> signals(Holder h) {
            return h.clip() < 0 ? List.of()
                : List.of(new HidingSignal(h.clip(), h.clipHow() == null ? "clip" : h.clipHow(), "글자가 잘려 나가 보이지 않습니다(" + h.clipHow() + ")."));
        }
    }

    /** 키워드 글자 사이에 보이지 않는 제로폭 문자를 끼워 필터를 피한다. */
    static final class ZeroWidthDetector extends TextDetector {
        private static final Pattern ZERO_WIDTH_CHARS = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF\\u00AD]");
        private final AdSignals signals;

        ZeroWidthDetector(AdSignals signals) {
            this.signals = signals;
        }

        @Override
        Technique technique() {
            return Technique.ETC;
        }

        @Override
        String extraType() {
            return ZERO_WIDTH;
        }

        @Override
        TextVerdict analyze(String text) {
            if (!ZERO_WIDTH_CHARS.matcher(text).find()) {
                return null;
            }
            String stripped = ZERO_WIDTH_CHARS.matcher(text).replaceAll("");
            KeywordDictionary dictionary = signals.dictionary();
            Set<String> visible = new LinkedHashSet<>();
            dictionary.scanKorean(text.toLowerCase()).forEach(h -> visible.add(h.keyword().word()));
            List<Keyword> revealed = new ArrayList<>();
            for (KeywordDictionary.Hit h : dictionary.scanKorean(stripped.toLowerCase())) {
                if (!visible.contains(h.keyword().word())) {
                    revealed.add(h.keyword());
                }
            }
            String lowerRaw = text.toLowerCase();
            String lowerStripped = stripped.toLowerCase();
            for (Keyword k : dictionary.latin()) {
                if (k.word().length() >= 4 && lowerStripped.contains(k.word()) && !lowerRaw.contains(k.word())) {
                    revealed.add(k);
                }
            }
            if (revealed.isEmpty()) {
                return null;
            }
            AdSignals.Assessment a = signals.assess(stripped, revealed);
            if (a.blockedByContext() || a.maxWeight() < 2 && !a.adLike()) {
                return null;
            }
            Set<String> keywords = new LinkedHashSet<>();
            revealed.forEach(k -> keywords.add(k.word()));
            keywords.addAll(a.keywordWords());
            keywords.addAll(a.contacts());
            return new TextVerdict(a.decoded(), "제로폭 문자 삽입",
                List.of("글자 사이에 보이지 않는 제로폭 문자(U+200B 등)를 끼웠습니다. 걷어내면 광고 키워드가 됩니다."), List.copyOf(keywords));
        }
    }

    /** 한글 자판으로 칠 낱말을 영문 상태로 쳐서 적는다(zkwlsh = 카지노). */
    static final class KeyboardLayoutDetector extends TextDetector {
        private static final Pattern WORD = Pattern.compile("(?<![A-Za-z0-9])[A-Za-z]{4,24}(?![A-Za-z0-9])");
        /** 키워드 앞뒤에 붙어도 되는 음절 수(조사 등). */
        private static final int SLACK = 2;
        private final AdSignals signals;

        KeyboardLayoutDetector(AdSignals signals) {
            this.signals = signals;
        }

        @Override
        Technique technique() {
            return Technique.ETC;
        }

        @Override
        String extraType() {
            return KEYBOARD_LAYOUT;
        }

        @Override
        TextVerdict analyze(String text) {
            Matcher m = WORD.matcher(text);
            List<Keyword> revealed = new ArrayList<>();
            StringBuilder decoded = new StringBuilder();
            int last = 0;
            while (m.find()) {
                String korean = QwertyKorean.toKorean(m.group());
                if (korean == null) {
                    continue;
                }
                for (KeywordDictionary.Hit h : signals.dictionary().scanKorean(korean)) {
                    if (h.keyword().weight() >= 2 && korean.length() <= h.keyword().word().length() + SLACK) {
                        revealed.add(h.keyword());
                        decoded.append(text, last, m.start()).append(korean);
                        last = m.end();
                        break;
                    }
                }
            }
            if (revealed.isEmpty()) {
                return null;
            }
            decoded.append(text, last, text.length());
            AdSignals.Assessment a = signals.assess(decoded.toString(), revealed);
            if (a.blockedByContext()) {
                return null;
            }
            Set<String> keywords = new LinkedHashSet<>();
            revealed.forEach(k -> keywords.add(k.word()));
            keywords.addAll(a.keywordWords());
            keywords.addAll(a.contacts());
            return new TextVerdict(a.decoded(), "한/영 자판 바꿔치기",
                List.of("한글 자판으로 칠 낱말을 영문 상태로 쳤습니다. 한글 자판으로 되돌리면 광고 키워드가 됩니다."), List.copyOf(keywords));
        }
    }
}
