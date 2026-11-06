package letter.domain;

/** 편지 서비스에서 발생하는 모든 업무 오류. 오류의 종류는 {@link Kind}로 구분한다. */
@SuppressWarnings("serial")
public class LetterException extends RuntimeException {

    public enum Kind { INVALID, NOT_FOUND, TOO_LARGE, RATE_LIMITED, STORAGE_FAILURE, CORRUPTED, UNAVAILABLE,
        /** 비밀번호가 걸린 편지인데 비밀번호를 보내지 않았다. */
        PASSWORD_REQUIRED,
        /** 비밀번호가 틀렸다. */
        PASSWORD_WRONG }

    private final Kind kind;

    private LetterException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static LetterException invalid(String message) {
        return new LetterException(Kind.INVALID, message, null);
    }

    public static LetterException notFound(String message) {
        return new LetterException(Kind.NOT_FOUND, message, null);
    }

    public static LetterException passwordRequired(String message) {
        return new LetterException(Kind.PASSWORD_REQUIRED, message, null);
    }

    public static LetterException passwordWrong(String message) {
        return new LetterException(Kind.PASSWORD_WRONG, message, null);
    }

    public static LetterException tooLarge(String message) {
        return new LetterException(Kind.TOO_LARGE, message, null);
    }

    public static LetterException rateLimited(String message) {
        return new LetterException(Kind.RATE_LIMITED, message, null);
    }

    /** 외부 연동(디스코드 등)을 지금 쓸 수 없을 때. */
    public static LetterException unavailable(String message, Throwable cause) {
        return new LetterException(Kind.UNAVAILABLE, message, cause);
    }

    public static LetterException storageFailure(String message, Throwable cause) {
        return new LetterException(Kind.STORAGE_FAILURE, message, cause);
    }

    public static LetterException corrupted(String message) {
        return new LetterException(Kind.CORRUPTED, message, null);
    }
}
