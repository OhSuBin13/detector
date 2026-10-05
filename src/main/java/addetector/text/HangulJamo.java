package addetector.text;

/** 한글 음절 ↔ 자모 변환과 자모 조합(두벌식 입력기 방식). */
public final class HangulJamo {
    private HangulJamo() {}

    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String[] JONG = {
        "", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ",
        "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
    };
    /** 겹자모 → 낱자모. 매칭은 항상 낱자모 열에서 한다. */
    private static final String[][] COMPOUND = {
        {"ㅘ", "ㅗㅏ"}, {"ㅙ", "ㅗㅐ"}, {"ㅚ", "ㅗㅣ"}, {"ㅝ", "ㅜㅓ"}, {"ㅞ", "ㅜㅔ"}, {"ㅟ", "ㅜㅣ"}, {"ㅢ", "ㅡㅣ"},
        {"ㄳ", "ㄱㅅ"}, {"ㄵ", "ㄴㅈ"}, {"ㄶ", "ㄴㅎ"}, {"ㄺ", "ㄹㄱ"}, {"ㄻ", "ㄹㅁ"}, {"ㄼ", "ㄹㅂ"},
        {"ㄽ", "ㄹㅅ"}, {"ㄾ", "ㄹㅌ"}, {"ㄿ", "ㄹㅍ"}, {"ㅀ", "ㄹㅎ"}, {"ㅄ", "ㅂㅅ"},
    };

    public static boolean isSyllable(char c) {
        return c >= 0xAC00 && c <= 0xD7A3;
    }

    /** 호환 자모(ㄱ~ㅣ). */
    public static boolean isCompatJamo(char c) {
        return c >= 0x3131 && c <= 0x3163;
    }

    public static boolean isConsonant(char c) {
        return c >= 0x3131 && c <= 0x314E;
    }

    public static boolean isVowel(char c) {
        return c >= 0x314F && c <= 0x3163;
    }

    /** 반각 자모(ﾡ~ￜ)·조합형 자모(ᄀ, ᅡ, ᆨ …)를 호환 자모로 바꾼다. 해당 없으면 0. */
    public static char toCompatJamo(char c) {
        if (isCompatJamo(c)) {
            return c;
        }
        // 반각 자모는 호환 자모와 같은 순서로 놓여 있다(모음은 6개씩 끊어져 있음).
        if (c >= 0xFFA1 && c <= 0xFFBE) {
            return (char) (0x3131 + c - 0xFFA1);
        }
        if (c >= 0xFFC2 && c <= 0xFFC7) {
            return (char) (0x314F + c - 0xFFC2);
        }
        if (c >= 0xFFCA && c <= 0xFFCF) {
            return (char) (0x3155 + c - 0xFFCA);
        }
        if (c >= 0xFFD2 && c <= 0xFFD7) {
            return (char) (0x315B + c - 0xFFD2);
        }
        if (c >= 0xFFDA && c <= 0xFFDC) {
            return (char) (0x3161 + c - 0xFFDA);
        }
        if (c >= 0x1100 && c <= 0x1112) {
            return CHO.charAt(c - 0x1100);
        }
        if (c >= 0x1161 && c <= 0x1175) {
            return JUNG.charAt(c - 0x1161);
        }
        if (c >= 0x11A8 && c <= 0x11C2) {
            String j = JONG[c - 0x11A8 + 1];
            return j.charAt(0);
        }
        return 0;
    }

    /** 겹자모를 낱자모로 푼다. 겹자모가 아니면 그대로. */
    public static String splitCompound(char jamo) {
        for (String[] pair : COMPOUND) {
            if (pair[0].charAt(0) == jamo) {
                return pair[1];
            }
        }
        return String.valueOf(jamo);
    }

    /** 음절 하나를 낱자모로 풀어 덧붙인다. */
    public static void decompose(char syllable, StringBuilder out) {
        int code = syllable - 0xAC00;
        int cho = code / (21 * 28);
        int jung = (code % (21 * 28)) / 28;
        int jong = code % 28;
        out.append(CHO.charAt(cho));
        out.append(splitCompound(JUNG.charAt(jung)));
        if (jong != 0) {
            out.append(splitCompound(JONG[jong].charAt(0)));
        }
    }

    /** 문자열을 낱자모 열로 바꾼다(음절은 풀고, 자모는 그대로, 나머지는 버림). */
    public static String toJamo(String text) {
        StringBuilder sb = new StringBuilder(text.length() * 3);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isSyllable(c)) {
                decompose(c, sb);
            } else {
                char j = toCompatJamo(c);
                if (j != 0) {
                    sb.append(splitCompound(j));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 호환 자모 열을 음절로 조합한다. 조합되지 않는 자모는 그대로 남는다.
     * 예: ㅋㅏㅈㅣㄴㅗ → 카지노, ㅂㅗㄴㅓㅅㅡ → 보너스
     */
    public static String compose(CharSequence jamo) {
        StringBuilder out = new StringBuilder(jamo.length());
        char cho = 0;
        char jung = 0;
        char jong = 0;
        for (int i = 0; i < jamo.length(); i++) {
            char c = jamo.charAt(i);
            if (isConsonant(c)) {
                if (cho == 0 && jung == 0) {
                    cho = c;
                } else if (jung == 0) {
                    out.append(cho);
                    cho = c;
                } else if (jong == 0) {
                    if (jongIndex(c) > 0) {
                        jong = c;
                    } else {
                        out.append(syllable(cho, jung, (char) 0));
                        cho = c;
                        jung = 0;
                    }
                } else {
                    char merged = mergeCompound(jong, c);
                    if (merged != 0 && jongIndex(merged) > 0) {
                        jong = merged;
                    } else {
                        out.append(syllable(cho, jung, jong));
                        cho = c;
                        jung = 0;
                        jong = 0;
                    }
                }
            } else if (isVowel(c)) {
                if (cho != 0 && jung == 0 && CHO.indexOf(cho) >= 0) {
                    jung = c;
                } else if (jung != 0 && jong == 0) {
                    char merged = mergeCompound(jung, c);
                    if (merged != 0) {
                        jung = merged;
                    } else {
                        out.append(syllable(cho, jung, (char) 0));
                        out.append(c);
                        cho = 0;
                        jung = 0;
                    }
                } else if (jong != 0) {
                    // 받침이 다음 음절의 초성으로 넘어간다(도깨비불).
                    String parts = splitCompound(jong);
                    char stay = parts.length() == 2 ? parts.charAt(0) : 0;
                    char move = parts.charAt(parts.length() - 1);
                    out.append(syllable(cho, jung, stay));
                    cho = move;
                    jung = c;
                    jong = 0;
                } else {
                    if (cho != 0) {
                        out.append(cho);
                        cho = 0;
                    }
                    out.append(c);
                }
            } else {
                if (cho != 0) {
                    out.append(jung != 0 ? syllable(cho, jung, jong) : cho);
                }
                cho = 0;
                jung = 0;
                jong = 0;
                out.append(c);
            }
        }
        if (cho != 0) {
            out.append(jung != 0 ? syllable(cho, jung, jong) : cho);
        }
        return out.toString();
    }

    private static int jongIndex(char c) {
        for (int i = 1; i < JONG.length; i++) {
            if (JONG[i].charAt(0) == c) {
                return i;
            }
        }
        return 0;
    }

    private static char mergeCompound(char a, char b) {
        for (String[] pair : COMPOUND) {
            if (pair[1].charAt(0) == a && pair[1].charAt(1) == b) {
                return pair[0].charAt(0);
            }
        }
        return 0;
    }

    private static char syllable(char cho, char jung, char jong) {
        int ci = CHO.indexOf(cho);
        int ji = JUNG.indexOf(jung);
        if (ci < 0 || ji < 0) {
            return cho;
        }
        return (char) (0xAC00 + (ci * 21 + ji) * 28 + (jong == 0 ? 0 : jongIndex(jong)));
    }

    /**
     * 퍼지 매칭용 자모 대표값. 예사소리·거센소리·된소리(ㄱㅋㄲ 등)와 ㅐ/ㅔ를 같은 것으로 본다.
     */
    public static char fuzzyClass(char jamo) {
        switch (jamo) {
            case 'ㅋ', 'ㄲ': return 'ㄱ';
            case 'ㅌ', 'ㄸ': return 'ㄷ';
            case 'ㅍ', 'ㅃ': return 'ㅂ';
            case 'ㅊ', 'ㅉ': return 'ㅈ';
            case 'ㅆ': return 'ㅅ';
            case 'ㅔ': return 'ㅐ';
            case 'ㅖ': return 'ㅒ';
            default: return jamo;
        }
    }
}
