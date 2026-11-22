package letter.domain;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 발송되어 저장된 편지. 내용 규칙은 {@link LetterDraft}를 재사용해 한 곳에서만 관리한다.
 *
 * @param author       작성자 표시 이름. 로그인한 사용자가 보낸 편지는 서버가 디스코드 이름으로 정한다.
 *                     답장 편지는 "{원본코드}에 대한 답장입니다.", 로그인 도입 전의 옛 편지는 작성자가 직접 적은 이름이다.
 * @param replyTo      답장을 받을 디스코드 ID. 없으면 null. 서버 안에서만 쓰이며 읽는 사람에게는 전달하지 않는다.
 * @param to           디스코드로 전달된 편지의 받는 사람. 디스코드로 보내지 않았으면 null.
 *                     읽는 사람에게 "to:"로 보여 주는 정보다.
 * @param passwordHash 비밀번호의 해시. 비밀번호가 없으면 null. 서버 안에서만 쓰이며 읽는 사람에게는 전달하지 않는다.
 * @param from         디스코드 로그인으로 확인된 보낸 사람. 로그인 도입 전의 옛 편지는 null. 답장 편지에서는 답장한 사람이다.
 *                     읽는 사람에게 "from:"으로 보여 주는 정보다.
 * @param deliveredTo  이 편지의 코드가 디스코드로 전달된 사용자의 ID. "받은 편지 목록"에 쓰인다.
 *                     답장 편지는 {@code to}를 숨기므로 따로 기록한다. 서버 안에서만 쓰이며 읽는 사람에게는 전달하지 않는다.
 *                     옛 편지는 null이고, 그때는 {@code to}의 ID가 대신 쓰인다. ({@link #addressee()})
 */
public record Letter(String author, String content, LocalDateTime createdAt,
                     DiscordId replyTo, DiscordUser to, String passwordHash,
                     DiscordUser from, DiscordId deliveredTo) {
    public static final int MAX_AUTHOR_CHARS = 50;
    public static final String ANONYMOUS = "익명";
    /** 답장 편지에만 붙는 작성자 표기의 꼬리. 일반 편지가 이 형식의 이름을 쓰면 답장인 척 할 수 있어 서버가 막는다. */
    public static final String REPLY_AUTHOR_SUFFIX = "에 대한 답장입니다.";
    private static final Pattern REPLY_AUTHOR = Pattern.compile("([A-Za-z2-9]{8})" + Pattern.quote(REPLY_AUTHOR_SUFFIX));

    public Letter {
        Objects.requireNonNull(createdAt, "createdAt");
        new LetterDraft(content);   // 내용 규칙은 초안과 같다
        author = normalizeAuthor(author);
    }

    /** 보낸 사람 확인 정보와 목록용 수신자 정보가 없는 편지. (로그인 도입 전의 편지와 같은 형태) */
    public Letter(String author, String content, LocalDateTime createdAt,
                  DiscordId replyTo, DiscordUser to, String passwordHash) {
        this(author, content, createdAt, replyTo, to, passwordHash, null, null);
    }

    /** 답장을 받지 않고, 디스코드 정보도 비밀번호도 없는 편지. */
    public Letter(String author, String content, LocalDateTime createdAt) {
        this(author, content, createdAt, null, null, null);
    }

    /** 답장을 받을 사람이 있을 수 있는 편지. (디스코드 정보·비밀번호 없음) */
    public Letter(String author, String content, LocalDateTime createdAt, DiscordId replyTo) {
        this(author, content, createdAt, replyTo, null, null);
    }

    /** 제어문자를 지우고 공백을 정리한다. 비면 "익명", 50자를 넘으면 거절한다. */
    public static String normalizeAuthor(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", "").trim();
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_AUTHOR_CHARS) {
            throw LetterException.invalid("작성자 이름은 " + MAX_AUTHOR_CHARS + "자 이하여야 합니다.");
        }
        return cleaned.isEmpty() ? ANONYMOUS : cleaned;
    }

    public boolean replyable() {
        return replyTo != null;
    }

    public boolean passwordProtected() {
        return passwordHash != null;
    }

    /** 답장 편지의 작성자 표기: "{원본코드}에 대한 답장입니다." */
    public static String replyAuthor(LetterCode original) {
        return original.value() + REPLY_AUTHOR_SUFFIX;
    }

    /** 이 작성자 이름이 답장 편지 전용 표기 형식인지. (대소문자 구분 없이 본다) */
    public static boolean isReplyAuthor(String author) {
        return REPLY_AUTHOR.matcher(author).matches();
    }

    /**
     * 읽는 사람에게 "from:"으로 보여 줄 표기.
     * 답장 편지(답장한 사람이 확인된 경우)는 "{이름}({@핸들})님이 {원본코드}편지에 답장했어요!",
     * 일반 편지에 확인된 보낸 사람이 있으면 {이름}({@핸들}, {ID}), 없으면 작성자 이름.
     */
    public String fromLabel() {
        if (from == null) return author;
        Matcher m = REPLY_AUTHOR.matcher(author);
        if (m.matches()) return from.shortLabel() + "님이 " + m.group(1) + "편지에 답장했어요!";
        return from.label();
    }

    /**
     * 이 편지가 전달된 디스코드 사용자의 ID. 받은 편지 목록의 기준이다.
     * 디스코드로 보내지 않은 편지는 null이다.
     */
    public DiscordId addressee() {
        if (deliveredTo != null) return deliveredTo;
        return to != null ? to.id() : null;
    }

    /** 해시는 로그에 남지 않도록 가린다. */
    @Override
    public String toString() {
        return "Letter[author=" + author + ", createdAt=" + createdAt + ", replyable=" + replyable()
                + ", from=" + from + ", to=" + to + ", passwordProtected=" + passwordProtected() + "]";
    }
}
