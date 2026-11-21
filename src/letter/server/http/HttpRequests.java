package letter.server.http;

import com.sun.net.httpserver.HttpExchange;
import letter.domain.DiscordUser;
import letter.domain.LetterException;
import letter.protocol.FormCodec;
import letter.server.service.Authenticator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** HTTP 요청에서 값을 꺼내는 도구 모음. (본문 읽기, 로그인 확인) 모든 핸들러가 같은 방식으로 요청을 다루게 한다. */
public final class HttpRequests {
    private static final String BEARER_PREFIX = "Bearer ";

    private HttpRequests() {
    }

    /**
     * 요청 본문을 key=value&amp;... 형식으로 읽는다. maxBodyBytes까지만 읽고, 넘으면 413.
     *
     * @throws LetterException 형식이 올바르지 않으면 INVALID, 너무 크면 TOO_LARGE
     */
    public static Map<String, String> readForm(HttpExchange ex, int maxBodyBytes) {
        String body = new String(readBody(ex, maxBodyBytes), StandardCharsets.UTF_8);
        try {
            return FormCodec.decode(body);
        } catch (IllegalArgumentException e) {
            throw LetterException.invalid("요청 형식이 올바르지 않습니다.");
        }
    }

    /** 요청 주소의 ?뒤(쿼리)를 key=value&amp;... 형식으로 읽는다. */
    public static Map<String, String> readQuery(HttpExchange ex) {
        try {
            return FormCodec.decode(ex.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            throw LetterException.invalid("요청 형식이 올바르지 않습니다.");
        }
    }

    /** 요청 헤더의 세션 토큰({@code Authorization: Bearer ...}). 없으면 null. */
    public static String bearerToken(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    /**
     * 이 요청을 보낸 로그인 사용자를 확인한다.
     *
     * @throws LetterException 로그인하지 않았거나 만료되었으면 UNAUTHENTICATED
     */
    public static DiscordUser authenticate(HttpExchange ex, Authenticator authenticator) {
        return authenticator.authenticate(bearerToken(ex));
    }

    private static byte[] readBody(HttpExchange ex, int maxBodyBytes) {
        String length = ex.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            long declared;
            try {
                declared = Long.parseLong(length.trim());
            } catch (NumberFormatException e) {
                throw LetterException.invalid("잘못된 Content-Length 입니다.");
            }
            if (declared < 0) throw LetterException.invalid("잘못된 Content-Length 입니다.");
            if (declared > maxBodyBytes) throw tooLarge(maxBodyBytes);
        }
        try {
            byte[] data = ex.getRequestBody().readNBytes(maxBodyBytes + 1);
            if (data.length > maxBodyBytes) throw tooLarge(maxBodyBytes);
            return data;
        } catch (IOException e) {
            throw LetterException.invalid("요청 본문을 읽지 못했습니다.");
        }
    }

    private static LetterException tooLarge(int maxBodyBytes) {
        return LetterException.tooLarge("편지가 너무 깁니다. (요청 크기 제한: 약 " + (maxBodyBytes / 1000) + "KB)");
    }
}
