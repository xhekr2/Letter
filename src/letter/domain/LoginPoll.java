package letter.domain;

/**
 * 로그인이 끝났는지 물어본 결과. 아직이면 token과 user가 모두 null이다.
 *
 * @param token 로그인이 끝났을 때 받는 세션 토큰. 이후 모든 요청에 실어 보낸다.
 * @param user  로그인한 디스코드 사용자
 */
public record LoginPoll(String token, DiscordUser user) {

    public LoginPoll {
        if ((token == null) != (user == null)) {
            throw new IllegalArgumentException("로그인 결과 형식이 올바르지 않습니다.");
        }
        if (token != null && token.isBlank()) {
            throw new IllegalArgumentException("로그인 결과 형식이 올바르지 않습니다.");
        }
    }

    public static LoginPoll pending() {
        return new LoginPoll(null, null);
    }

    public boolean isDone() {
        return token != null;
    }

    /** 토큰은 로그에 남지 않도록 가린다. */
    @Override
    public String toString() {
        return isDone() ? "LoginPoll[done, user=" + user + "]" : "LoginPoll[pending]";
    }
}
