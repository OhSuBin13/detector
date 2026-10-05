package addetector.detect;

import addetector.model.Finding;
import addetector.model.Technique;
import java.util.List;

/** 기법 하나의 탐지기. Playwright 객체처럼 워커(스레드)마다 한 벌씩 만든다. */
public interface Detector {

    /**
     * 보고 후보. 요소 번호는 {@link SelectorService}가 선택자로 바꾼다.
     *
     * @param element 보고할 요소 번호
     * @param evidence 근거 원문
     * @param extraType ETC일 때 추가 유형 이름
     */
    record Candidate(int element, Technique technique, String evidence, String extraType, Finding.Detail detail) {}

    List<Candidate> detect(FrameContext context);
}
