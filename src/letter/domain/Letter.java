package letter.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/** 발송되어 저장된 편지. 작성 규칙은 {@link LetterDraft}를 재사용해 한 곳에서만 관리한다. */
public record Letter(String author, String content, LocalDateTime createdAt) {

    public Letter {
        Objects.requireNonNull(createdAt, "createdAt");
        LetterDraft draft = new LetterDraft(author, content);
        author = draft.author();
        content = draft.content();
    }
}
