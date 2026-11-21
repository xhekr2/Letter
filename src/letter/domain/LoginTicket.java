package letter.domain;

import java.util.Objects;

/**
 * 서버가 로그인을 시작할 때 클라이언트에게 주는 안내.
 *
 * @param loginId          로그인이 끝났는지 물어볼 때 쓰는 비밀 값. 이 프로그램만 알고 있어야 하며 브라우저 주소에는 나오지 않는다.
 * @param url              사용자가 브라우저에서 열 디스코드 승인 주소
 * @param confirmCode      브라우저 화면에서 입력해야 로그인이 끝나는 확인 코드. 남이 보낸 링크로 내 계정이 엉뚱한 프로그램에
 *                         연결되는 것을 막는다.
 * @param expiresInSeconds 이 안내가 유효한 시간(초)
 */
public record LoginTicket(String loginId, String url, String confirmCode, int expiresInSeconds) {

    public LoginTicket {
        Objects.requireNonNull(loginId, "loginId");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(confirmCode, "confirmCode");
        if (loginId.isBlank() || url.isBlank() || confirmCode.isBlank() || expiresInSeconds <= 0) {
            throw new IllegalArgumentException("로그인 안내 형식이 올바르지 않습니다.");
        }
    }
}
