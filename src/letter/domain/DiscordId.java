package letter.domain;

import java.util.regex.Pattern;

/**
 * 디스코드 사용자 ID(스노우플레이크). 형식이 검증된 값 객체이므로 이 타입으로 받은 값은
 * URL 경로나 JSON에 넣어도 안전하다.
 * (디스코드 설정 → 고급 → 개발자 모드를 켠 뒤, 사용자를 우클릭 → "사용자 ID 복사")
 */
public record DiscordId(String value) {
    private static final Pattern FORMAT = Pattern.compile("[0-9]{17,20}");

    public DiscordId {
        String normalized = value == null ? "" : value.trim();
        if (!FORMAT.matcher(normalized).matches()) {
            throw LetterException.invalid("올바르지 않은 디스코드 ID입니다. (숫자 17~20자리)");
        }
        value = normalized;
    }

    public static DiscordId of(String raw) {
        return new DiscordId(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
