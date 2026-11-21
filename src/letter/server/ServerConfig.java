package letter.server;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 서버 설정(불변). 환경변수 PORT, DATA_DIR, MAX_BODY_BYTES, RATE_LIMIT_PER_MIN, TIME_ZONE 로 바꿀 수 있고,
 * 첫 번째 실행 인자로 포트를 덮어쓸 수 있다.
 * DISCORD_BOT_TOKEN 이 있으면 디스코드 전송 기능이 켜진다. (없으면 꺼짐)
 * DISCORD_CLIENT_ID, DISCORD_CLIENT_SECRET, PUBLIC_URL 이 모두 있으면 디스코드 로그인이 켜진다.
 * (편지 봇의 애플리케이션 값이다. 로그인이 꺼져 있으면 아무도 로그인할 수 없어서 편지 기능도 쓸 수 없다.)
 *
 * @param discordClientId     로그인에 쓰는 디스코드 애플리케이션 ID. 없으면 null.
 * @param discordClientSecret 로그인에 쓰는 디스코드 애플리케이션 secret. 없으면 null.
 * @param publicUrl           이 서버가 바깥에서 보이는 주소(끝의 / 없음). 디스코드가 로그인 뒤 사용자를 돌려보낼 주소를 만드는 데 쓴다.
 */
public record ServerConfig(int port, Path dataDir, int maxBodyBytes, int rateLimitPerMinute, ZoneId zone,
                           String discordBotToken, String discordClientId, String discordClientSecret,
                           String publicUrl) {
    /** 로그인 완료 후 디스코드가 사용자를 돌려보내는 경로. 개발자 포털의 Redirects에 {@code PUBLIC_URL + 이 경로}를 등록해야 한다. */
    public static final String CALLBACK_PATH = "/auth/callback";
    private static final Pattern SNOWFLAKE = Pattern.compile("[0-9]{17,20}");

    /** 디스코드 로그인 설정이 없는 서버. (테스트용, 로그인은 꺼짐) */
    public ServerConfig(int port, Path dataDir, int maxBodyBytes, int rateLimitPerMinute, ZoneId zone,
                        String discordBotToken) {
        this(port, dataDir, maxBodyBytes, rateLimitPerMinute, zone, discordBotToken, null, null, null);
    }

    public boolean discordEnabled() {
        return discordBotToken != null;
    }

    public boolean loginEnabled() {
        return discordClientId != null && discordClientSecret != null && publicUrl != null;
    }

    /** 개발자 포털에 등록해야 하는 로그인 완료 주소. 로그인이 꺼져 있으면 null. */
    public String redirectUri() {
        return loginEnabled() ? publicUrl + CALLBACK_PATH : null;
    }

    /** 봇 토큰과 client secret이 로그에 찍히지 않도록 toString 에서 뺀다. */
    @Override
    public String toString() {
        return "ServerConfig[port=" + port + ", dataDir=" + dataDir + ", maxBodyBytes=" + maxBodyBytes
                + ", rateLimitPerMinute=" + rateLimitPerMinute + ", zone=" + zone
                + ", discord=" + (discordEnabled() ? "on" : "off")
                + ", login=" + (loginEnabled() ? "on" : "off") + "]";
    }

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
        String token = env.get("DISCORD_BOT_TOKEN");
        String discordToken = (token == null || token.isBlank()) ? null : token.trim();

        String clientId = optional(env, "DISCORD_CLIENT_ID");
        String clientSecret = optional(env, "DISCORD_CLIENT_SECRET");
        String publicUrl = optional(env, "PUBLIC_URL");
        requireAllOrNone(clientId, clientSecret, publicUrl);
        if (clientId != null && !SNOWFLAKE.matcher(clientId).matches()) {
            throw new IllegalArgumentException("DISCORD_CLIENT_ID 값이 올바르지 않습니다. (개발자 포털의 Application ID, 숫자 17~20자리)");
        }
        return new ServerConfig(port, dataDir, maxBodyBytes, rateLimit, zone, discordToken,
                clientId, clientSecret, publicUrl == null ? null : normalizePublicUrl(publicUrl));
    }

    /** 로그인 설정은 일부만 있으면 반쯤 켜진 상태가 되므로, 시작할 때 바로 알려 준다. */
    private static void requireAllOrNone(String clientId, String clientSecret, String publicUrl) {
        if (clientId == null && clientSecret == null && publicUrl == null) return;
        List<String> missing = new ArrayList<>();
        if (clientId == null) missing.add("DISCORD_CLIENT_ID");
        if (clientSecret == null) missing.add("DISCORD_CLIENT_SECRET");
        if (publicUrl == null) missing.add("PUBLIC_URL");
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("디스코드 로그인 설정이 덜 되어 있습니다. 빠진 값: " + String.join(", ", missing)
                    + " (로그인을 쓰지 않으려면 세 값을 모두 지워 주세요)");
        }
    }

    /** http(s) 주소만 허용하고 끝의 /는 뗀다. 쿼리나 #는 쓸 수 없다. */
    private static String normalizePublicUrl(String raw) {
        String u = raw;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        try {
            URI uri = URI.create(u);
            String scheme = uri.getScheme();
            if (uri.getHost() != null && scheme != null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return u;
            }
        } catch (IllegalArgumentException ignored) {
            // 아래에서 같은 메시지로 처리
        }
        throw new IllegalArgumentException("PUBLIC_URL 값이 올바르지 않습니다: '" + raw + "' (예: https://example.com)");
    }

    private static String optional(Map<String, String> env, String name) {
        String v = env.get(name);
        return (v == null || v.isBlank()) ? null : v.trim();
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
