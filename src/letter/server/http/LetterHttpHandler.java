package letter.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LetterException;
import letter.domain.Password;
import letter.domain.Receipt;
import letter.protocol.DiscordForm;
import letter.protocol.FormCodec;
import letter.protocol.InboxForm;
import letter.protocol.LetterForm;
import letter.server.service.Authenticator;
import letter.server.service.LetterDeliveryService;
import letter.server.service.LetterService;

import java.util.Map;

/**
 * HTTP 요청을 서비스 호출로 바꿔 주는 어댑터. 모든 요청은 로그인(Authorization: Bearer 세션 토큰)이 필요하다.
 *   POST /letters                -> 편지 발송. 보낸 사람은 요청한 사람의 로그인 계정으로 정해진다. (author 항목은 무시한다)
 *                                   discordId 항목이 있으면 그 사람에게 디스코드 봇이 코드를 전달,
 *                                   거기에 replyTo 항목을 더하면 그 사람이 답장을 받는다
 *   GET  /letters/inbox          -> 내게 전달된 편지의 코드 목록 (최신순)
 *   GET  /letters/{code}         -> 편지 조회 (답장할 수 있으면 replyable=true. 답장받을 ID와 비밀번호 해시는 절대 내려주지 않는다)
 *                                   비밀번호가 걸린 편지는 401
 *   POST /letters/{code}/open    -> 비밀번호를 보내서 편지 조회 (틀리면 403)
 *   POST /letters/{code}/replies -> 답장 발송 (답장 코드는 원본에 저장된 디스코드 ID로 전달)
 */
public class LetterHttpHandler implements HttpHandler {
    private static final String COLLECTION = "/letters";
    private static final String INBOX = "/letters/inbox";
    private static final String ITEM_PREFIX = "/letters/";
    private static final String REPLIES_SUFFIX = "/replies";
    private static final String OPEN_SUFFIX = "/open";

    private final LetterService service;
    private final LetterDeliveryService delivery;
    private final Authenticator authenticator;
    private final int maxBodyBytes;

    public LetterHttpHandler(LetterService service, LetterDeliveryService delivery, Authenticator authenticator,
                             int maxBodyBytes) {
        this.service = service;
        this.delivery = delivery;
        this.authenticator = authenticator;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public void handle(HttpExchange ex) {
        HttpResponses.guard(ex, () -> route(ex));
    }

    private void route(HttpExchange ex) {
        DiscordUser caller = HttpRequests.authenticate(ex, authenticator);   // 로그인하지 않았으면 어떤 주소든 401
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        if (path.equals(COLLECTION)) {
            if (!method.equals("POST")) {
                HttpResponses.sendError(ex, 405, "이 주소는 POST만 지원합니다.");
                return;
            }
            create(ex, caller);
        } else if (path.equals(INBOX)) {
            if (!method.equals("GET")) {
                HttpResponses.sendError(ex, 405, "이 주소는 GET만 지원합니다.");
                return;
            }
            inbox(ex, caller);
        } else if (path.startsWith(ITEM_PREFIX) && path.endsWith(REPLIES_SUFFIX)) {
            if (!method.equals("POST")) {
                HttpResponses.sendError(ex, 405, "이 주소는 POST만 지원합니다.");
                return;
            }
            reply(ex, caller, path.substring(ITEM_PREFIX.length(), path.length() - REPLIES_SUFFIX.length()));
        } else if (path.startsWith(ITEM_PREFIX) && path.endsWith(OPEN_SUFFIX)) {
            if (!method.equals("POST")) {
                HttpResponses.sendError(ex, 405, "이 주소는 POST만 지원합니다.");
                return;
            }
            open(ex, path.substring(ITEM_PREFIX.length(), path.length() - OPEN_SUFFIX.length()));
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

    private void create(HttpExchange ex, DiscordUser caller) {
        Map<String, String> form = HttpRequests.readForm(ex, maxBodyBytes);

        // 작성자 이름은 받지 않는다: 보낸 사람은 로그인한 계정으로만 정해진다
        LetterDraft draft = new LetterDraft(form.get("content"), Password.ofOptional(form.get(LetterForm.PASSWORD_FIELD)));

        String recipient = form.get(DiscordForm.RECIPIENT_FIELD);
        String replyTo = form.get(DiscordForm.REPLY_TO_FIELD);
        if (recipient != null) {
            DiscordId replyToId = replyTo == null ? null : DiscordId.of(replyTo);
            Receipt receipt = delivery.sendTo(caller, draft, DiscordId.of(recipient), replyToId);
            HttpResponses.send(ex, 200, DiscordForm.receiptToBody(receipt));
            return;
        }
        if (replyTo != null) {
            // 답장 안내는 디스코드로 보낼 때만 의미가 있다
            throw LetterException.invalid("답장 받을 사람은 디스코드로 보낼 때만 지정할 수 있습니다.");
        }

        LetterCode code = service.send(caller, draft);
        HttpResponses.send(ex, 200, FormCodec.encode(Map.of("code", code.value())));
    }

    /** 로그인한 사람 본인 앞으로 전달된 편지의 코드만 돌려준다. 다른 사람의 목록은 볼 수 없다. */
    private void inbox(HttpExchange ex, DiscordUser caller) {
        HttpResponses.send(ex, 200, InboxForm.toBody(service.inboxOf(caller.id())));
    }

    private void read(HttpExchange ex, String rawCode) {
        Letter letter = service.open(LetterCode.of(rawCode), null);
        HttpResponses.send(ex, 200, LetterForm.toBody(letter));
    }

    private void open(HttpExchange ex, String rawCode) {
        Map<String, String> form = HttpRequests.readForm(ex, maxBodyBytes);
        Letter letter = service.open(LetterCode.of(rawCode), form.get(LetterForm.PASSWORD_FIELD));
        HttpResponses.send(ex, 200, LetterForm.toBody(letter));
    }

    private void reply(HttpExchange ex, DiscordUser caller, String rawCode) {
        Map<String, String> form = HttpRequests.readForm(ex, maxBodyBytes);
        String content = form.get("content");
        Receipt receipt = delivery.reply(caller, LetterCode.of(rawCode), content, form.get(LetterForm.PASSWORD_FIELD));
        HttpResponses.send(ex, 200, DiscordForm.receiptToBody(receipt));
    }
}
