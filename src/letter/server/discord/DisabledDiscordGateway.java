package letter.server.discord;

import letter.domain.DiscordId;
import letter.domain.DiscordUser;

import java.util.Optional;

/** 봇 토큰이 없을 때 쓰는 구현. 호출하면 "설정되지 않음"으로 실패해서 나머지 코드가 null 검사를 할 필요가 없다. */
public class DisabledDiscordGateway implements DiscordGateway {

    @Override
    public Optional<DiscordUser> findUser(DiscordId id) throws DiscordGatewayException {
        throw disabled();
    }

    @Override
    public void sendDirectMessage(DiscordId to, String content) throws DiscordGatewayException {
        throw disabled();
    }

    private static DiscordGatewayException disabled() {
        return new DiscordGatewayException(DiscordGatewayException.Reason.NOT_CONFIGURED,
                "디스코드 봇 토큰(DISCORD_BOT_TOKEN)이 설정되지 않았습니다.");
    }
}
