package letter.domain;

/**
 * 아직 발송되지 않은 편지 초안. 작성 규칙(내용 필수)이 여기 한 곳에 있다.
 * 보낸 사람은 초안에 들어 있지 않다. 로그인한 디스코드 계정을 서버가 정하기 때문에,
 * 작성자가 이름을 마음대로 적어 다른 사람인 척할 수 없다.
 *
 * @param password 편지에 거는 비밀번호. 걸지 않으면 null.
 */
public record LetterDraft(String content, Password password) {

    public LetterDraft {
        if (content == null || content.isBlank()) throw LetterException.invalid("편지 내용이 비어 있습니다.");
    }

    /** 비밀번호가 없는 편지 초안. */
    public LetterDraft(String content) {
        this(content, null);
    }
}
