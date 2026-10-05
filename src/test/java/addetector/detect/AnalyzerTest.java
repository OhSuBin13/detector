package addetector.detect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 분석기와 의미 게이트. 실사이트에서 나온 오탐 사례는 여기 대조군에 옮겨 회귀를 막는다. */
class AnalyzerTest {
    private final AdSignals signals = new AdSignals(KeywordDictionary.load(null));
    private final JamoAnalyzer jamo = new JamoAnalyzer(signals);
    private final HomoglyphAnalyzer homoglyph = new HomoglyphAnalyzer(signals);

    private static String fullwidth(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c >= '!' && c <= '~' ? (char) (c + 0xFEE0) : c);
        }
        return sb.toString();
    }

    // ───────────────────────── JAMO

    @ParameterizedTest
    @ValueSource(strings = {
        "ㅋㅏㅈㅣㄴㅗ ㅂㅗㄴㅓㅅㅡ 코드 LUCKY7",
        "ㅋㅏㅈㅣㄴㅗ 바로가기",
        "ㅂ.ㅏ.ㅋ.ㅏ.ㄹ.ㅏ 가입 즉시 지급",
        "ㅋ ㅏ ㅈ ㅣ ㄴ ㅗ 신규 이벤트",
        "카ㅈㅣ노 꽁머니",
        "ㅂ 오늘도 좋은 하루 ㅏ 함께 걸어요 ㅋ 가을 하늘 ㅏ 맑은 날씨 ㄹ 산책길 ㅏ 행복한 시간",
        "ㅋr지노 주소 문의",
        "ㄹㅏㅋㅏㅂㅏ 첫충 지급",
        "ㅁㅓㄱㅌㅟ 검증 완료 업체",
        "ㅌㅗㅌㅗ ㅅㅏㅇㅣㅌㅡ 추천",
    })
    void detectsDecomposedAds(String text) {
        assertNotNull(jamo.analyze(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ㅋㅋㅋㅋ 진짜 웃기네요 ㅠㅠ",
        "ㅇㅋ 내일 봬요 ㄱㅅ",
        "초성 퀴즈: ㅋㅈㄴ, ㅂㅋㄹ 정답은 무엇일까요?",
        "한글 자모: ㄱ ㄴ ㄷ ㄹ ㅁ ㅂ ㅅ / ㅏ ㅑ ㅓ ㅕ ㅗ ㅛ",
        "ㄱ + ㅏ = 가, ㄴ + ㅏ = 나 처럼 자음과 모음을 합쳐 글자를 만듭니다.",
        "ㅇㅇ 그 토토로 인형 귀엽더라 ㅎㅎ",
        "카지노 바로가기",
        "이들은 ‘ㅋㅏㅈㅣㄴㅗ’처럼 자모를 풀어 써 단속을 피하는 수법을 썼다고 경찰은 밝혔다.",
        "일반 공지사항입니다",
    })
    void ignoresChatJamoAndReports(String text) {
        assertNull(jamo.analyze(text), text);
    }

    @Test
    void decodedTextAndKeywordsAreExplained() {
        TextVerdict v = jamo.analyze("ㅋㅏㅈㅣㄴㅗ 바로가기");
        assertEquals("카지노 바로가기", v.decoded());
        assertTrue(v.keywords().contains("카지노"));
        assertFalse(v.reasons().isEmpty());
    }

    @Test
    void nfdHangulIsNotJamoDecomposition() {
        assertNull(jamo.analyze(Normalizer.normalize("카지노 바로가기 첫충 지급", Normalizer.Form.NFD)));
    }

    // ───────────────────────── HOMOGLYPH

    @Test
    void detectsLookalikeAds() {
        assertNotNull(homoglyph.analyze(fullwidth("mega-BET") + " 첫충 30" + fullwidth("%") + " 지급"));
        // 키릴 с, а, і
        assertNotNull(homoglyph.analyze("" + (char) 0x441 + (char) 0x430 + "s" + (char) 0x456 + "no 신규가입 이벤트"));
        assertNotNull(homoglyph.analyze("c4s1no 바로가기"));
        assertNotNull(homoglyph.analyze("7r지노 무료 쿠폰"));
        assertNotNull(homoglyph.analyze("바7r라 사이트 주소"));
        assertNotNull(homoglyph.analyze(fullwidth("LUCKY") + " 카지노 가입코드 문의"));
        // 수학용 굵은 글자 casino
        StringBuilder bold = new StringBuilder();
        for (char c : "casino".toCharArray()) {
            bold.appendCodePoint(0x1D41A + c - 'a');
        }
        assertNotNull(homoglyph.analyze(bold + " 라이브 중계"));
    }

    @Test
    void realJamoMixedInBelongsToJamoOnly() {
        // 진짜 낱자모가 섞인 ㅋr지노는 JAMO로만 본다.
        assertNull(homoglyph.analyze("ㅋr지노 주소 문의"));
        assertNotNull(jamo.analyze("ㅋr지노 주소 문의"));
        // 한 요소에 두 기법이 따로 있으면 둘 다 나온다.
        String both = fullwidth("bet") + " ㅋㅏㅈㅣㄴㅗ 동시 가입";
        assertNotNull(homoglyph.analyze(both));
        assertNotNull(jamo.analyze(both));
    }

    @Test
    void ignoresLegitimateSpecialCharacters() {
        String[] decoys = {
            fullwidth("KBS") + " 뉴스 특보",
            "할인율 " + fullwidth("30%") + " (부가세 포함)",
            "Привет, это обычный русский текст.",
            "㈜모의상사 ℡ 02-123-4567 · 면적 30㎡ · 무게 5㎏",
            "모델명 B3T-100 재고 5개, 품번 SL0T-7",
            "E스포츠 대회 1등 상금 안내, T머니 충전소",
            "α선과 β선의 차이, 파장 500㎚",
            "café 메뉴 안내 · résumé 작성법",
            "2026년 10월 1일 7시 LG전자 공개 채용 설명회",
            fullwidth("CASINO") + " 단속 강화, 경찰 불법 도박장 적발",
            "alphabet, internet, velvet " + fullwidth("alphabet"),
            "Ⅰ. 개요 Ⅱ. 추진 계획 Ⅲ. 기대 효과",
            "바카라 필승법 문의 카톡 baccara77",
            "카지노 바로가기",
            "bet365 casino",
        };
        for (String text : decoys) {
            assertNull(homoglyph.analyze(text), text);
        }
    }

    // ───────────────────────── 의미 게이트

    @ParameterizedTest
    @ValueSource(strings = {
        "먹튀검증 안전놀이터 상담 텔레그램 @promo_777",
        "무료 슬롯 체험 바로가기",
        "카지노 주소 바로가기",
        "바카라 사이트 추천",
        "카지노",
        "온라인 카지노",
        "출장 안마 문의 010-1234-5678",
        "best online casino bonus",
        "카지노사이트 추천 바로가기",
        // 숨긴 글 안에서 다시 자모로 풀어 쓴 것(받침 포함)도 읽어 낸다.
        "ㅅㅡㄹㄹㅗㅅ 무료 체험",
        "ㅁㅓㄱㅌㅟ 검증 업체",
    })
    void hiddenAdTextPassesGate(String text) {
        assertTrue(signals.assess(text).adLike(), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "카지노업 현황",
        "경찰, 불법 카지노 운영 일당 검거… 바카라 도박장 단속",
        "불법 도박 신고는 경찰청 112, 카지노 관련 피해 예방 캠페인",
        "카지노업 현황 사행산업 통계 불법도박 신고 안내 예방교육 상담",
        "성인 요금 3,000원 · 포커스 그룹 모집 · 타임슬롯 예약",
        "도서 대출 기간은 14일이며 성인 열람실은 22시까지",
        "숨겨진 탭: 민원 서식 내려받기, 상담 문의 안내",
        "본문 바로가기",
        "토토로 인형 전시회 안내",
    })
    void legitimateHiddenTextIsBlocked(String text) {
        assertFalse(signals.assess(text).adLike(), text);
    }

    @Test
    void longTextCountsOnlySignalsNearKeywords() {
        // 긴 메뉴: 주제어 하나와, 멀리 떨어진 "상담", "이벤트", 기관 전화번호
        String menu = "카지노업 현황 | 기관 소개 | 조직도 | 찾아오시는 길 | 정보공개 | 사전정보공표 | 공공데이터 개방 | 민원 안내 | 자주 묻는 질문 | "
            + "고객 상담 | 이벤트 | 대표전화 050-1234-5678 | www.mock.go.kr";
        AdSignals.Assessment a = signals.assess(menu);
        assertTrue(a.cues().isEmpty(), a.cues().toString());
        assertTrue(a.contacts().isEmpty(), a.contacts().toString());
        assertFalse(a.adLike());
    }

    @Test
    void publicDomainsAreNotAdContacts() {
        assertTrue(signals.assess("토토 문의 www.mois.go.kr").contacts().isEmpty());
        assertFalse(signals.assess("토토 문의 toto-777.com").contacts().isEmpty());
    }

    @Test
    void dictionaryExclusionsAndContainment() {
        KeywordDictionary d = signals.dictionary();
        assertTrue(d.scanKorean("포커스 그룹").isEmpty());
        assertEquals(1, d.scanKorean("포커 대회").size());
        // 먹튀검증 안의 먹튀는 따로 세지 않는다.
        assertEquals(1, d.scanKorean("먹튀검증").size());
        assertEquals("먹튀검증", d.scanKorean("먹튀검증").get(0).keyword().word());
    }
}
