package letter.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import letter.domain.DiscordId;
import letter.domain.LetterException;
import letter.domain.LoginResult;
import letter.domain.LoginTicket;
import letter.protocol.AuthForm;
import letter.server.service.LoginService;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 디스코드 DM 인증 코드 로그인 어댑터. 모두 key=value 형식으로 주고받는다.
 *   POST /auth/login   -> 본문 discordId=.. : 봇이 그 사람에게 인증 코드를 DM으로 보내고, loginId를 돌려줌
 *   POST /auth/verify  -> 본문 loginId=..&code=.. : 코드가 맞으면 세션 토큰과 사용자
 *   POST /auth/logout  -> 세션 종료 (Authorization: Bearer 토큰)
 */
public class AuthHttpHandler implements HttpHandler {
    private static final String PREFIX = "/auth/";
    private static final int MAX_FORM_BYTES = 10_000;   // 로그인 요청은 작다

    private final LoginService login;

    public AuthHttpHandler(LoginService login) {
        this.login = login;
    }

    @Override
    public void handle(HttpExchange ex) {
        HttpResponses.guard(ex, () -> route(ex));
    }

    private void route(HttpExchange ex) {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        if (!path.startsWith(PREFIX)) throw LetterException.notFound("지원하지 않는 주소입니다.");

        switch (path.substring(PREFIX.length())) {
            case "login" -> post(ex, method, this::start);
            case "verify" -> post(ex, method, this::verify);
            case "logout" -> post(ex, method, this::logout);
            default -> throw LetterException.notFound("지원하지 않는 주소입니다.");
        }
    }

    private void post(HttpExchange ex, String method, Consumer<HttpExchange> action) {
        if (!method.equals("POST")) {
            HttpResponses.sendError(ex, 405, "이 주소는 POST만 지원합니다.");
            return;
        }
        action.accept(ex);
    }

    private void start(HttpExchange ex) {
        Map<String, String> form = HttpRequests.readForm(ex, MAX_FORM_BYTES);
        String rawId = form.get(AuthForm.DISCORD_ID_FIELD);
        if (rawId == null) throw LetterException.invalid("디스코드 ID를 입력해 주세요.");
        LoginTicket ticket = login.start(DiscordId.of(rawId));   // 형식이 틀리면 INVALID
        HttpResponses.send(ex, 200, AuthForm.ticketToBody(ticket));
    }

    private void verify(HttpExchange ex) {
        Map<String, String> form = HttpRequests.readForm(ex, MAX_FORM_BYTES);
        LoginResult result = login.verify(form.get(AuthForm.LOGIN_ID_FIELD), form.get(AuthForm.CODE_FIELD));
        HttpResponses.send(ex, 200, AuthForm.resultToBody(result));
    }

    private void logout(HttpExchange ex) {
        login.logout(HttpRequests.bearerToken(ex));
        HttpResponses.send(ex, 200, "ok");
    }
}
