package addetector.detect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 숨김 기법의 root 단위 거르기(메뉴, 길이·밀도)와 의미 게이트. 수집기 결과(JSON)를 직접 만들어 넣는다. */
class HiddenDetectorTest {
    private final OffscreenDetector offscreen = new OffscreenDetector(new AdSignals(KeywordDictionary.load(null)));

    /** display:none root(번호 1) 하나와 그 안의 글 하나. */
    private List<Detector.Candidate> detect(String text, int menu) {
        String t = text.replace("\"", "\\\"");
        String json = """
            {"truncated":false,
             "holders":[{"e":2,"t":"%s","ph":-1,"dn":1,"vis":-1,"clip":-1}],
             "roots":{"1":{"t":"%s","n":%d,"d":5,"p":-1,"m":%d}}}
            """.formatted(t, t, text.length(), menu);
        return offscreen.detect(new FrameContext(FrameSnapshot.parse(json)));
    }

    @Test
    void shortHiddenAdIsReported() {
        List<Detector.Candidate> found = detect("먹튀 피해 예방 안전놀이터 추천", 0);
        assertEquals(1, found.size());
        assertEquals(1, found.get(0).element());
    }

    @Test
    void hiddenMenuIsNotReported() {
        // 숨겨진 GNB 하위 메뉴의 "카지노업 현황"
        assertTrue(detect("카지노업 현황", 1).isEmpty());
    }

    @Test
    void longFaqAnswerIsNotReported() {
        // 사행산업 감독 기관 FAQ 답변(실사이트 오탐 사례)
        String faq = "사행산업사업자: 강원랜드 (내국인카지노) 구매상한의무 내용: 테이블 게임 1인 1회 10만원 이하 "
            + "(다만, 전체 테이블 중 1/2 범위에서 1인 1회 30만원 이하) 머신 게임 1인 1회 2천원 이하 (다만, 비디오 포커게임은 2천5백원 이하) "
            + "근거: 관광진흥법 시행규칙 [별표 10] 제4호 및 제5호. 사행산업 총량은 사행산업의 사회적 부작용 최소화와 건전발전을 위해 설정한 상한입니다.";
        assertTrue(faq.length() > AdSignals.COMPACT_TEXT);
        assertTrue(detect(faq, 0).isEmpty());
    }

    @Test
    void longKeywordDenseSpamIsReported() {
        String spam = "카지노사이트 바로가기 · 토토사이트 추천 · 바카라사이트 주소 · ".repeat(8);
        assertEquals(1, detect(spam, 0).size());
    }
}
