package addetector.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.Normalizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    void composesJamoIntoSyllables() {
        assertEquals("카지노", HangulJamo.compose("ㅋㅏㅈㅣㄴㅗ"));
        assertEquals("보너스", HangulJamo.compose("ㅂㅗㄴㅓㅅㅡ"));
        assertEquals("먹튀", HangulJamo.compose("ㅁㅓㄱㅌㅟ"));
        assertEquals("먹튀", HangulJamo.compose("ㅁㅓㄱㅌㅜㅣ"));
        assertEquals("파워볼", HangulJamo.compose("ㅍㅏㅇㅝㅂㅗㄹ"));
        assertEquals("값이", HangulJamo.compose("ㄱㅏㅂㅅㅇㅣ"));
        assertEquals("갑시", HangulJamo.compose("ㄱㅏㅂㅅㅣ"));
        // 음절이 되지 않는 자모는 그대로 남는다.
        assertEquals("ㅋㅋㅋ", HangulJamo.compose("ㅋㅋㅋ"));
        assertEquals("ㅠㅠ", HangulJamo.compose("ㅠㅠ"));
    }

    @Test
    void decomposesSyllablesIntoBasicJamo() {
        assertEquals("ㅋㅏㅈㅣㄴㅗ", HangulJamo.toJamo("카지노"));
        assertEquals("ㅁㅓㄱㅌㅜㅣ", HangulJamo.toJamo("먹튀"));
        assertEquals("ㄱㅏㅂㅅ", HangulJamo.toJamo("값"));
        assertEquals("ㅍㅏㅇㅜㅓㅂㅗㄹ", HangulJamo.toJamo("파워볼"));
    }

    @Test
    void convertsOtherJamoFormsToCompat() {
        assertEquals('ㅋ', HangulJamo.toCompatJamo((char) 0x110F));
        assertEquals('ㅏ', HangulJamo.toCompatJamo((char) 0x1161));
        assertEquals('ㄱ', HangulJamo.toCompatJamo((char) 0x11A8));
        assertEquals('ㄱ', HangulJamo.toCompatJamo((char) 0xFFA1));
        assertEquals(0, HangulJamo.toCompatJamo('가'));
        assertEquals(0, HangulJamo.toCompatJamo('a'));
    }

    @Test
    void decodesDecomposedText() {
        assertEquals("카지노 바로가기", JamoText.decode("ㅋㅏㅈㅣㄴㅗ 바로가기"));
        assertEquals("카지노 보너스 코드 LUCKY7", JamoText.decode("ㅋㅏㅈㅣㄴㅗ ㅂㅗㄴㅓㅅㅡ 코드 LUCKY7"));
        assertEquals("바카라 가입", JamoText.decode("ㅂ.ㅏ.ㅋ.ㅏ.ㄹ.ㅏ 가입"));
        assertEquals("카지노 신규", JamoText.decode("ㅋ ㅏ ㅈ ㅣ ㄴ ㅗ 신규"));
        assertEquals("카지노", JamoText.decode("카ㅈㅣ노"));
        assertEquals("카지노 주소", JamoText.decode("ㅋr지노 주소"));
        // NFD로 저장된 정상 한글은 그대로 읽힌다.
        assertEquals("공지사항", JamoText.decode(Normalizer.normalize("공지사항", Normalizer.Form.NFD)));
    }

    @Test
    void ahoCorasickFindsOverlappingPatterns() {
        AhoCorasick ac = new AhoCorasick(List.of("먹튀", "먹튀검증", "검증", "토토"));
        List<AhoCorasick.Hit> hits = ac.search("안전 먹튀검증 토토");
        assertEquals(4, hits.size());
        assertTrue(hits.contains(new AhoCorasick.Hit(3, 7, 1)));
        assertTrue(hits.contains(new AhoCorasick.Hit(3, 5, 0)));
        assertTrue(hits.contains(new AhoCorasick.Hit(5, 7, 2)));
        assertTrue(hits.contains(new AhoCorasick.Hit(8, 10, 3)));
    }

    @Test
    void latinSkeletonSeesThroughLookalikes() {
        // ｍｅｇａ－ＢＥＴ
        String fullwidth = fullwidth("mega-BET");
        LatinSkeleton.Token token = LatinSkeleton.tokens(fullwidth).get(0);
        List<LatinSkeleton.Match> bet = LatinSkeleton.find(token, "bet");
        assertEquals(1, bet.size());
        assertEquals(3, bet.get(0).disguised());

        // 키릴 с, а, і 를 섞은 casino
        String mixed = "" + (char) 0x441 + (char) 0x430 + "s" + (char) 0x456 + "no";
        assertEquals(1, LatinSkeleton.find(LatinSkeleton.tokens(mixed).get(0), "casino").size());

        // 숫자 치환
        LatinSkeleton.Match leet = LatinSkeleton.find(LatinSkeleton.tokens("c4s1no").get(0), "casino").get(0);
        assertEquals(0, leet.disguised());
        assertEquals(2, leet.leetInside());
    }

    @Test
    void latinSkeletonRespectsWordBoundaries() {
        // alphabet 속의 bet, 전각으로 써도 마찬가지
        assertTrue(LatinSkeleton.find(LatinSkeleton.tokens("alphabet").get(0), "bet").isEmpty());
        assertTrue(LatinSkeleton.find(LatinSkeleton.tokens(fullwidth("alphabet")).get(0), "bet").isEmpty());
        // 러시아어 Привет 속의 вет(≈bet): 낱말 전체가 아니므로 인정하지 않는다.
        String privet = "" + (char) 0x41F + (char) 0x440 + (char) 0x438 + (char) 0x432 + (char) 0x435 + (char) 0x442;
        for (LatinSkeleton.Token t : LatinSkeleton.tokens(privet)) {
            assertTrue(LatinSkeleton.find(t, "bet").isEmpty());
        }
        // 끝에 붙은 숫자는 치환으로 보지 않는다(낱말 안쪽 치환 0).
        assertEquals(0, LatinSkeleton.find(LatinSkeleton.tokens("baccara77").get(0), "baccarat").get(0).leetInside());
    }

    @Test
    void qwertyKoreanDecodesWholeWordsOnly() {
        assertEquals("카지노", QwertyKorean.toKorean("zkwlsh"));
        assertEquals("바카라", QwertyKorean.toKorean("qkzkfk"));
        assertEquals("토토", QwertyKorean.toKorean("xhxh"));
        // 음절로 깔끔하게 떨어지지 않으면 한글 자판 입력이 아니다.
        assertNull(QwertyKorean.toKorean("window"));
        assertNull(QwertyKorean.toKorean("abc1"));
    }

    @Test
    void jamoAlignerMatchesMixedForms() {
        List<String> keywords = List.of(HangulJamo.toJamo("카지노"), HangulJamo.toJamo("토토"));
        // ㅋr지노: 낱자모 1 + 닮은꼴 1 + 음절 4
        JamoAligner.Match m = JamoAligner.find(JamoStream.of("ㅋr지노", true), keywords, true).get(0);
        assertEquals(0, m.keyword());
        assertEquals(1, m.jamo());
        assertEquals(1, m.look());
        assertEquals(4, m.syl());
        // 7r지노: ㄱ↔ㅋ 퍼지 한 칸
        JamoAligner.Match fuzzy = JamoAligner.find(JamoStream.of("7r지노", true), keywords, true).get(0);
        assertEquals(1, fuzzy.fuzzy());
        assertEquals(2, fuzzy.look());
        // 정상 음절은 퍼지로 맞추지 않는다(가지노 ≠ 카지노).
        assertTrue(JamoAligner.find(JamoStream.of("가지노", true), keywords, true).isEmpty());
        // 음절 중간에서 끝나는 일치는 인정하지 않는다(카지놀이).
        assertTrue(JamoAligner.find(JamoStream.of("카지놀이", true), keywords, true).isEmpty());
        // 닮은꼴은 한글이 섞인 토큰에서만 자모로 읽는다.
        assertEquals(0, JamoStream.of("r2d2 robot", true).lookCount());
        assertFalse(JamoStream.of("7r지노", false).units().stream().anyMatch(u -> u.kind() == JamoStream.Kind.LOOK));
    }

    static String fullwidth(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c >= '!' && c <= '~' ? (char) (c + 0xFEE0) : c);
        }
        return sb.toString();
    }
}
