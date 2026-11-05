package letter.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import letter.domain.DiscordId;
import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LetterException;
import letter.domain.Receipt;
import letter.protocol.DiscordForm;
import letter.protocol.FormCodec;
import letter.protocol.LetterForm;
import letter.server.service.LetterDeliveryService;
import letter.server.service.LetterService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP 요청을 서비스 호출로 바꿔 주는 어댑터.
 *   POST /letters                -> 편지 발송 (discordId 항목이 있으면 그 사람에게 디스코드 봇이 코드를 전달,
 *                                   거기에 replyTo 항목을 더하면 그 사람이 답장을 받는다)
 *   GET  /letters/{code}         -> 편지 조회 (답장할 수 있으면 replyable=true. 답장받을 ID는 절대 내려주지 않는다)
 *   POST /letters/{code}/replies -> 답장 발송 (답장 코드는 원본에 저장된 디스코드 ID로 전달)
 */
public class LetterHttpHandler implements HttpHandler {
    private static final String COLLECTION = "/letters";
    private static final String ITEM_PREFIX = "/letters/";
    private static final String REPLIES_SUFFIX = "/replies";

    private final LetterService service;
    private final LetterDeliveryService delivery;
    private final int maxBodyBytes;

    public LetterHttpHandler(LetterService service, LetterDeliveryService delivery, int maxBodyBytes) {
        this.service = service;
        this.delivery = delivery;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public void handle(HttpExchange ex) {
        HttpResponses.guard(ex, () -> route(ex));
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
        } else if (path.startsWith(ITEM_PREFIX) && path.endsWith(REPLIES_SUFFIX)) {
            if (!method.equals("POST")) {
                HttpResponses.sendError(ex, 405, "이 주소는 POST만 지원합니다.");
                return;
            }
            reply(ex, path.substring(ITEM_PREFIX.length(), path.length() - REPLIES_SUFFIX.length()));
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
        Map<String, String> form = readForm(ex);

        LetterDraft draft = new LetterDraft(form.get("author"), form.get("content"));

        String recipient = form.get(DiscordForm.RECIPIENT_FIELD);
        String replyTo = form.get(DiscordForm.REPLY_TO_FIELD);
        if (recipient != null) {
            DiscordId replyToId = replyTo == null ? null : DiscordId.of(replyTo);
            Receipt receipt = delivery.sendTo(draft, DiscordId.of(recipient), replyToId);
            HttpResponses.send(ex, 200, DiscordForm.receiptToBody(receipt));
            return;
        }
        if (replyTo != null) {
            // 답장 안내는 디스코드로 보낼 때만 의미가 있다
            throw LetterException.invalid("답장 받을 사람은 디스코드로 보낼 때만 지정할 수 있습니다.");
        }

        LetterCode code = service.send(draft);
        HttpResponses.send(ex, 200, FormCodec.encode(Map.of("code", code.value())));
    }

    private void read(HttpExchange ex, String rawCode) {
        Letter letter = service.read(LetterCode.of(rawCode));
        HttpResponses.send(ex, 200, LetterForm.toBody(letter));
    }

    private void reply(HttpExchange ex, String rawCode) {
        Map<String, String> form = readForm(ex);
        String content = form.get("content");
        Receipt receipt = delivery.reply(LetterCode.of(rawCode), content);
        HttpResponses.send(ex, 200, DiscordForm.receiptToBody(receipt));
    }

    private Map<String, String> readForm(HttpExchange ex) {
        String body = new String(readBody(ex), StandardCharsets.UTF_8);
        try {
            return FormCodec.decode(body);
        } catch (IllegalArgumentException e) {
            throw LetterException.invalid("요청 형식이 올바르지 않습니다.");
        }
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
