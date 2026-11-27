package letter.protocol;

import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.LetterException;
import letter.domain.LoginResult;
import letter.domain.LoginTicket;

import java.util.LinkedHashMap;
import java.util.Map;

/** 로그인 관련 요청·응답({@link LoginTicket}, {@link LoginResult})과 전송 형식의 변환. 서버와 클라이언트가 함께 쓴다. */
public final class AuthForm {
    /** 로그인을 시작할 때 내 디스코드 ID를 보내는 항목 이름 */
    public static final String DISCORD_ID_FIELD = "discordId";
    /** 인증 코드를 확인할 때 loginId를 보내는 항목 이름 */
    public static final String LOGIN_ID_FIELD = "loginId";
    /** 인증 코드를 보내는 항목 이름 */
    public static final String CODE_FIELD = "code";
    /** 로그인이 필요하다는 오류 응답에 붙는 표시. (비밀번호가 필요하다는 응답과 같은 401이라 이것으로 구분한다) */
    public static final String LOGIN_REQUIRED_FIELD = "login";
    public static final String LOGIN_REQUIRED_VALUE = "required";

    private AuthForm() {
    }

    public static String ticketToBody(LoginTicket ticket) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(LOGIN_ID_FIELD, ticket.loginId());
        fields.put("expiresIn", String.valueOf(ticket.expiresInSeconds()));
        return FormCodec.encode(fields);
    }

    /** @throws IllegalArgumentException 형식이 올바르지 않거나 항목이 빠졌을 때 */
    public static LoginTicket ticketFromBody(String body) {
        Map<String, String> fields = FormCodec.decode(body);
        String loginId = fields.get(LOGIN_ID_FIELD);
        String expiresIn = fields.get("expiresIn");
        if (loginId == null || expiresIn == null) {
            throw new IllegalArgumentException("필수 항목이 없습니다.");
        }
        return new LoginTicket(loginId, Integer.parseInt(expiresIn));   // 숫자가 아니면 IllegalArgumentException
    }

    public static String resultToBody(LoginResult result) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("token", result.token());
        fields.put("id", result.user().id().value());
        fields.put("name", result.user().name());
        return FormCodec.encode(fields);
    }

    /** @throws IllegalArgumentException 형식이 올바르지 않거나 항목이 빠졌을 때 */
    public static LoginResult resultFromBody(String body) {
        Map<String, String> fields = FormCodec.decode(body);
        String token = fields.get("token");
        String id = fields.get("id");
        if (token == null || id == null) throw new IllegalArgumentException("필수 항목이 없습니다.");
        try {
            return new LoginResult(token, new DiscordUser(DiscordId.of(id), fields.get("name")));
        } catch (LetterException e) {
            throw new IllegalArgumentException("디스코드 사용자 형식이 올바르지 않습니다.", e);
        }
    }
}
