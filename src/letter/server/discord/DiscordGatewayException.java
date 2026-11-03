package letter.server.discord;

/** 디스코드와 통신하지 못했을 때의 오류. 원인은 {@link Reason}으로 구분한다. */
@SuppressWarnings("serial")
public class DiscordGatewayException extends Exception {

    public enum Reason {
        /** 봇 토큰이 설정되지 않아 기능이 꺼져 있다. */
        NOT_CONFIGURED,
        /** 상대가 DM을 막았거나 봇과 같은 서버에 없어서 보낼 수 없다. */
        DM_NOT_ALLOWED,
        /** 네트워크 오류, 시간 초과, 디스코드 장애, 호출 한도 초과 등 일시적인 문제. */
        UNAVAILABLE
    }

    private final Reason reason;

    public DiscordGatewayException(Reason reason, String message) {
        this(reason, message, null);
    }

    public DiscordGatewayException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
