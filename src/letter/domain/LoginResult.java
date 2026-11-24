package letter.domain;

import java.util.Objects;

/**
 * 인증 코드가 맞아 로그인이 끝난 결과.
 *
 * @param token 세션 토큰. 이후 모든 요청에 실어 보낸다.
 * @param user  로그인한 디스코드 사용자
 */
public record LoginResult(String token, DiscordUser user) {

    public LoginResult {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(user, "user");
        if (token.isBlank()) {
            throw new IllegalArgumentException("로그인 결과 형식이 올바르지 않습니다.");
        }
    }

    /** 토큰은 로그에 남지 않도록 가린다. */
    @Override
    public String toString() {
        return "LoginResult[user=" + user + "]";
    }
}
