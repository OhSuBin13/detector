package addetector.text;

/** 한글 자판(두벌식)으로 칠 글을 영문 상태로 친 것을 되돌린다. 예: zkwlsh → 카지노 */
public final class QwertyKorean {
    private QwertyKorean() {}

    private static final String KEYS = "qwertyuiopasdfghjklzxcvbnmQWERTOP";
    private static final String JAMO = "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡㅃㅉㄸㄲㅆㅒㅖ";

    /** 영문 글자 하나가 가리키는 자모. 없으면 0. */
    public static char keyToJamo(char c) {
        int i = KEYS.indexOf(c);
        if (i < 0 && c >= 'A' && c <= 'Z') {
            i = KEYS.indexOf(Character.toLowerCase(c));
        }
        return i < 0 ? 0 : JAMO.charAt(i);
    }

    /**
     * 영문 낱말 전체가 한글 음절로 깔끔하게 조합되면 그 한글을, 아니면 null을 돌려준다.
     * 낱자모가 남으면(조합 실패) 한글 자판 입력으로 보지 않는다.
     */
    public static String toKorean(String latinWord) {
        StringBuilder jamo = new StringBuilder(latinWord.length());
        for (int i = 0; i < latinWord.length(); i++) {
            char j = keyToJamo(latinWord.charAt(i));
            if (j == 0) {
                return null;
            }
            jamo.append(j);
        }
        String composed = HangulJamo.compose(jamo);
        for (int i = 0; i < composed.length(); i++) {
            if (!HangulJamo.isSyllable(composed.charAt(i))) {
                return null;
            }
        }
        return composed;
    }
}
