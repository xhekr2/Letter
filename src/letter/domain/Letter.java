package letter.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 발송되어 저장된 편지. 작성 규칙은 {@link LetterDraft}를 재사용해 한 곳에서만 관리한다.
 *
 * @param replyTo      답장을 받을 디스코드 ID. 없으면 null. 서버 안에서만 쓰이며 읽는 사람에게는 전달하지 않는다.
 * @param to           디스코드로 전달된 편지의 받는 사람. 디스코드로 보내지 않았으면 null.
 *                     읽는 사람에게 "to:"로 보여 주는 정보다.
 * @param passwordHash 비밀번호의 해시. 비밀번호가 없으면 null. 서버 안에서만 쓰이며 읽는 사람에게는 전달하지 않는다.
 */
public record Letter(String author, String content, LocalDateTime createdAt,
                     DiscordId replyTo, DiscordUser to, String passwordHash) {

    public Letter {
        Objects.requireNonNull(createdAt, "createdAt");
        LetterDraft draft = new LetterDraft(author, content);
        author = draft.author();
        content = draft.content();
    }

    /** 답장을 받지 않고, 디스코드 정보도 비밀번호도 없는 편지. */
    public Letter(String author, String content, LocalDateTime createdAt) {
        this(author, content, createdAt, null, null, null);
    }

    /** 답장을 받을 사람이 있을 수 있는 편지. (디스코드 정보·비밀번호 없음) */
    public Letter(String author, String content, LocalDateTime createdAt, DiscordId replyTo) {
        this(author, content, createdAt, replyTo, null, null);
    }

    public boolean replyable() {
        return replyTo != null;
    }

    public boolean passwordProtected() {
        return passwordHash != null;
    }

    /** 해시는 로그에 남지 않도록 가린다. */
    @Override
    public String toString() {
        return "Letter[author=" + author + ", createdAt=" + createdAt + ", replyable=" + replyable()
                + ", to=" + to + ", passwordProtected=" + passwordProtected() + "]";
    }
}
