package addetector.text;

import java.text.Normalizer;

/** 낱자모가 섞인 글을 사람이 읽는 꼴로 되돌린다. */
public final class JamoText {
    private JamoText() {}

    /** 자모 사이에 낀 구분자를 한 구간으로 이어 볼 최대 길이. */
    private static final int MAX_GAP = 2;

    /**
     * 풀어 쓴 자모를 음절로 다시 합친다.
     * <ul>
     *   <li>{@code ㅋㅏㅈㅣㄴㅗ 바로가기} → {@code 카지노 바로가기}</li>
     *   <li>{@code ㅂ.ㅏ.ㅋ.ㅏ.ㄹ.ㅏ} → {@code 바카라} (글자마다 끼운 구분자는 버림)</li>
     *   <li>{@code ㅋr지노} → {@code 카지노} (자모 옆의 닮은꼴 글자는 자모로 읽음)</li>
     * </ul>
     */
    public static String decode(String text) {
        char[] chars = normalize(text);
        StringBuilder out = new StringBuilder(chars.length);
        int i = 0;
        while (i < chars.length) {
            if (!HangulJamo.isCompatJamo(chars[i])) {
                out.append(chars[i]);
                i++;
                continue;
            }
            // 구간: 자모로 시작해 자모로 끝나고, 사이 구분자는 MAX_GAP 이하
            int end = i + 1;
            int gaps = 0;
            int gapsWithSeparator = 0;
            int scan = i + 1;
            while (scan < chars.length) {
                int sep = scan;
                while (sep < chars.length && isSeparator(chars[sep]) && sep - scan <= MAX_GAP) {
                    sep++;
                }
                if (sep >= chars.length || sep - scan > MAX_GAP || !HangulJamo.isCompatJamo(chars[sep])) {
                    break;
                }
                gaps++;
                if (sep > scan) {
                    gapsWithSeparator++;
                }
                end = sep + 1;
                scan = end;
            }
            boolean spaced = gaps > 0 && gapsWithSeparator * 2 > gaps;
            StringBuilder run = new StringBuilder();
            for (int k = i; k < end; k++) {
                char c = chars[k];
                if (HangulJamo.isCompatJamo(c)) {
                    run.append(c);
                } else if (spaced) {
                    // 글자마다 구분자를 끼운 꼴: 한 칸짜리는 버리고, 두 칸 이상은 낱말 경계로 본다.
                    int sepEnd = k;
                    while (sepEnd < end && !HangulJamo.isCompatJamo(chars[sepEnd])) {
                        sepEnd++;
                    }
                    if (sepEnd - k >= 2) {
                        out.append(HangulJamo.compose(run)).append(' ');
                        run.setLength(0);
                    }
                    k = sepEnd - 1;
                } else {
                    out.append(HangulJamo.compose(run)).append(c);
                    run.setLength(0);
                }
            }
            out.append(HangulJamo.compose(run));
            i = end;
        }
        return out.toString();
    }

    /** 반각·조합형 자모를 호환 자모로 바꾸고, 진짜 자모에 붙은 닮은꼴 글자를 자모로 읽는다. */
    private static char[] normalize(String text) {
        // 정상적으로 조합되는 조합형 자모(NFD 문서)는 음절로 합쳐 둔다.
        char[] chars = Normalizer.normalize(text, Normalizer.Form.NFC).toCharArray();
        boolean[] real = new boolean[chars.length];
        for (int i = 0; i < chars.length; i++) {
            char jamo = HangulJamo.toCompatJamo(chars[i]);
            if (jamo != 0) {
                chars[i] = jamo;
                real[i] = true;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < chars.length; k++) {
                int i = pass == 0 ? k : chars.length - 1 - k;
                if (real[i] || HangulJamo.isSyllable(chars[i])) {
                    continue;
                }
                String look = LookalikeTable.toJamo(chars[i]);
                if (look == null) {
                    continue;
                }
                boolean prev = i > 0 && real[i - 1];
                boolean next = i + 1 < chars.length && real[i + 1];
                if (prev || next) {
                    chars[i] = look.charAt(0);
                    real[i] = true;
                }
            }
        }
        return chars;
    }

    private static boolean isSeparator(char c) {
        return !Character.isLetterOrDigit(c) && !HangulJamo.isCompatJamo(c);
    }
}
