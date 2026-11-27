package letter.domain;

import java.util.Objects;

/**
 * 서버가 로그인을 시작할 때 클라이언트에게 주는 안내. 인증 코드는 봇이 디스코드 DM으로 보내므로 여기에는 들어 있지 않다.
 *
 * @param loginId          인증 코드를 확인할 때 쓰는 비밀 값. 이 프로그램만 알고 있어야 한다.
 * @param expiresInSeconds 인증 코드가 유효한 시간(초)
 */
public record LoginTicket(String loginId, int expiresInSeconds) {

    public LoginTicket {
        Objects.requireNonNull(loginId, "loginId");
        if (loginId.isBlank() || expiresInSeconds <= 0) {
            throw new IllegalArgumentException("로그인 안내 형식이 올바르지 않습니다.");
        }
    }
}
