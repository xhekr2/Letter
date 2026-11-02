package letter.server;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Map;

/**
 * 서버 설정(불변). 환경변수 PORT, DATA_DIR, MAX_BODY_BYTES, RATE_LIMIT_PER_MIN, TIME_ZONE 로 바꿀 수 있고,
 * 첫 번째 실행 인자로 포트를 덮어쓸 수 있다.
 */
public record ServerConfig(int port, Path dataDir, int maxBodyBytes, int rateLimitPerMinute, ZoneId zone) {

    /** @throws IllegalArgumentException 설정 값이 올바르지 않을 때 */
    public static ServerConfig from(Map<String, String> env, String[] args) {
        int port = args.length > 0
                ? parseInt("포트 인자", args[0], 1, 65535)
                : intOf(env, "PORT", 8080, 1, 65535);
        Path dataDir = Paths.get(stringOf(env, "DATA_DIR", "data"));
        int maxBodyBytes = intOf(env, "MAX_BODY_BYTES", 2_000_000, 1_000, 100_000_000);
        int rateLimit = intOf(env, "RATE_LIMIT_PER_MIN", 60, 1, 100_000);

        ZoneId zone;
        try {
            zone = ZoneId.of(stringOf(env, "TIME_ZONE", "Asia/Seoul"));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("TIME_ZONE 값이 올바르지 않습니다: " + env.get("TIME_ZONE"));
        }
        return new ServerConfig(port, dataDir, maxBodyBytes, rateLimit, zone);
    }

    private static String stringOf(Map<String, String> env, String name, String def) {
        String v = env.get(name);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static int intOf(Map<String, String> env, String name, int def, int min, int max) {
        String v = env.get(name);
        return (v == null || v.isBlank()) ? def : parseInt(name, v, min, max);
    }

    private static int parseInt(String name, String value, int min, int max) {
        try {
            int n = Integer.parseInt(value.trim());
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 값이 올바르지 않습니다: '" + value + "' (" + min + " ~ " + max + ")");
        }
    }
}
