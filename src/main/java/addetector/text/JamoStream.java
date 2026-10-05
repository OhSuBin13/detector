package addetector.text;

import java.util.ArrayList;
import java.util.List;

/**
 * 텍스트를 낱자모 단위 열로 편 것. 음절은 풀고, 낱자모는 그대로, 한글과 섞인 닮은꼴 글자는 자모 후보로 바꾼다.
 * 구분자(공백·문장부호)는 버리되 길게 이어지면 장벽을 세워 그 너머로는 매칭되지 않게 한다.
 */
public final class JamoStream {
    public enum Kind {
        /** 정상 음절에서 나온 자모 */
        SYL,
        /** 낱자모로 적힌 것 */
        JAMO,
        /** 자모를 흉내 낸 닮은꼴 글자 */
        LOOK,
        /** 매칭이 넘어갈 수 없는 자리 */
        BARRIER
    }

    /**
     * @param cands 이 칸이 될 수 있는 자모들
     * @param src 원문에서의 위치
     * @param first 음절의 첫 자모인가(SYL만 의미 있음)
     * @param last 음절의 마지막 자모인가(SYL만 의미 있음)
     */
    public record Unit(String cands, Kind kind, int src, boolean first, boolean last) {}

    private static final int MAX_SEPARATORS = 3;

    private final List<Unit> units = new ArrayList<>();
    private int jamoCount;
    private int lookCount;

    public List<Unit> units() {
        return units;
    }

    public int jamoCount() {
        return jamoCount;
    }

    public int lookCount() {
        return lookCount;
    }

    /**
     * @param text 원문
     * @param lookalikes 한글과 한 토큰에 섞인 닮은꼴 글자를 자모 후보로 넣을지
     */
    public static JamoStream of(String text, boolean lookalikes) {
        JamoStream stream = new JamoStream();
        int n = text.length();
        int separators = 0;
        int i = 0;
        while (i < n) {
            // 공백으로 나뉜 토큰 단위로 본다(닮은꼴은 한글이 섞인 토큰에서만 인정).
            if (Character.isWhitespace(text.charAt(i))) {
                separators++;
                i++;
                continue;
            }
            int end = i;
            boolean hangul = false;
            while (end < n && !Character.isWhitespace(text.charAt(end))) {
                char c = text.charAt(end);
                if (HangulJamo.isSyllable(c) || HangulJamo.toCompatJamo(c) != 0) {
                    hangul = true;
                }
                end++;
            }
            int j = i;
            while (j < end) {
                int cp = text.codePointAt(j);
                int len = Character.charCount(cp);
                char c = text.charAt(j);
                char jamo = len == 1 ? HangulJamo.toCompatJamo(c) : 0;
                String look;
                if (len == 1 && HangulJamo.isSyllable(c)) {
                    separators = stream.gap(separators);
                    StringBuilder sb = new StringBuilder(4);
                    HangulJamo.decompose(c, sb);
                    for (int k = 0; k < sb.length(); k++) {
                        stream.units.add(new Unit(String.valueOf(sb.charAt(k)), Kind.SYL, j, k == 0, k == sb.length() - 1));
                    }
                } else if (jamo != 0) {
                    separators = stream.gap(separators);
                    String parts = HangulJamo.splitCompound(jamo);
                    for (int k = 0; k < parts.length(); k++) {
                        stream.units.add(new Unit(String.valueOf(parts.charAt(k)), Kind.JAMO, j, true, true));
                    }
                    stream.jamoCount++;
                } else if (lookalikes && hangul && (look = LookalikeTable.toJamo(cp)) != null) {
                    separators = stream.gap(separators);
                    stream.units.add(new Unit(look, Kind.LOOK, j, true, true));
                    stream.lookCount++;
                } else if (Character.isLetterOrDigit(cp)) {
                    stream.barrier();
                    separators = 0;
                } else {
                    separators++;
                }
                j += len;
            }
            i = end;
        }
        return stream;
    }

    private int gap(int separators) {
        if (separators > MAX_SEPARATORS) {
            barrier();
        }
        return 0;
    }

    private void barrier() {
        if (!units.isEmpty() && units.get(units.size() - 1).kind() != Kind.BARRIER) {
            units.add(new Unit("", Kind.BARRIER, -1, true, true));
        }
    }

    /** 낱자모로 적힌 것만 남긴 열(사이에 낀 글은 모두 버림). 띄엄띄엄 흩어 쓴 자모를 잇는 데 쓴다. */
    public static JamoStream standaloneOnly(String text) {
        JamoStream stream = new JamoStream();
        for (int i = 0; i < text.length(); i++) {
            char jamo = HangulJamo.toCompatJamo(text.charAt(i));
            if (jamo != 0) {
                String parts = HangulJamo.splitCompound(jamo);
                for (int k = 0; k < parts.length(); k++) {
                    stream.units.add(new Unit(String.valueOf(parts.charAt(k)), Kind.JAMO, i, true, true));
                }
                stream.jamoCount++;
            }
        }
        return stream;
    }
}
