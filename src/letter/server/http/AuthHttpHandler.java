package letter.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import letter.domain.DiscordUser;
import letter.domain.LetterException;
import letter.domain.LoginPoll;
import letter.domain.LoginTicket;
import letter.protocol.AuthForm;
import letter.server.service.LoginService;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 디스코드 로그인 어댑터.
 *   POST /auth/login     -> 로그인 시작 (승인 주소, loginId, 확인 코드)            [프로그램]
 *   POST /auth/poll      -> loginId로 로그인이 끝났는지 확인, 끝났으면 세션 토큰      [프로그램]
 *   GET  /auth/callback  -> 디스코드 승인 뒤 사용자가 돌아오는 주소. 확인 코드 입력 화면    [브라우저]
 *   POST /auth/confirm   -> 확인 코드 입력 화면의 제출. 맞으면 로그인 완료              [브라우저]
 *   POST /auth/logout    -> 세션 종료 (Authorization: Bearer 토큰)                  [프로그램]
 * 프로그램이 쓰는 주소는 key=value 형식으로, 브라우저가 쓰는 주소는 HTML로 응답한다.
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
            case "poll" -> post(ex, method, this::poll);
            case "logout" -> post(ex, method, this::logout);
            case "confirm" -> post(ex, method, this::confirm);
            case "callback" -> {
                if (!method.equals("GET")) HttpResponses.sendError(ex, 405, "이 주소는 GET만 지원합니다.");
                else callback(ex);
            }
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
        LoginTicket ticket = login.start();
        HttpResponses.send(ex, 200, AuthForm.ticketToBody(ticket));
    }

    private void poll(HttpExchange ex) {
        Map<String, String> form = HttpRequests.readForm(ex, MAX_FORM_BYTES);
        LoginPoll poll = login.poll(form.get(AuthForm.LOGIN_ID_FIELD));
        HttpResponses.send(ex, 200, AuthForm.pollToBody(poll));
    }

    private void logout(HttpExchange ex) {
        login.logout(HttpRequests.bearerToken(ex));
        HttpResponses.send(ex, 200, "ok");
    }

    /** 디스코드가 사용자를 돌려보내는 주소. (브라우저가 연다) 오류도 사람이 읽을 수 있게 HTML로 보여 준다. */
    private void callback(HttpExchange ex) {
        Map<String, String> query = HttpRequests.readQuery(ex);
        String state = query.get("state");
        try {
            if (query.containsKey("error")) {                 // 디스코드 승인 화면에서 취소 등
                login.deny(state);
                HttpResponses.sendHtml(ex, 200, LoginPages.cancelled());
                return;
            }
            DiscordUser user = login.receiveApproval(state, query.get("code"));
            HttpResponses.sendHtml(ex, 200, LoginPages.confirm(user, state));
        } catch (LetterException e) {
            HttpResponses.sendHtml(ex, HttpResponses.statusOf(e.kind()), LoginPages.failure(e.getMessage()));
        }
    }

    private void confirm(HttpExchange ex) {
        Map<String, String> form = HttpRequests.readForm(ex, MAX_FORM_BYTES);
        String state = form.get("state");
        try {
            DiscordUser user = login.confirm(state, form.get("code"));
            HttpResponses.sendHtml(ex, 200, LoginPages.success(user));
        } catch (LetterException e) {
            int status = HttpResponses.statusOf(e.kind());
            Optional<DiscordUser> retry = login.awaitingConfirmation(state);   // 코드만 틀렸고 시도 횟수가 남아 있으면 다시 입력받는다
            String page = retry.isPresent()
                    ? LoginPages.confirm(retry.get(), state, e.getMessage())
                    : LoginPages.failure(e.getMessage());
            HttpResponses.sendHtml(ex, status, page);
        }
    }
}
