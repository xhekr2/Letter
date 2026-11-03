package letter.server.discord;

import letter.domain.DiscordId;
import letter.domain.DiscordUser;

import java.util.Optional;

/** 디스코드와 통신하는 방법의 추상화. 서비스는 이 인터페이스만 알고, REST 호출 방식은 모른다. */
public interface DiscordGateway {

    /** @return 해당 ID의 사용자가 없으면 빈 값 */
    Optional<DiscordUser> findUser(DiscordId id) throws DiscordGatewayException;

    /** 봇이 해당 사용자에게 DM을 보낸다. */
    void sendDirectMessage(DiscordId to, String content) throws DiscordGatewayException;
}
