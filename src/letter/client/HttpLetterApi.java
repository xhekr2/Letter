package letter.client;

import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LetterException;
import letter.domain.OpenedLetter;
import letter.domain.Receipt;
import letter.protocol.DiscordForm;
import letter.protocol.FormCodec;
import letter.protocol.LetterForm;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP로 편지 서버와 통신하는 {@link LetterApi} 구현. */
public class HttpLetterApi implements LetterApi {
    private static final int MAX_BODY_BYTES = 2_000_000;   // 서버 기본 제한과 동일
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final String baseUrl;

    /** @throws IllegalArgumentException 서버 주소가 올바르지 않을 때 */
    public HttpLetterApi(String url) {
        this.baseUrl = normalize(url);
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public LetterCode send(LetterDraft draft) throws ApiException {
        Reply reply = postLetter(draft, null, null);
        if (!reply.ok()) throw reply.toException();
        String code = reply.fields().get("code");
        if (code == null) throw unreadable();
        try {
            return LetterCode.of(code);
        } catch (LetterException e) {
            throw unreadable();
        }
    }

    @Override
    public Receipt sendToDiscord(LetterDraft draft, DiscordId to, DiscordId replyTo) throws ApiException {
        Reply reply = postLetter(draft, to, replyTo);
        if (!reply.ok()) throw reply.toException();
        try {
            return DiscordForm.receiptFromBody(reply.body());
        } catch (IllegalArgumentException e) {
            throw unreadable();
        }
    }

    @Override
    public DiscordUser findDiscordUser(DiscordId id) throws ApiException {
        Reply reply = call(HttpRequest.newBuilder(URI.create(baseUrl + "/discord/users/" + id.value()))
                .timeout(REQUEST_TIMEOUT).GET().build());

        if (!reply.ok()) throw reply.toException();
        try {
            return DiscordForm.userFromBody(reply.body());
        } catch (IllegalArgumentException e) {
            throw unreadable();
        }
    }

    @Override
    public Receipt reply(LetterCode original, String content) throws ApiException {
        String body = FormCodec.encode(Map.of("content", content));
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new ApiException("답장이 너무 깁니다. 조금 줄여서 다시 써 주세요.", false);
        }
        Reply reply = call(HttpRequest.newBuilder(URI.create(baseUrl + "/letters/" + original.value() + "/replies"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());

        if (!reply.ok()) throw reply.toException();
        try {
            return DiscordForm.receiptFromBody(reply.body());
        } catch (IllegalArgumentException e) {
            throw unreadable();
        }
    }

    @Override
    public OpenedLetter open(LetterCode code) throws ApiException {
        Reply reply = call(HttpRequest.newBuilder(URI.create(baseUrl + "/letters/" + code.value()))
                .timeout(REQUEST_TIMEOUT).GET().build());

        if (!reply.ok()) throw reply.toException();
        try {
            return new OpenedLetter(LetterForm.fromBody(reply.body()), LetterForm.replyableFromBody(reply.body()));
        } catch (IllegalArgumentException e) {
            throw unreadable();
        }
    }

    /** 편지 발송 요청. discordId가 있으면 서버가 그 사람에게 코드를 디스코드로 전달한다. */
    private Reply postLetter(LetterDraft draft, DiscordId discordId, DiscordId replyTo) throws ApiException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("author", draft.author());
        fields.put("content", draft.content());
        if (discordId != null) fields.put(DiscordForm.RECIPIENT_FIELD, discordId.value());
        if (replyTo != null) fields.put(DiscordForm.REPLY_TO_FIELD, replyTo.value());
        String body = FormCodec.encode(fields);
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new ApiException("편지가 너무 깁니다. 조금 줄여서 다시 써 주세요.", false);
        }

        return call(HttpRequest.newBuilder(URI.create(baseUrl + "/letters"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    /** 통신 실패를 예외가 아닌 Reply(status -1 / -2)로 바꿔서 위쪽 코드를 단순하게 만든다. */
    private Reply call(HttpRequest request) {
        try {
            HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, String> fields = parseOrEmpty(res.body());
            String error = fields.get("error");
            if (res.statusCode() != 200 && error == null) {
                error = "서버가 예상하지 못한 응답을 보냈습니다. (HTTP " + res.statusCode() + ")";
            }
            return new Reply(res.statusCode(), res.body(), fields, error);
        } catch (HttpConnectTimeoutException e) {
            return failure(-1, "서버에 연결하는 데 시간이 너무 오래 걸립니다.");
        } catch (HttpTimeoutException e) {
            return failure(-1, "서버 응답이 없습니다. (시간 초과)");
        } catch (ConnectException e) {
            return failure(-1, "서버에 연결할 수 없습니다. 주소(" + baseUrl + ")와 서버 상태를 확인해 주세요.");
        } catch (IOException e) {
            return failure(-1, "통신 중 오류가 발생했습니다: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(-2, "요청이 중단되었습니다.");
        }
    }

    private static Reply failure(int status, String message) {
        return new Reply(status, "", Collections.emptyMap(), message);
    }

    private static ApiException unreadable() {
        return new ApiException("서버 응답을 이해할 수 없습니다.", false);
    }

    private static Map<String, String> parseOrEmpty(String body) {
        try {
            return FormCodec.decode(body);
        } catch (IllegalArgumentException e) {
            return Collections.emptyMap();   // HTML 오류 페이지 등 형식이 다른 응답
        }
    }

    private static String normalize(String url) {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        try {
            URI uri = URI.create(u);
            String scheme = uri.getScheme();
            if (uri.getHost() != null && scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return u;
            }
        } catch (IllegalArgumentException ignored) {
            // 아래에서 같은 메시지로 처리
        }
        throw new IllegalArgumentException("서버 주소가 올바르지 않습니다: " + url);
    }

    /** 서버 응답 (status -1: 통신 실패, -2: 중단됨) */
    private record Reply(int status, String body, Map<String, String> fields, String error) {
        boolean ok() {
            return status == 200;
        }

        boolean retryable() {
            return status == -1 || status == 429 || status >= 500;
        }

        ApiException toException() {
            return new ApiException(error != null ? error : "서버 응답을 이해할 수 없습니다.", retryable());
        }
    }
}
