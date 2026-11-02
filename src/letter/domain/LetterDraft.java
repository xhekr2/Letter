package letter.domain;

/**
 * 아직 발송되지 않은 편지 (작성자 + 내용).
 * 편지가 지켜야 할 규칙(내용 필수, 이름 길이 제한, 익명 처리)을 이 클래스가 책임진다.
 * 불변 객체이므로 한 번 만들어지면 항상 유효한 상태다.
 */
public record LetterDraft(String author, String content) {
    public static final int MAX_AUTHOR_CHARS = 50;
    public static final String ANONYMOUS = "익명";

    public LetterDraft {
        if (content == null || content.isBlank()) {
            throw LetterException.invalid("편지 내용이 비어 있습니다.");
        }
        author = normalizeAuthor(author);
    }

    /** 제어문자를 제거하고 공백을 다듬는다. 비어 있으면 익명, 너무 길면 오류. */
    public static String normalizeAuthor(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", "").trim();
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_AUTHOR_CHARS) {
            throw LetterException.invalid("작성자 이름은 " + MAX_AUTHOR_CHARS + "자 이하여야 합니다.");
        }
        return cleaned.isEmpty() ? ANONYMOUS : cleaned;
    }
}
