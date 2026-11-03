package letter.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.LetterException;
import letter.protocol.DiscordForm;
import letter.server.service.LetterDeliveryService;

/**
 * 편지를 보내기 전에 "받는 사람이 맞는지" 확인할 수 있게 디스코드 사용자 이름을 알려 주는 어댑터.
 *   GET /discord/users/{id} -> 사용자 이름
 */
public class DiscordUserHttpHandler implements HttpHandler {
    private static final String ITEM_PREFIX = "/discord/users/";

    private final LetterDeliveryService delivery;

    public DiscordUserHttpHandler(LetterDeliveryService delivery) {
        this.delivery = delivery;
    }

    @Override
    public void handle(HttpExchange ex) {
        HttpResponses.guard(ex, () -> route(ex));
    }

    private void route(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        if (!path.startsWith(ITEM_PREFIX)) {
            throw LetterException.notFound("지원하지 않는 주소입니다.");
        }
        if (!ex.getRequestMethod().equals("GET")) {
            HttpResponses.sendError(ex, 405, "이 주소는 GET만 지원합니다.");
            return;
        }
        DiscordUser user = delivery.lookup(DiscordId.of(path.substring(ITEM_PREFIX.length())));
        HttpResponses.send(ex, 200, DiscordForm.userToBody(user));
    }
}
