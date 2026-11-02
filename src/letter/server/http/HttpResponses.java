package letter.server.http;

import com.sun.net.httpserver.HttpExchange;
import letter.domain.LetterException;
import letter.protocol.FormCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;

/** HTTP 응답을 보내는 도구 모음. */
public final class HttpResponses {
    private static final Logger LOG = Logger.getLogger(HttpResponses.class.getName());

    private HttpResponses() {
    }

    public static void send(HttpExchange ex, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException e) {
            LOG.warning("응답 전송 실패 (클라이언트 연결 끊김 등): " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    public static void sendError(HttpExchange ex, int status, String message) {
        send(ex, status, FormCodec.encode(Map.of("error", message)));
    }

    /** 오류 종류를 HTTP 상태 코드로 바꾼다. 도메인은 HTTP를 모르고, 이 변환은 HTTP 계층이 맡는다. */
    public static int statusOf(LetterException.Kind kind) {
        return switch (kind) {
            case INVALID -> 400;
            case NOT_FOUND -> 404;
            case TOO_LARGE -> 413;
            case RATE_LIMITED -> 429;
            case STORAGE_FAILURE, CORRUPTED -> 500;
        };
    }
}
