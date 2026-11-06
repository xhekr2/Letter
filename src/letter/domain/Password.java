package letter.domain;

/**
 * 편지에 거는 선택적 비밀번호. 형식이 검증된 값 객체다.
 * 디스코드 DM에서 `|| 비밀번호 ||`(가림 표시)로 안전하게 보여 줄 수 있도록 '|'와 '`'는 쓸 수 없다.
 */
public record Password(String value) {
    public static final int MAX_CHARS = 30;

    public Password {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw LetterException.invalid("비밀번호가 비어 있습니다.");
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_CHARS) {
            throw LetterException.invalid("비밀번호는 " + MAX_CHARS + "자 이하여야 합니다.");
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw LetterException.invalid("비밀번호에 쓸 수 없는 문자(제어 문자)가 있습니다.");
        }
        if (normalized.indexOf('|') >= 0 || normalized.indexOf('`') >= 0) {
            throw LetterException.invalid("비밀번호에는 '|' 와 '`' 를 쓸 수 없습니다.");
        }
        value = normalized;
    }

    /** 비어 있으면(비밀번호를 걸지 않으면) null, 아니면 검증된 비밀번호. */
    public static Password ofOptional(String raw) {
        return raw == null || raw.isBlank() ? null : new Password(raw);
    }

    /** 로그나 화면에 실수로 찍혀도 값이 드러나지 않게 한다. */
    @Override
    public String toString() {
        return "Password[****]";
    }
}
