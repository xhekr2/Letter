package letter.client;

/**
 * 로그인이 필요하거나 로그인이 만료되었을 때의 오류. (서버를 다시 시작했거나 오래 지난 경우)
 * 같은 요청을 그대로 다시 보내도 소용없고, 다시 로그인해야 한다.
 */
@SuppressWarnings("serial")
public class LoginRequiredException extends ApiException {

    public LoginRequiredException(String message) {
        super(message, false);
    }
}
