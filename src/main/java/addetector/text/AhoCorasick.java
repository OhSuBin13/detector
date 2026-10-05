package addetector.text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 여러 키워드를 한 번에 찾는 Aho-Corasick 자동자. 만든 뒤에는 읽기 전용이라 스레드 간 공유할 수 있다. */
public final class AhoCorasick {
    /** 일치 구간. end는 포함하지 않는다. */
    public record Hit(int start, int end, int pattern) {}

    private final List<Map<Character, Integer>> next = new ArrayList<>();
    private final List<Integer> fail = new ArrayList<>();
    private final List<List<Integer>> out = new ArrayList<>();
    private final int[] lengths;

    public AhoCorasick(List<String> patterns) {
        lengths = new int[patterns.size()];
        newNode();
        for (int p = 0; p < patterns.size(); p++) {
            String pattern = patterns.get(p);
            lengths[p] = pattern.length();
            if (pattern.isEmpty()) {
                continue;
            }
            int node = 0;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                Integer to = next.get(node).get(c);
                if (to == null) {
                    to = newNode();
                    next.get(node).put(c, to);
                }
                node = to;
            }
            out.get(node).add(p);
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int child : next.get(0).values()) {
            fail.set(child, 0);
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            int node = queue.poll();
            for (Map.Entry<Character, Integer> e : next.get(node).entrySet()) {
                int child = e.getValue();
                int f = fail.get(node);
                while (f != 0 && !next.get(f).containsKey(e.getKey())) {
                    f = fail.get(f);
                }
                Integer target = next.get(f).get(e.getKey());
                fail.set(child, target != null && target != child ? target : 0);
                out.get(child).addAll(out.get(fail.get(child)));
                queue.add(child);
            }
        }
    }

    private int newNode() {
        next.add(new HashMap<>());
        fail.add(0);
        out.add(new ArrayList<>());
        return next.size() - 1;
    }

    public List<Hit> search(CharSequence text) {
        List<Hit> hits = new ArrayList<>();
        int node = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            while (node != 0 && !next.get(node).containsKey(c)) {
                node = fail.get(node);
            }
            Integer to = next.get(node).get(c);
            node = to != null ? to : 0;
            for (int p : out.get(node)) {
                hits.add(new Hit(i + 1 - lengths[p], i + 1, p));
            }
        }
        return hits;
    }
}
