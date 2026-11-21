package letter.server.discord;

import letter.domain.DiscordUser;

/** 로그인 설정(client id/secret/공개 주소)이 없을 때 쓰는 구현. 호출하면 "설정되지 않음"으로 실패한다. */
public class DisabledDiscordOAuth implements DiscordOAuth {

    @Override
    public String authorizeUrl(String state) throws DiscordGatewayException {
        throw disabled();
    }

    @Override
    public DiscordUser identify(String code) throws DiscordGatewayException {
        throw disabled();
    }

    private static DiscordGatewayException disabled() {
        return new DiscordGatewayException(DiscordGatewayException.Reason.NOT_CONFIGURED,
                "디스코드 로그인 설정(DISCORD_CLIENT_ID, DISCORD_CLIENT_SECRET, PUBLIC_URL)이 없습니다.");
    }
}
