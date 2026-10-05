package addetector;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 점검 한 번의 설정.
 *
 * @param entryUrl 사용자가 넣은 진입 URL(다듬기 전 그대로)
 * @param outDir result.json 등을 쓸 폴더
 * @param budget 시간 예산
 * @param workers 워커(브라우저) 수
 * @param maxPages 방문할 최대 페이지 수(0 = 제한 없음)
 * @param settleMillis 페이지 로드 뒤 늦게 붙는 내용(댓글 등)을 기다리는 최대 시간
 * @param headless 브라우저 창을 띄우지 않는가
 * @param extras 추가 유형(ETC)도 탐지해 result_extra.json을 쓰는가
 * @param topic meta.topic 값
 */
public record ScanOptions(
    String entryUrl,
    Path outDir,
    Duration budget,
    int workers,
    int maxPages,
    int settleMillis,
    boolean headless,
    boolean extras,
    String topic) {

    public static final String DEFAULT_TOPIC = "TOPIC";
    public static final int DEFAULT_SETTLE_MILLIS = 1500;

    public static ScanOptions of(String entryUrl, Path outDir) {
        return new ScanOptions(entryUrl, outDir, TimeBudget.DEFAULT, defaultWorkers(), 0, DEFAULT_SETTLE_MILLIS, true, true, DEFAULT_TOPIC);
    }

    public ScanOptions withBudget(Duration value) {
        return new ScanOptions(entryUrl, outDir, value, workers, maxPages, settleMillis, headless, extras, topic);
    }

    public ScanOptions withWorkers(int value) {
        return new ScanOptions(entryUrl, outDir, budget, Math.max(1, Math.min(8, value)), maxPages, settleMillis, headless, extras, topic);
    }

    public ScanOptions withMaxPages(int value) {
        return new ScanOptions(entryUrl, outDir, budget, workers, Math.max(0, value), settleMillis, headless, extras, topic);
    }

    public ScanOptions withSettleMillis(int value) {
        return new ScanOptions(entryUrl, outDir, budget, workers, maxPages, Math.max(0, value), headless, extras, topic);
    }

    public ScanOptions withHeadless(boolean value) {
        return new ScanOptions(entryUrl, outDir, budget, workers, maxPages, settleMillis, value, extras, topic);
    }

    public ScanOptions withExtras(boolean value) {
        return new ScanOptions(entryUrl, outDir, budget, workers, maxPages, settleMillis, headless, value, topic);
    }

    public ScanOptions withTopic(String value) {
        return new ScanOptions(entryUrl, outDir, budget, workers, maxPages, settleMillis, headless, extras, value);
    }

    /**
     * 워커 기본값. 워커 하나가 브라우저 하나(약 650MB)를 쓰므로 메모리와 코어 수에 맞춘다.
     * 운영 중인 사이트에 부담을 주지 않도록 많아도 3개까지만 쓴다.
     */
    public static int defaultWorkers() {
        int cores = Runtime.getRuntime().availableProcessors();
        long memoryGb = physicalMemoryBytes() / (1L << 30);
        int byMemory = memoryGb <= 0 ? 2 : memoryGb < 7 ? 1 : memoryGb < 15 ? 2 : 3;
        int byCores = cores <= 2 ? 1 : cores <= 4 ? 2 : 3;
        return Math.max(1, Math.min(byMemory, byCores));
    }

    private static long physicalMemoryBytes() {
        try {
            java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getTotalMemorySize();
            }
        } catch (RuntimeException | LinkageError e) {
            // 알 수 없으면 보수적으로 잡는다.
        }
        return 0;
    }
}
