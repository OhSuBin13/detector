package addetector.output;

import addetector.detect.FrameSnapshot;
import addetector.model.Finding;
import addetector.model.Technique;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사람용 부가 파일: report.json(근거·통계)과 report.html(서버 없이 여는 결과 화면).
 * 채점 파일(result.json)과 분리되어 있어, 여기서 실패해도 채점 파일과 종료 코드는 영향을 받지 않는다.
 */
public final class ReportWriter {
    private ReportWriter() {}

    public static final String REPORT_JSON = "report.json";
    public static final String REPORT_HTML = "report.html";
    private static final String DATA_PLACEHOLDER = "/*__REPORT_DATA__*/null";
    /** 같은 위치·같은 문구가 이 수 이상의 페이지에 반복되면 공통 영역(머리글·바닥글·템플릿) 변조를 의심한다. */
    private static final int SHARED_MIN_PAGES = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param status 종료 상태(정상 완료 / 시간 예산 도달 / …)
     * @param running 점검이 아직 진행 중인가(결과 화면의 실시간 표시용)
     */
    public record RunInfo(
        ResultWriter.Meta meta, String status, String message, int visited, int failed, Gaps gaps, int queued, String browser, int workers,
        boolean running) {

        public RunInfo(ResultWriter.Meta meta, String status, String message, int visited, int failed, int queued, String browser, int workers, boolean running) {
            this(meta, status, message, visited, failed, Gaps.NONE, queued, browser, workers, running);
        }
    }

    /**
     * 실패는 아니지만 점검이 빈 곳.
     *
     * @param uninspected 열었지만 본문 frame을 점검하지 못한 페이지 수
     * @param truncated 요소·시간 상한 때문에 일부만 점검한 페이지 수
     * @param deferred 사이트가 거부하거나 연결이 끊겨 미뤄 두었다가 끝내 다시 방문하지 못한 페이지 수
     * @param files 열어 보니 내려받는 파일이었던 주소 수
     */
    public record Gaps(int uninspected, int truncated, int deferred, int files) {
        public static final Gaps NONE = new Gaps(0, 0, 0, 0);
    }

    public static ObjectNode toNode(RunInfo info, List<Finding> core, List<Finding> extra, Map<String, ReviewStore.Entry> review) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode m = root.putObject("meta");
        ResultWriter.Meta meta = info.meta();
        m.put("tool_version", meta.toolVersion());
        m.put("entry_url", meta.entryUrl() == null ? "" : meta.entryUrl());
        m.put("started_at", ResultWriter.timestamp(meta.startedAt()));
        m.put("finished_at", ResultWriter.timestamp(meta.finishedAt()));
        m.put("elapsed_sec", Math.round(meta.elapsedSec() * 10) / 10.0);
        m.put("status", info.status());
        m.put("message", info.message() == null ? "" : info.message());
        m.put("running", info.running());
        m.put("pages_visited", info.visited());
        m.put("pages_failed", info.failed());
        m.put("pages_uninspected", info.gaps().uninspected());
        m.put("pages_truncated", info.gaps().truncated());
        m.put("pages_deferred", info.gaps().deferred());
        m.put("files_skipped", info.gaps().files());
        m.put("pages_queued", info.queued());
        m.put("browser", info.browser());
        m.put("workers", info.workers());

        // 같은 위치·기법·문구가 여러 페이지에 반복되는가
        Map<String, Set<String>> pagesByShape = new HashMap<>();
        for (List<Finding> list : List.of(core, extra)) {
            for (Finding f : list) {
                pagesByShape.computeIfAbsent(shape(f), k -> new HashSet<>()).add(f.url());
            }
        }
        ArrayNode array = root.putArray("findings");
        int n = 0;
        for (Finding f : core) {
            add(array, f, ResultWriter.id(++n), pagesByShape);
        }
        n = 0;
        for (Finding f : extra) {
            add(array, f, ResultWriter.id(++n), pagesByShape);
        }
        ObjectNode r = root.putObject("review");
        review.forEach((key, entry) -> r.set(key, MAPPER.valueToTree(entry)));
        return root;
    }

    private static String shape(Finding f) {
        return f.technique() + "\n" + f.extraType() + "\n" + f.location() + "\n" + f.evidenceText();
    }

    private static void add(ArrayNode array, Finding f, String id, Map<String, Set<String>> pagesByShape) {
        ObjectNode o = array.addObject();
        o.put("id", id);
        o.put("key", ReviewStore.key(f));
        o.put("url", f.url());
        o.put("location", f.location());
        o.put("technique", f.technique().name());
        o.put("technique_label", f.technique().label());
        if (f.extraType() != null) {
            o.put("extra_finding", f.extraType());
        }
        o.put("evidence_text", f.evidenceText());
        Finding.Detail d = f.detail();
        o.put("decoded", d == null ? "" : d.decoded());
        o.put("method", d == null ? "" : d.method());
        ArrayNode reasons = o.putArray("reasons");
        ArrayNode keywords = o.putArray("keywords");
        if (d != null) {
            d.reasons().forEach(reasons::add);
            d.keywords().forEach(keywords::add);
        }
        int pages = pagesByShape.getOrDefault(shape(f), Set.of()).size();
        o.put("shared_pages", pages >= SHARED_MIN_PAGES ? pages : 0);
        o.put("action", action(f.technique(), pages >= SHARED_MIN_PAGES));
    }

    /** 담당자에게 권하는 조치. */
    static String action(Technique technique, boolean shared) {
        String base = switch (technique) {
            case HOMOGLYPH -> "닮은꼴 글자로 금칙어 필터를 피한 문구입니다. 게시글·댓글이면 삭제하고 작성 계정·IP를 차단하세요. "
                + "금칙어 필터에 유니코드 정규화(NFKC)와 닮은꼴 글자 치환을 적용하면 재발을 줄일 수 있습니다.";
            case JAMO -> "자음·모음을 풀어 써서 금칙어 필터를 피한 문구입니다. 게시글이면 삭제하고 작성 계정·IP를 차단하세요.";
            case TRANSPARENT, OFFSCREEN, ETC -> "화면에 보이지 않게 심어 둔 문구입니다. 게시글 본문이면 삭제하고 작성 계정·IP를 차단하세요. "
                + "게시글이 아닌 곳(템플릿·스크립트)에 있다면 웹셸·관리자 계정 탈취로 인한 변조일 수 있으니 소스 변경 이력과 서버를 점검하세요.";
        };
        return shared
            ? base + " 같은 문구가 여러 페이지의 같은 위치에 반복됩니다. 머리글·바닥글 등 공통 영역(템플릿)이 변조되었을 가능성이 높습니다."
            : base;
    }

    /** report.json과 report.html을 쓴다. */
    public static void write(Path dir, ObjectNode report) throws IOException {
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";
        ResultWriter.writeAtomically(dir.resolve(REPORT_JSON), json);
        ResultWriter.writeAtomically(dir.resolve(REPORT_HTML), html(report));
    }

    /**
     * 결과 화면 HTML.
     *
     * @param report 화면에 심을 데이터. null이면 서버 모드(화면이 /api에서 받아 온다).
     */
    public static String html(ObjectNode report) throws IOException {
        String template = FrameSnapshot.resource("/ui/app.html");
        if (report == null) {
            return template;
        }
        // </script>로 끊기지 않게, 그리고 HTML 주석 시작으로 읽히지 않게 한다.
        String json = MAPPER.writeValueAsString(report).replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        return template.replace(DATA_PLACEHOLDER, json);
    }
}
