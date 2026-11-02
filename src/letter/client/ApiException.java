package letter.client;

/** 서버와 통신하다 실패했을 때의 오류. 같은 요청을 다시 시도해 볼 만한지(retryable)를 함께 알려 준다. */
@SuppressWarnings("serial")
public class ApiException extends Exception {
    private final boolean retryable;

    public ApiException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
