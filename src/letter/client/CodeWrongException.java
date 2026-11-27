package letter.client;

/** 로그인 인증 코드가 틀렸을 때의 오류. 남은 횟수 안에서는 같은 로그인으로 코드를 다시 입력할 수 있다. */
@SuppressWarnings("serial")
public class CodeWrongException extends ApiException {

    public CodeWrongException(String message) {
        super(message, false);
    }
}
