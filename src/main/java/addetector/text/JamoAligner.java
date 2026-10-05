package addetector.text;

import addetector.text.JamoStream.Kind;
import addetector.text.JamoStream.Unit;
import java.util.ArrayList;
import java.util.List;

/** 자모 열({@link JamoStream})에 키워드의 자모 열을 맞춰 본다. */
public final class JamoAligner {
    private JamoAligner() {}

    /** 퍼지 매칭(ㄱ↔ㅋ 등)을 허용하는 최소 키워드 길이(낱자모 수). */
    private static final int FUZZY_MIN_LENGTH = 5;

    /**
     * @param keyword 키워드 번호
     * @param from 시작 칸
     * @param to 끝 칸(미포함)
     * @param jamo 낱자모로 적힌 칸 수
     * @param look 닮은꼴 칸 수
     * @param syl 정상 음절에서 나온 칸 수
     * @param fuzzy 비슷한 자모로 대신 맞춘 칸 수
     */
    public record Match(int keyword, int from, int to, int jamo, int look, int syl, int fuzzy, int srcStart, int srcEnd) {}

    /**
     * @param keywordJamo 키워드별 낱자모 열
     * @param allowFuzzy 비슷한 자모 한 칸 치환 허용
     */
    public static List<Match> find(JamoStream stream, List<String> keywordJamo, boolean allowFuzzy) {
        List<Unit> units = stream.units();
        List<Match> matches = new ArrayList<>();
        for (int start = 0; start < units.size(); start++) {
            Unit head = units.get(start);
            if (head.kind() == Kind.BARRIER || head.kind() == Kind.SYL && !head.first()) {
                continue;
            }
            for (int k = 0; k < keywordJamo.size(); k++) {
                Match m = matchAt(units, start, keywordJamo.get(k), k, allowFuzzy);
                if (m != null) {
                    matches.add(m);
                }
            }
        }
        return matches;
    }

    private static Match matchAt(List<Unit> units, int start, String keyword, int index, boolean allowFuzzy) {
        int len = keyword.length();
        if (start + len > units.size()) {
            return null;
        }
        int jamo = 0;
        int look = 0;
        int syl = 0;
        int fuzzy = 0;
        int maxFuzzy = allowFuzzy && len >= FUZZY_MIN_LENGTH ? 1 : 0;
        for (int j = 0; j < len; j++) {
            Unit u = units.get(start + j);
            if (u.kind() == Kind.BARRIER) {
                return null;
            }
            char want = keyword.charAt(j);
            if (u.cands().indexOf(want) < 0) {
                // 정상 음절은 글자 그대로만 맞춘다(가지 ≠ 카지).
                if (u.kind() == Kind.SYL || fuzzy >= maxFuzzy || !fuzzyEquals(u.cands(), want)) {
                    return null;
                }
                fuzzy++;
            }
            switch (u.kind()) {
                case JAMO -> jamo++;
                case LOOK -> look++;
                default -> syl++;
            }
        }
        Unit tail = units.get(start + len - 1);
        if (tail.kind() == Kind.SYL && !tail.last()) {
            return null;
        }
        return new Match(index, start, start + len, jamo, look, syl, fuzzy, units.get(start).src(), tail.src() + 1);
    }

    private static boolean fuzzyEquals(String cands, char want) {
        char target = HangulJamo.fuzzyClass(want);
        for (int i = 0; i < cands.length(); i++) {
            if (HangulJamo.fuzzyClass(cands.charAt(i)) == target) {
                return true;
            }
        }
        return false;
    }
}
