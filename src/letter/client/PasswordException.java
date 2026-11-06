package letter.client;

/** 비밀번호가 필요하거나(wrong=false) 틀렸을 때(wrong=true)의 오류. 같은 요청을 그대로 다시 보내도 소용없다. */
@SuppressWarnings("serial")
public class PasswordException extends ApiException {
    private final boolean wrong;

    public PasswordException(String message, boolean wrong) {
        super(message, false);
        this.wrong = wrong;
    }

    /** true면 비밀번호를 보냈는데 틀린 것, false면 비밀번호가 필요한데 보내지 않은 것. */
    public boolean isWrong() {
        return wrong;
    }
}
