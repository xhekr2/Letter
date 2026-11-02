package letter.domain;

/** 편지 서비스에서 발생하는 모든 업무 오류. 오류의 종류는 {@link Kind}로 구분한다. */
@SuppressWarnings("serial")
public class LetterException extends RuntimeException {

    public enum Kind { INVALID, NOT_FOUND, TOO_LARGE, RATE_LIMITED, STORAGE_FAILURE, CORRUPTED }

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

    public static LetterException tooLarge(String message) {
        return new LetterException(Kind.TOO_LARGE, message, null);
    }

    public static LetterException storageFailure(String message, Throwable cause) {
        return new LetterException(Kind.STORAGE_FAILURE, message, cause);
    }

    public static LetterException corrupted(String message) {
        return new LetterException(Kind.CORRUPTED, message, null);
    }
}
