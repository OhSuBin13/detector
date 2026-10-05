package addetector.text;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/**
 * 닮은꼴 문자표.
 * <ul>
 *   <li>다른 문자 체계(키릴·그리스 등) → 라틴 글자</li>
 *   <li>숫자·기호 → 라틴 글자(leet)</li>
 *   <li>라틴·숫자·기호·가나·한자 → 한글 자모</li>
 * </ul>
 */
public final class LookalikeTable {
    private LookalikeTable() {}

    private static final Map<Integer, Character> TO_LATIN = new HashMap<>();
    private static final Map<Character, String> LEET = new HashMap<>();
    private static final Map<Integer, String> TO_JAMO = new HashMap<>();

    private static void latin(String pairs) {
        // "аa еe оo" 꼴: 닮은꼴 글자 + 라틴 글자
        for (String pair : pairs.trim().split("\\s+")) {
            int cp = pair.codePointAt(0);
            char to = pair.charAt(pair.length() - 1);
            TO_LATIN.put(cp, to);
        }
    }

    private static void jamo(char jamo, String lookalikes) {
        lookalikes.codePoints().forEach(cp -> TO_JAMO.merge(cp, String.valueOf(jamo), (a, b) -> a.contains(b) ? a : a + b));
    }

    static {
        // 키릴 소문자·대문자
        latin("аa вb сc ԁd еe ёe һh нh іi їi јj кk ӏl мm пn оo рp ԛq гr ѕs тt ѵv ѡw шw хx уy үy");
        latin("Аa Вb Сc Ԁd Еe Ёe Нh Іi Їi Јj Кk Мm Оo Рp Ԛq Ѕs Тt Ѵv Ԝw Хx Уy Үy");
        // 그리스 소문자·대문자
        latin("αa βb εe ηn ιi κk νv οo ρp τt υu χx ωw γy");
        latin("Αa Βb Εe Ζz Ηh Ιi Κk Μm Νn Οo Ρp Τt Υy Χx");
        // 라틴 확장·작은 대문자
        latin("ıi ɑa ɡg ƅb ɩi ʟl ᴀa ʙb ᴄc ᴅd ᴇe ꜰf ɢg ʜh ɪi ᴊj ᴋk ᴍm ɴn ᴏo ᴘp ʀr ꜱs ᴛt ᴜu ᴠv ᴡw ʏy ᴢz øo łl");
        // 아르메니아
        latin("օo սu հh");

        LEET.put('0', "o");
        LEET.put('1', "li");
        LEET.put('3', "e");
        LEET.put('4', "a");
        LEET.put('5', "s");
        LEET.put('7', "t");
        LEET.put('8', "b");
        LEET.put('@', "a");
        LEET.put('$', "s");
        LEET.put('!', "i");

        jamo('ㄱ', "7¬フ");
        jamo('ㄴ', "Lし∟└レ");
        jamo('ㄷ', "cC⊂匚");
        jamo('ㄹ', "2zZ己");
        jamo('ㅁ', "口ロ□");
        jamo('ㅅ', "人入∧^ヘ");
        jamo('ㅇ', "oO0○◯〇");
        jamo('ㅈ', "スズ");
        jamo('ㅋ', "ヲ");
        jamo('ㅌ', "E∈");
        jamo('ㅍ', "π");
        jamo('ㅏ', "rト├卜");
        jamo('ㅐ', "H");
        jamo('ㅓ', "┤");
        jamo('ㅗ', "⊥┴上");
        jamo('ㅜ', "T丁┬");
        jamo('ㅡ', "ー一—─");
        jamo('ㅣ', "lI|1!丨ǀ∣");
    }

    /** 닮은꼴 비라틴 글자가 흉내 내는 라틴 소문자. 없으면 0. */
    public static char scriptToLatin(int codePoint) {
        Character c = TO_LATIN.get(codePoint);
        return c == null ? 0 : c;
    }

    /**
     * NFKC로 접히는 글자(전각·수학용 영숫자·원문자 등)가 가리키는 ASCII 글자. 없으면 0.
     * 한 글자가 여러 글자로 풀리는 기호(㎏, ㈜ 등)는 정상 표기라 대상이 아니다.
     */
    public static char compatToAscii(int codePoint) {
        if (codePoint < 0x80) {
            return 0;
        }
        String n = Normalizer.normalize(new String(Character.toChars(codePoint)), Normalizer.Form.NFKC);
        if (n.length() == 1 && n.charAt(0) < 0x80 && Character.isLetterOrDigit(n.charAt(0))) {
            return n.charAt(0);
        }
        return 0;
    }

    /** 발음 구별 기호를 뗀 라틴 글자(é → e). 없으면 0. */
    public static char stripDiacritic(int codePoint) {
        if (codePoint < 0xC0 || codePoint > 0x24F) {
            return 0;
        }
        String n = Normalizer.normalize(new String(Character.toChars(codePoint)), Normalizer.Form.NFD);
        char base = n.charAt(0);
        return n.length() > 1 && base < 0x80 && Character.isLetter(base) ? Character.toLowerCase(base) : 0;
    }

    /** 숫자·기호가 흉내 낼 수 있는 라틴 소문자들. 없으면 빈 문자열. */
    public static String leetToLatin(char c) {
        return LEET.getOrDefault(c, "");
    }

    /** 글자가 흉내 낼 수 있는 한글 자모들. 없으면 null. */
    public static String toJamo(int codePoint) {
        return TO_JAMO.get(codePoint);
    }
}
