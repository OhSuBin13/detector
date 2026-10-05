package addetector.text;

import java.util.ArrayList;
import java.util.List;

/**
 * 텍스트에서 라틴 글자처럼 보이는 토큰을 뽑아 "뼈대"(닮은꼴을 걷어낸 ASCII 소문자열)를 만든다.
 * 예: {@code ｍｅｇａ－ＢＥＴ} → mega-bet, {@code саsіno}(키릴 섞임) → casino, {@code c4s1no} → casino
 */
public final class LatinSkeleton {
    private LatinSkeleton() {}

    public enum Kind {
        /** ASCII 영문자 */
        PLAIN,
        /** 전각·수학용 영숫자 등 NFKC로 접히는 글자 */
        COMPAT,
        /** 키릴·그리스 등 다른 문자 체계의 닮은꼴 */
        SCRIPT,
        /** 발음 구별 기호가 붙은 라틴 글자 */
        DIACRITIC,
        /** 글자를 흉내 낼 수 있는 숫자·기호 */
        LEET,
        /** 글자를 흉내 내지 않는 숫자 */
        DIGIT,
        /** 토큰 안 구분자(-, _, .) */
        SEP,
        /** 라틴 글자로 읽을 수 없는 다른 문자 체계의 글자. 낱말 경계 판단에만 쓴다. */
        OTHER
    }

    /** 뼈대의 한 칸. cands는 이 칸이 될 수 있는 소문자들(구분자·숫자는 원래 글자). */
    public record Cell(String cands, Kind kind, int src) {
        public boolean disguised() {
            return kind == Kind.COMPAT || kind == Kind.SCRIPT || kind == Kind.DIACRITIC;
        }
    }

    public record Token(int start, int end, List<Cell> cells) {
        /** 가장 그럴듯한 한 가지 읽기(leet는 첫 후보). 표시용. */
        public String reading() {
            StringBuilder sb = new StringBuilder();
            for (Cell c : cells) {
                sb.append(c.kind() == Kind.LEET || c.kind() == Kind.DIGIT ? c.cands().charAt(c.cands().length() - 1) : c.cands().charAt(0));
            }
            return sb.toString();
        }

        public int count(Kind kind) {
            int n = 0;
            for (Cell c : cells) {
                if (c.kind() == kind) {
                    n++;
                }
            }
            return n;
        }

        public int letters() {
            int n = 0;
            for (Cell c : cells) {
                if (c.kind() != Kind.SEP && c.kind() != Kind.DIGIT && c.kind() != Kind.LEET && c.kind() != Kind.OTHER) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * 일치 구간(칸 번호, to 미포함).
     *
     * @param disguised 닮은꼴 글자로 맞춘 칸 수
     * @param leet 숫자·기호로 맞춘 칸 수
     * @param leetInside 그중 낱말 안쪽(첫 칸·끝 칸이 아닌 곳)에 있는 것. 끝에 붙은 숫자(baccara77)는 치환이 아니라 그냥 숫자일 때가 많다.
     */
    public record Match(String keyword, int from, int to, int disguised, int leet, int leetInside) {}

    public static List<Token> tokens(String text) {
        List<Token> tokens = new ArrayList<>();
        List<Cell> cells = new ArrayList<>();
        int tokenStart = -1;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            Cell cell = classify(cp, i);
            if (cell != null) {
                if (tokenStart < 0) {
                    tokenStart = i;
                }
                cells.add(cell);
            } else if (tokenStart >= 0) {
                addToken(tokens, tokenStart, i, cells);
                cells = new ArrayList<>();
                tokenStart = -1;
            }
            i += len;
        }
        if (tokenStart >= 0) {
            addToken(tokens, tokenStart, text.length(), cells);
        }
        return tokens;
    }

    private static void addToken(List<Token> tokens, int start, int end, List<Cell> cells) {
        // 앞뒤 구분자는 토큰에 넣지 않는다.
        int from = 0;
        int to = cells.size();
        while (from < to && cells.get(from).kind() == Kind.SEP) {
            from++;
        }
        while (to > from && cells.get(to - 1).kind() == Kind.SEP) {
            to--;
        }
        if (from < to) {
            List<Cell> trimmed = List.copyOf(cells.subList(from, to));
            tokens.add(new Token(trimmed.get(0).src(), end, trimmed));
        }
    }

    private static Cell classify(int cp, int src) {
        if (cp < 0x80) {
            char c = (char) cp;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                return new Cell(String.valueOf(Character.toLowerCase(c)), Kind.PLAIN, src);
            }
            if (c >= '0' && c <= '9' || c == '@' || c == '$' || c == '!') {
                String leet = LookalikeTable.leetToLatin(c);
                return leet.isEmpty() ? new Cell(String.valueOf(c), Kind.DIGIT, src) : new Cell(leet + c, Kind.LEET, src);
            }
            if (c == '-' || c == '_' || c == '.') {
                return new Cell(String.valueOf(c), Kind.SEP, src);
            }
            return null;
        }
        char script = LookalikeTable.scriptToLatin(cp);
        if (script != 0) {
            return new Cell(String.valueOf(script), Kind.SCRIPT, src);
        }
        char compat = LookalikeTable.compatToAscii(cp);
        if (compat != 0) {
            if (Character.isLetter(compat)) {
                return new Cell(String.valueOf(Character.toLowerCase(compat)), Kind.COMPAT, src);
            }
            String leet = LookalikeTable.leetToLatin(compat);
            return leet.isEmpty() ? new Cell(String.valueOf(compat), Kind.DIGIT, src) : new Cell(leet + compat, Kind.LEET, src);
        }
        char base = LookalikeTable.stripDiacritic(cp);
        if (base != 0) {
            return new Cell(String.valueOf(base), Kind.DIACRITIC, src);
        }
        // 전각 하이픈·가운뎃점 등
        if (cp == 0xFF0D || cp == 0x2010 || cp == 0x2011 || cp == 0x2013 || cp == 0xFF3F || cp == 0xFF0E || cp == 0x00B7) {
            return new Cell("-", Kind.SEP, src);
        }
        // 닮은꼴이 아닌 외국 글자(Привет의 П, и)는 낱말의 일부로 남겨, 그 낱말 속 조각(вет ≈ bet)이 따로 맞지 않게 한다.
        if (Character.isLetter(cp)) {
            Character.UnicodeScript us = Character.UnicodeScript.of(cp);
            if (us != Character.UnicodeScript.HANGUL && us != Character.UnicodeScript.HAN
                && us != Character.UnicodeScript.HIRAGANA && us != Character.UnicodeScript.KATAKANA) {
                return new Cell("", Kind.OTHER, src);
            }
        }
        return null;
    }

    /**
     * 토큰에서 키워드가 나오는 자리를 찾는다. 구분자는 건너뛴다.
     * 짧은 키워드는 낱말 경계에 걸려야 한다(alphabet 속 bet 방지).
     */
    public static List<Match> find(Token token, String keyword) {
        List<Match> matches = new ArrayList<>();
        List<Cell> cells = token.cells();
        // 라틴 글자가 하나도 없는 외국어 낱말(러시아어 등)은 낱말 전체가 키워드일 때만 인정한다.
        boolean foreignWord = token.count(Kind.PLAIN) == 0 && token.count(Kind.COMPAT) == 0;
        for (int start = 0; start < cells.size(); start++) {
            if (cells.get(start).kind() == Kind.SEP) {
                continue;
            }
            int pos = start;
            int k = 0;
            int disguised = 0;
            int leet = 0;
            int leetInside = 0;
            while (k < keyword.length() && pos < cells.size()) {
                Cell cell = cells.get(pos);
                if (cell.kind() == Kind.SEP) {
                    // 글자 사이마다 끼운 구분자(c.a.s.i.n.o)도 건너뛴다.
                    pos++;
                    continue;
                }
                if (cell.kind() == Kind.DIGIT || cell.kind() == Kind.OTHER || cell.cands().indexOf(keyword.charAt(k)) < 0) {
                    break;
                }
                if (cell.disguised()) {
                    disguised++;
                } else if (cell.kind() == Kind.LEET) {
                    leet++;
                    if (k > 0 && k < keyword.length() - 1) {
                        leetInside++;
                    }
                }
                k++;
                pos++;
            }
            if (k == keyword.length() && boundaryOk(cells, start, pos, keyword.length())
                && (!foreignWord || wholeToken(cells, start, pos))) {
                matches.add(new Match(keyword, start, pos, disguised, leet, leetInside));
            }
        }
        return matches;
    }

    private static boolean boundaryOk(List<Cell> cells, int from, int to, int keywordLength) {
        boolean leftEdge = from == 0 || !isLetter(cells.get(from - 1));
        boolean rightEdge = to == cells.size() || !isLetter(cells.get(to));
        if (keywordLength <= 3) {
            return leftEdge && rightEdge;
        }
        if (keywordLength <= 5) {
            return leftEdge || rightEdge;
        }
        return true;
    }

    private static boolean wholeToken(List<Cell> cells, int from, int to) {
        for (int i = 0; i < cells.size(); i++) {
            if ((i < from || i >= to) && cells.get(i).kind() != Kind.SEP) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLetter(Cell cell) {
        // 숫자·기호는 글자를 흉내 낼 수 있어도 경계로 본다(ＢＥＴ３６５의 BET).
        return cell.kind() == Kind.PLAIN || cell.kind() == Kind.OTHER || cell.disguised();
    }
}
