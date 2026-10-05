package addetector.output;

import addetector.model.Finding;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 담당자가 건마다 남긴 처리 상태와 메모(review.json).
 * 건은 (정규화 url, location, technique)로 식별하므로 다시 점검해도 같은 건이면 상태가 이어진다.
 * "조치 완료"로 둔 건이 다음 점검에서 또 나오면 재발견으로 표시한다.
 */
public final class ReviewStore {
    public static final String FILE = "review.json";
    public static final String NEW = "new";
    public static final String CONFIRMED = "confirmed";
    public static final String RESOLVED = "resolved";
    public static final String FALSE_POSITIVE = "false_positive";
    private static final Set<String> STATUSES = Set.of(NEW, CONFIRMED, RESOLVED, FALSE_POSITIVE);

    /**
     * @param status new(미확인) / confirmed(불법광고 확인) / resolved(조치 완료) / false_positive(오탐)
     * @param updatedAt 담당자가 마지막으로 고친 시각
     * @param lastSeenAt 마지막으로 탐지된 점검의 시작 시각
     * @param reappeared 조치 완료 뒤 다시 탐지되었는가
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String status, String memo, String updatedAt, String lastSeenAt, boolean reappeared) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private ReviewStore(Path file) {
        this.file = file;
    }

    /** 폴더의 review.json을 읽는다. 없거나 읽을 수 없으면 빈 상태로 시작한다. */
    public static ReviewStore open(Path dir) {
        ReviewStore store = new ReviewStore(dir.resolve(FILE));
        if (Files.isRegularFile(store.file)) {
            try {
                Map<String, Entry> loaded = MAPPER.readValue(Files.readAllBytes(store.file), new TypeReference<LinkedHashMap<String, Entry>>() {});
                loaded.forEach((k, v) -> {
                    if (k != null && v != null) {
                        store.entries.put(k, v);
                    }
                });
            } catch (IOException e) {
                // 손상된 파일은 버리고 새로 시작한다(점검 결과에는 영향이 없다).
            }
        }
        return store;
    }

    /** 건 식별자: 채점 단위 키의 해시. */
    public static String key(Finding finding) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(FindingCollector.key(finding).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized Map<String, Entry> all() {
        return new LinkedHashMap<>(entries);
    }

    /** 담당자가 상태·메모를 고친다. */
    public synchronized Entry set(String key, String status, String memo, String now) throws IOException {
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("알 수 없는 처리 상태: " + status);
        }
        Entry old = entries.get(key);
        Entry updated = new Entry(status, memo == null ? "" : memo, now, old == null ? null : old.lastSeenAt(), false);
        entries.put(key, updated);
        save();
        return updated;
    }

    /** 새 점검 결과를 반영한다: 이번에 나온 건의 마지막 탐지 시각을 갱신하고, 조치 완료였던 건은 재발견으로 표시한다. */
    public synchronized void onScan(Collection<Finding> findings, String scanStartedAt) throws IOException {
        boolean changed = false;
        for (Finding f : findings) {
            String key = key(f);
            Entry old = entries.get(key);
            if (old == null) {
                continue;
            }
            boolean again = RESOLVED.equals(old.status()) && !scanStartedAt.equals(old.lastSeenAt())
                && (old.updatedAt() == null || old.updatedAt().compareTo(scanStartedAt) <= 0);
            entries.put(key, new Entry(old.status(), old.memo(), old.updatedAt(), scanStartedAt, old.reappeared() || again));
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    private void save() throws IOException {
        ResultWriter.writeAtomically(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entries) + "\n");
    }
}
