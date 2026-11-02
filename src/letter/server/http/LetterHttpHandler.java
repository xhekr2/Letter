package letter.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LetterException;
import letter.protocol.FormCodec;
import letter.protocol.LetterForm;
import letter.server.service.LetterService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP 요청을 서비스 호출로 바꿔 주는 어댑터.
 *   POST /letters        -> 편지 발송
 *   GET  /letters/{code} -> 편지 조회
 */
public class LetterHttpHandler implements HttpHandler {
    private static final Logger LOG = Logger.getLogger(LetterHttpHandler.class.getName());
    private static final String COLLECTION = "/letters";
    private static final String ITEM_PREFIX = "/letters/";

    private final LetterService service;
    private final int maxBodyBytes;

    public LetterHttpHandler(LetterService service, int maxBodyBytes) {
        this.service = service;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public void handle(HttpExchange ex) {
        try {
            route(ex);
        } catch (LetterException e) {
            if (e.kind() == LetterException.Kind.STORAGE_FAILURE || e.kind() == LetterException.Kind.CORRUPTED) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            }
            HttpResponses.sendError(ex, HttpResponses.statusOf(e.kind()), e.getMessage());
        } catch (RuntimeException e) {
            // 예상하지 못한 오류: 자세한 내용은 로그에만 남기고 사용자에게는 일반 메시지만 보낸다
            LOG.log(Level.SEVERE, "처리 중 예외", e);
            HttpResponses.sendError(ex, 500, "서버 오류가 발생했습니다.");
        }
    }

    private void route(HttpExchange ex) {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        if (path.equals(COLLECTION)) {
            if (!method.equals("POST")) {
                HttpResponses.sendError(ex, 405, "이 주소는 POST만 지원합니다.");
                return;
            }
            create(ex);
        } else if (path.startsWith(ITEM_PREFIX)) {
            if (!method.equals("GET")) {
                HttpResponses.sendError(ex, 405, "이 주소는 GET만 지원합니다.");
                return;
            }
            read(ex, path.substring(ITEM_PREFIX.length()));
        } else {
            throw LetterException.notFound("지원하지 않는 주소입니다.");
        }
    }

    private void create(HttpExchange ex) {
        String body = new String(readBody(ex), StandardCharsets.UTF_8);

        Map<String, String> form;
        try {
            form = FormCodec.decode(body);
        } catch (IllegalArgumentException e) {
            throw LetterException.invalid("요청 형식이 올바르지 않습니다.");
        }

        LetterDraft draft = new LetterDraft(form.get("author"), form.get("content"));
        LetterCode code = service.send(draft);
        HttpResponses.send(ex, 200, FormCodec.encode(Map.of("code", code.value())));
    }

    private void read(HttpExchange ex, String rawCode) {
        Letter letter = service.read(LetterCode.of(rawCode));
        HttpResponses.send(ex, 200, LetterForm.toBody(letter));
    }

    /** 요청 본문을 maxBodyBytes까지만 읽는다. 넘으면 413. */
    private byte[] readBody(HttpExchange ex) {
        String length = ex.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            long declared;
            try {
                declared = Long.parseLong(length.trim());
            } catch (NumberFormatException e) {
                throw LetterException.invalid("잘못된 Content-Length 입니다.");
            }
            if (declared < 0) throw LetterException.invalid("잘못된 Content-Length 입니다.");
            if (declared > maxBodyBytes) throw tooLarge();
        }
        try {
            byte[] data = ex.getRequestBody().readNBytes(maxBodyBytes + 1);
            if (data.length > maxBodyBytes) throw tooLarge();
            return data;
        } catch (IOException e) {
            throw LetterException.invalid("요청 본문을 읽지 못했습니다.");
        }
    }

    private LetterException tooLarge() {
        return LetterException.tooLarge("편지가 너무 깁니다. (요청 크기 제한: 약 " + (maxBodyBytes / 1000) + "KB)");
    }
}
