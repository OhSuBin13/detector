package addetector;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** 도구 버전(meta.tool_version). 빌드 버전에서 온다. */
public final class Version {
    private Version() {}

    private static final String VALUE = load();

    public static String get() {
        return VALUE;
    }

    private static String load() {
        try (InputStream in = Version.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version", "").strip();
                if (!v.isEmpty() && !v.startsWith("$")) {
                    return v;
                }
            }
        } catch (IOException e) {
            // 버전을 못 읽어도 점검은 계속한다.
        }
        return "0.0.0";
    }
}
