package letter.server.discord;

import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.LetterException;
import letter.server.discord.DiscordGatewayException.Reason;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 디스코드 OAuth2를 직접 호출하는 구현. 승인 코드 → 액세스 토큰 → 사용자 정보(`identify`)를 한 번 거치고,
 * 쓸모가 끝난 액세스 토큰은 바로 폐기한다. (우리는 "누구인지"만 알면 되고, 그 뒤에는 자체 세션을 쓴다.)
 * client secret, 승인 코드, 액세스 토큰은 로그나 오류 메시지에 절대 넣지 않는다.
 */
public class DiscordRestOAuth implements DiscordOAuth {
    public static final String DEFAULT_API_URL = "https://discord.com/api/v10";
    public static final String DEFAULT_AUTHORIZE_URL = "https://discord.com/oauth2/authorize";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String SCOPE = "identify";

    private final HttpClient http;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String apiUrl;
    private final String authorizeUrl;

    /** @param redirectUri 개발자 포털의 Redirects에 등록한 주소와 한 글자도 다르지 않아야 한다. */
    public DiscordRestOAuth(String clientId, String clientSecret, String redirectUri) {
        this(clientId, clientSecret, redirectUri, DEFAULT_API_URL, DEFAULT_AUTHORIZE_URL);
    }

    /** 주소를 바꿀 수 있는 것은 테스트에서 가짜 디스코드 서버를 쓰기 위해서다. */
    public DiscordRestOAuth(String clientId, String clientSecret, String redirectUri, String apiUrl, String authorizeUrl) {
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.redirectUri = Objects.requireNonNull(redirectUri, "redirectUri");
        this.apiUrl = stripTrailingSlash(apiUrl);
        this.authorizeUrl = authorizeUrl;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public String authorizeUrl(String state) {
        return authorizeUrl
                + "?response_type=code"
                + "&client_id=" + encode(clientId)
                + "&scope=" + SCOPE
                + "&state=" + encode(state)
                + "&redirect_uri=" + encode(redirectUri);
    }

    @Override
    public DiscordUser identify(String code) throws DiscordGatewayException {
        Map<String, String> tokenForm = new LinkedHashMap<>();
        tokenForm.put("grant_type", "authorization_code");
        tokenForm.put("code", code);
        tokenForm.put("redirect_uri", redirectUri);
        Response token = send(request("/oauth2/token")
                .header("Authorization", basicAuth())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(tokenForm), StandardCharsets.UTF_8)));

        if (token.status() == 400) {
            throw new DiscordGatewayException(Reason.INVALID_CODE, "디스코드가 로그인 승인 코드를 받아 주지 않았습니다. (HTTP 400)");
        }
        if (token.status() == 401) {
            throw new DiscordGatewayException(Reason.NOT_CONFIGURED, "디스코드 client id/secret이 올바르지 않습니다. (HTTP 401)");
        }
        requireSuccess(token);

        String accessToken = text(asObject(token.body()), "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new DiscordGatewayException(Reason.UNAVAILABLE, "디스코드 응답에 액세스 토큰이 없습니다.");
        }
        try {
            return readUser(accessToken);
        } finally {
            revokeQuietly(accessToken);
        }
    }

    private DiscordUser readUser(String accessToken) throws DiscordGatewayException {
        Response me = send(request("/users/@me").header("Authorization", "Bearer " + accessToken).GET());
        if (me.status() == 401) {
            throw new DiscordGatewayException(Reason.INVALID_CODE, "디스코드가 로그인 정보를 확인해 주지 않았습니다. (HTTP 401)");
        }
        requireSuccess(me);

        Map<String, Object> user = asObject(me.body());
        String id = text(user, "id");
        try {
            return new DiscordUser(DiscordId.of(id), DiscordNames.display(text(user, "username"), text(user, "global_name")));
        } catch (LetterException e) {
            throw new DiscordGatewayException(Reason.UNAVAILABLE, "디스코드 응답에 올바른 사용자 ID가 없습니다.", e);
        }
    }

    /** 로그인 확인이 끝난 액세스 토큰을 디스코드에서 지운다. 실패해도 로그인에는 영향이 없으므로 조용히 넘어간다. */
    private void revokeQuietly(String accessToken) {
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("token", accessToken);
            form.put("token_type_hint", "access_token");
            send(request("/oauth2/token/revoke")
                    .header("Authorization", basicAuth())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody(form), StandardCharsets.UTF_8)));
        } catch (DiscordGatewayException ignored) {
            // 토큰은 어차피 곧 만료된다
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(apiUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "DiscordBot (letter-server, 1.0)");
    }

    private Response send(HttpRequest.Builder request) throws DiscordGatewayException {
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

    private static void requireSuccess(Response res) throws DiscordGatewayException {
        if (res.status() >= 200 && res.status() < 300) return;
        if (res.status() == 429) {
            throw new DiscordGatewayException(Reason.UNAVAILABLE, "디스코드 호출 한도를 넘었습니다. (HTTP 429)");
        }
        throw new DiscordGatewayException(Reason.UNAVAILABLE, "디스코드가 오류를 돌려주었습니다. (HTTP " + res.status() + ")");
    }

    private String basicAuth() {
        String credentials = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static String formBody(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        return sb.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(String json) throws DiscordGatewayException {
        try {
            if (MiniJson.parse(json) instanceof Map<?, ?> map) return (Map<String, Object>) map;
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
