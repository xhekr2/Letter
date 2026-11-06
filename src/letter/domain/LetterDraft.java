package letter.domain;

/**
 * 아직 발송되지 않은 편지 초안. 작성 규칙(내용 필수, 작성자 이름 규칙)이 여기 한 곳에 있다.
 *
 * @param password 편지에 거는 비밀번호. 걸지 않으면 null.
 */
public record LetterDraft(String author, String content, Password password) {
    public static final int MAX_AUTHOR_CHARS = 50;
    public static final String ANONYMOUS = "익명";

    public LetterDraft {
        if (content == null || content.isBlank()) throw LetterException.invalid("편지 내용이 비어 있습니다.");
        author = normalizeAuthor(author);
    }

    /** 비밀번호가 없는 편지 초안. */
    public LetterDraft(String author, String content) {
        this(author, content, null);
    }

    public static String normalizeAuthor(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", "").trim();
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_AUTHOR_CHARS) {
            throw LetterException.invalid("작성자 이름은 " + MAX_AUTHOR_CHARS + "자 이하여야 합니다.");
        }
        return cleaned.isEmpty() ? ANONYMOUS : cleaned;
    }
}
