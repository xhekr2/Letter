package letter.protocol;

import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.Letter;
import letter.domain.LetterException;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link Letter} 와 저장/전송 형식의 변환.
 * 저장 형식({@link #toStoredBody})에는 답장받을 사람의 ID와 비밀번호 해시가 들어가고,
 * 전송 형식({@link #toBody})에는 들어가지 않는다. (읽는 사람에게 노출되지 않도록 서로 분리)
 * 받는 사람(to)은 읽는 사람에게 보여 주는 정보이므로 두 형식 모두에 들어간다.
 */
public final class LetterForm {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");
    private static final String REPLY_TO = "replyTo";
    private static final String REPLYABLE = "replyable";
    private static final String TO_ID = "toId";
    private static final String TO_NAME = "toName";
    private static final String PASSWORD_HASH = "pw";
    /** 편지 발송 요청과 비밀번호 확인 요청에서 비밀번호를 보낼 때 쓰는 항목 이름 */
    public static final String PASSWORD_FIELD = "password";

    private LetterForm() {
    }

    /** 파일에 저장하는 형식. 답장받을 ID와 비밀번호 해시가 있으면 포함한다. */
    public static String toStoredBody(Letter letter) {
        Map<String, String> fields = baseFields(letter);
        if (letter.replyTo() != null) fields.put(REPLY_TO, letter.replyTo().value());
        if (letter.passwordHash() != null) fields.put(PASSWORD_HASH, letter.passwordHash());
        return FormCodec.encode(fields);
    }

    /** 편지를 읽는 사람에게 보내는 형식. 답장 가능 여부(replyable)만 알리고 ID는 숨긴다. */
    public static String toBody(Letter letter) {
        Map<String, String> fields = baseFields(letter);
        if (letter.replyable()) fields.put(REPLYABLE, "true");
        return FormCodec.encode(fields);
    }

    private static Map<String, String> baseFields(Letter letter) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("author", letter.author());
        fields.put("content", letter.content());
        fields.put("createdAt", TIME.format(letter.createdAt()));
        if (letter.to() != null) {
            fields.put(TO_ID, letter.to().id().value());
            fields.put(TO_NAME, letter.to().name());
        }
        return fields;
    }

    /** 저장 형식과 전송 형식 모두 읽는다. 옛 데이터(replyTo 없음)도 그대로 읽힌다. */
    public static Letter fromBody(String body) {
        try {
            Map<String, String> fields = FormCodec.decode(body);
            String content = fields.get("content");
            String createdAt = fields.get("createdAt");
            if (content == null || createdAt == null) {
                throw new IllegalArgumentException("필수 항목이 없습니다.");
            }
            String replyTo = fields.get(REPLY_TO);
            String toId = fields.get(TO_ID);
            return new Letter(fields.get("author"), content, LocalDateTime.parse(createdAt, TIME),
                    replyTo == null ? null : DiscordId.of(replyTo),
                    toId == null ? null : new DiscordUser(DiscordId.of(toId), fields.get(TO_NAME)),
                    fields.get(PASSWORD_HASH));
        } catch (DateTimeException | LetterException e) {
            throw new IllegalArgumentException("편지 데이터 형식이 올바르지 않습니다.", e);
        }
    }

    /** 전송 형식의 replyable 항목. 없으면(옛 서버 포함) false. */
    public static boolean replyableFromBody(String body) {
        try {
            return "true".equals(FormCodec.decode(body).get(REPLYABLE));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
