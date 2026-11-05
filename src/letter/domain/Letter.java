package letter.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 발송되어 저장된 편지. 작성 규칙은 {@link LetterDraft}를 재사용해 한 곳에서만 관리한다.
 *
 * @param replyTo 답장을 받을 디스코드 ID. 없으면 null(답장을 받지 않는 편지).
 *                서버 안에서만 쓰이며, 편지를 읽는 사람에게는 절대 전달하지 않는다.
 */
public record Letter(String author, String content, LocalDateTime createdAt, DiscordId replyTo) {

    public Letter {
        Objects.requireNonNull(createdAt, "createdAt");
        LetterDraft draft = new LetterDraft(author, content);
        author = draft.author();
        content = draft.content();
    }

    /** 답장을 받지 않는 편지. */
    public Letter(String author, String content, LocalDateTime createdAt) {
        this(author, content, createdAt, null);
    }

    public boolean replyable() {
        return replyTo != null;
    }
}
