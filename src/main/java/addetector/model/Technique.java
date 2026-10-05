package addetector.model;

/** 공모전이 정한 은닉 기법 코드(붙임4). */
public enum Technique {
    HOMOGLYPH("닮은꼴 글자 위장"),
    JAMO("자모 분해"),
    TRANSPARENT("투명 텍스트"),
    OFFSCREEN("화면 밖 은닉"),
    /** 추가 제안 유형. result_extra.json에만 쓴다. */
    ETC("추가 제안");

    private final String label;

    Technique(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 적격평가 대상(result.json)인가. */
    public boolean core() {
        return this != ETC;
    }
}
