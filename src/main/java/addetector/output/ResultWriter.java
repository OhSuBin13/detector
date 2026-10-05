package addetector.output;

import addetector.model.Finding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 공모전 표준 스키마(붙임4)의 결과 파일을 쓴다. UTF-8(BOM 없음).
 * 파싱할 수 없는 파일은 그 회차 0점이므로, 임시 파일에 다 쓴 뒤 이름을 바꿔 반쯤 쓰인 파일이 남지 않게 한다.
 */
public final class ResultWriter {
    private ResultWriter() {}

    public static final String RESULT_FILE = "result.json";
    public static final String EXTRA_FILE = "result_extra.json";

    /**
     * @param entryUrl 입력값으로 받은 진입 URL(그대로)
     * @param elapsedSec 탐지 시작부터 완료까지 걸린 시간(초)
     */
    public record Meta(String topic, String entryUrl, OffsetDateTime startedAt, OffsetDateTime finishedAt, double elapsedSec, String toolVersion) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** @param findings 정렬된 검출 목록. id는 여기서 순서대로 붙인다. */
    public static void write(Path file, Meta meta, List<Finding> findings) throws IOException {
        writeAtomically(file, toJson(meta, findings));
    }

    public static String toJson(Meta meta, List<Finding> findings) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode m = root.putObject("meta");
        m.put("topic", meta.topic());
        m.put("entry_url", meta.entryUrl() == null ? "" : meta.entryUrl());
        m.put("started_at", timestamp(meta.startedAt()));
        m.put("finished_at", timestamp(meta.finishedAt()));
        m.put("elapsed_sec", Math.round(meta.elapsedSec() * 10) / 10.0);
        m.put("tool_version", meta.toolVersion());
        ArrayNode array = root.putArray("findings");
        int n = 0;
        for (Finding f : findings) {
            ObjectNode o = array.addObject();
            o.put("id", id(++n));
            o.put("url", f.url());
            o.put("is_violation", true);
            o.put("location", f.location());
            o.put("evidence_text", f.evidenceText() == null ? "" : f.evidenceText());
            o.put("technique", f.technique().name());
            if (f.extraType() != null) {
                o.put("extra_finding", f.extraType());
            }
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    public static String id(int n) {
        return String.format("f_%03d", n);
    }

    /** ISO 8601, 초 단위, UTC 오프셋 포함. 예: 2026-10-26T10:00:00+09:00 */
    public static String timestamp(OffsetDateTime time) {
        return time.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static void writeAtomically(Path file, String content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
