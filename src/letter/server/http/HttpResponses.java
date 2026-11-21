package letter.server.http;

import com.sun.net.httpserver.HttpExchange;
import letter.domain.LetterException;
import letter.protocol.AuthForm;
import letter.protocol.FormCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
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

    /**
     * 브라우저에 보여 줄 HTML 응답. 이 서버의 화면은 정적인 안내 페이지뿐이라, 스크립트·외부 자원·다른 사이트의 프레임 삽입을
     * 모두 막는다. (로그인 확인 화면을 다른 페이지 위에 몰래 겹쳐 놓는 클릭 유도를 막기 위해서다)
     */
    public static void sendHtml(HttpExchange ex, int status, String html) {
        var headers = ex.getResponseHeaders();
        headers.set("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        headers.set("X-Frame-Options", "DENY");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        try {
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            headers.set("Content-Type", "text/html; charset=UTF-8");
            headers.set("Cache-Control", "no-store");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException e) {
            LOG.warning("응답 전송 실패 (클라이언트 연결 끊김 등): " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    /**
     * 로그인이 필요하다는 응답(401). 비밀번호가 필요하다는 응답도 같은 401이라, 프로그램이 둘을 구분할 수 있게
     * {@code login=required} 항목을 함께 보낸다.
     */
    private static void sendLoginRequired(HttpExchange ex, String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("error", message);
        fields.put(AuthForm.LOGIN_REQUIRED_FIELD, AuthForm.LOGIN_REQUIRED_VALUE);
        ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        send(ex, statusOf(LetterException.Kind.UNAUTHENTICATED), FormCodec.encode(fields));
    }

    /**
     * 핸들러 본문을 실행하고, 발생한 오류를 알맞은 HTTP 응답으로 바꿔 준다. (모든 핸들러가 같은 방식으로 오류를 다루게 한다)
     * 업무 오류는 종류에 맞는 상태 코드로, 예상하지 못한 오류는 일반 메시지(500)로 응답한다.
     */
    public static void guard(HttpExchange ex, Runnable route) {
        try {
            route.run();
        } catch (LetterException e) {
            if (e.kind() == LetterException.Kind.STORAGE_FAILURE || e.kind() == LetterException.Kind.CORRUPTED) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            }
            if (e.kind() == LetterException.Kind.UNAUTHENTICATED) {
                sendLoginRequired(ex, e.getMessage());
            } else {
                sendError(ex, statusOf(e.kind()), e.getMessage());
            }
        } catch (RuntimeException e) {
            // 예상하지 못한 오류: 자세한 내용은 로그에만 남기고 사용자에게는 일반 메시지만 보낸다
            LOG.log(Level.SEVERE, "처리 중 예외", e);
            sendError(ex, 500, "서버 오류가 발생했습니다.");
        }
    }

    /** 오류 종류를 HTTP 상태 코드로 바꾼다. 도메인은 HTTP를 모르고, 이 변환은 HTTP 계층이 맡는다. */
    public static int statusOf(LetterException.Kind kind) {
        return switch (kind) {
            case INVALID -> 400;
            case UNAUTHENTICATED, PASSWORD_REQUIRED -> 401;
            case PASSWORD_WRONG -> 403;
            case NOT_FOUND -> 404;
            case TOO_LARGE -> 413;
            case RATE_LIMITED -> 429;
            case STORAGE_FAILURE, CORRUPTED -> 500;
            case UNAVAILABLE -> 503;
        };
    }
}
