package addetector.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import addetector.output.OutputTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 모의 사이트 전체 점검: 핵심 4종 precision·recall 1.0, 첨부파일·파괴적 링크 미요청(설계 M3, G9). */
@Tag("browser")
class MockSiteTest {

    @Test
    void mockSiteIsDetectedPerfectly(@TempDir Path out) throws Exception {
        Path site = Path.of("mock-site");
        assertTrue(Files.isDirectory(site), "mock-site 폴더가 필요합니다(프로젝트 폴더에서 실행)");
        // strict: 오탐·미탐·금지된 요청이 하나라도 있으면 1
        assertEquals(0, Evaluate.mockSite(site, out, null, 2, true));
        assertTrue(OutputTest.validate(out.resolve("result.json")).isEmpty());
        assertTrue(Files.size(out.resolve("report.html")) > 10_000);
    }
}
