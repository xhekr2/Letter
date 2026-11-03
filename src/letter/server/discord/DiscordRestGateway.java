package letter.server.discord;

import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.server.discord.DiscordGatewayException.Reason;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 디스코드 REST API(v10)를 직접 호출하는 구현. 봇이 "보내는 일"만 하므로 게이트웨이(웹소켓) 접속은 필요 없다.
 * 봇 토큰은 요청 헤더에만 쓰이고 로그나 오류 메시지에는 절대 넣지 않는다.
 */
public class DiscordRestGateway implements DiscordGateway {
    public static final String DEFAULT_BASE_URL = "https://discord.com/api/v10";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final int CODE_CANNOT_DM_USER = 50007;
    private static final Pattern SNOWFLAKE = Pattern.compile("[0-9]{1,20}");

    private final HttpClient http;
    private final String baseUrl;
    private final String token;

    public DiscordRestGateway(String botToken) {
        this(botToken, DEFAULT_BASE_URL);
    }

    /** baseUrl을 바꿀 수 있는 것은 테스트에서 가짜 디스코드 서버를 쓰기 위해서다. */
    public DiscordRestGateway(String botToken, String baseUrl) {
        this.token = Objects.requireNonNull(botToken, "botToken");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public Optional<DiscordUser> findUser(DiscordId id) throws DiscordGatewayException {
        Response res = call("GET", "/users/" + id.value(), null);
        if (res.status() == 404) return Optional.empty();
        requireSuccess(res);

        Map<String, Object> user = asObject(res.body());
        String username = text(user, "username");
        String globalName = text(user, "global_name");
        String display;
        if (globalName != null && !globalName.isBlank()) {
            display = username == null ? globalName : globalName + " (@" + username + ")";
        } else {
            display = username == null ? null : "@" + username;
        }
        return Optional.of(new DiscordUser(id, display));
    }

    @Override
    public void sendDirectMessage(DiscordId to, String content) throws DiscordGatewayException {
        // 1) DM 채널 열기
        Response channel = call("POST", "/users/@me/channels", "{\"recipient_id\":" + MiniJson.quote(to.value()) + "}");
        requireSuccess(channel);
        String channelId = text(asObject(channel.body()), "id");
        if (channelId == null || !SNOWFLAKE.matcher(channelId).matches()) {
            throw new DiscordGatewayException(Reason.UNAVAILABLE, "디스코드 응답에 채널 ID가 없습니다.");
        }

        // 2) 메시지 보내기 (allowed_mentions: 본문에 @멘션이 있어도 아무도 호출하지 않는다)
        String payload = "{\"content\":" + MiniJson.quote(content) + ",\"allowed_mentions\":{\"parse\":[]}}";
        requireSuccess(call("POST", "/channels/" + channelId + "/messages", payload));
    }

    private Response call(String method, String path, String jsonBody) throws DiscordGatewayException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bot " + token)
                .header("User-Agent", "DiscordBot (letter-server, 1.0)");
        if (jsonBody == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> res = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(res.statusCode(), res.body());
        } catch (IOException e) {
            throw new DiscordGatewayException(Reason.UNAVAILABLE, "디스코드와 통신하지 못했습니다: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscordGatewayException(Reason.UNAVAILABLE, "디스코드 요청이 중단되었습니다.", e);
        }
    }

    /** 2xx가 아니면 원인에 맞는 예외로 바꾼다. */
    private static void requireSuccess(Response res) throws DiscordGatewayException {
        int status = res.status();
        if (status >= 200 && status < 300) return;

        if (status == 401) {
            throw new DiscordGatewayException(Reason.NOT_CONFIGURED, "디스코드 봇 토큰이 올바르지 않습니다. (HTTP 401)");
        }
        if (status == 403 || errorCode(res.body()) == CODE_CANNOT_DM_USER) {
            throw new DiscordGatewayException(Reason.DM_NOT_ALLOWED, "상대에게 DM을 보낼 수 없습니다. (HTTP " + status + ")");
        }
        if (status == 429) {
            throw new DiscordGatewayException(Reason.UNAVAILABLE, "디스코드 호출 한도를 넘었습니다. (HTTP 429)");
        }
        throw new DiscordGatewayException(Reason.UNAVAILABLE, "디스코드가 오류를 돌려주었습니다. (HTTP " + status + ")");
    }

    /** 디스코드 오류 본문의 "code" 값. 형식이 다르면 -1. */
    private static int errorCode(String body) {
        try {
            Object code = asObject(body).get("code");
            return code instanceof Double d ? d.intValue() : -1;
        } catch (IllegalArgumentException | DiscordGatewayException e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(String json) throws DiscordGatewayException {
        try {
            Object parsed = MiniJson.parse(json);
            if (parsed instanceof Map<?, ?> map) return (Map<String, Object>) map;
        } catch (IllegalArgumentException e) {
            throw new DiscordGatewayException(Reason.UNAVAILABLE, "디스코드 응답을 이해할 수 없습니다.", e);
        }
        throw new DiscordGatewayException(Reason.UNAVAILABLE, "디스코드 응답을 이해할 수 없습니다.");
    }

    private static String text(Map<String, Object> object, String key) {
        return object.get(key) instanceof String s ? s : null;
    }

    private record Response(int status, String body) {
    }
}
