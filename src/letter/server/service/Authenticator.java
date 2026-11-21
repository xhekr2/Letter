package letter.server.service;

import letter.domain.DiscordUser;

/** 요청에 실려 온 세션 토큰으로 "누가 보낸 요청인지" 확인하는 방법. HTTP 계층은 이 인터페이스만 안다. */
public interface Authenticator {

    /**
     * @param token 로그인 때 받은 세션 토큰. 요청에 없었으면 null.
     * @return 로그인한 디스코드 사용자
     * @throws letter.domain.LetterException 토큰이 없거나 틀렸거나 만료되었으면 UNAUTHENTICATED
     */
    DiscordUser authenticate(String token);
}
