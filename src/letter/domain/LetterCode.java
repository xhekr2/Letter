package letter.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 편지의 고유 코드. 형식이 검증된 값 객체이므로, 이 타입으로 받은 값은 항상 안전하다.
 * (예: 파일 이름으로 써도 "../" 같은 경로 조작이 불가능하다)
 */
public record LetterCode(String value) {
    public static final int LENGTH = 8;
    /** 헷갈리는 문자(0/O, 1/I)를 뺀 발급용 문자 */
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern FORMAT = Pattern.compile("[A-Z2-9]{" + LENGTH + "}");

    public LetterCode {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(normalized).matches()) {
            throw LetterException.invalid("올바르지 않은 코드 형식입니다. (영문 대문자와 숫자 8자리)");
        }
        value = normalized;
    }

    public static LetterCode of(String raw) {
        return new LetterCode(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
