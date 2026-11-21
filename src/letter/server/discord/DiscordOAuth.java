package letter.server.discord;

import letter.domain.DiscordUser;

/**
 * 디스코드 OAuth2 로그인(승인 코드 방식)의 추상화. 서비스는 이 인터페이스만 알고, 디스코드와 주고받는 방식은 모른다.
 * 편지 봇의 애플리케이션(client id/secret)으로 사용자가 누구인지 확인하는 용도이며, 사용자의 다른 정보는 요청하지 않는다.
 */
public interface DiscordOAuth {

    /**
     * 사용자가 브라우저에서 열 디스코드 승인 주소.
     *
     * @param state 승인 뒤 우리 서버로 돌아올 때 그대로 되돌려 받는 값
     * @throws DiscordGatewayException 로그인 설정이 없으면 NOT_CONFIGURED
     */
    String authorizeUrl(String state) throws DiscordGatewayException;

    /**
     * 승인 뒤 돌려받은 code로 로그인한 사용자가 누구인지 확인한다.
     *
     * @throws DiscordGatewayException code가 거부되면 INVALID_CODE, 설정이 없거나 틀리면 NOT_CONFIGURED,
     *                                 디스코드와 통신하지 못하면 UNAVAILABLE
     */
    DiscordUser identify(String code) throws DiscordGatewayException;
}
