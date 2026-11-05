package letter.protocol;

import letter.domain.Delivery;
import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.LetterCode;
import letter.domain.LetterException;
import letter.domain.Receipt;

import java.util.LinkedHashMap;
import java.util.Map;

/** 디스코드 관련 응답({@link DiscordUser}, {@link Receipt})과 전송 형식의 변환. 서버와 클라이언트가 함께 쓴다. */
public final class DiscordForm {
    /** 편지 발송 요청에 디스코드 수신자를 함께 보낼 때 쓰는 항목 이름 */
    public static final String RECIPIENT_FIELD = "discordId";
    /** 편지 발송 요청에서 "답장을 받을 디스코드 ID"를 보낼 때 쓰는 항목 이름 (수신자 항목과 함께일 때만 유효) */
    public static final String REPLY_TO_FIELD = "replyTo";

    private DiscordForm() {
    }

    public static String userToBody(DiscordUser user) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", user.id().value());
        fields.put("name", user.name());
        return FormCodec.encode(fields);
    }

    /** @throws IllegalArgumentException 형식이 올바르지 않거나 항목이 빠졌을 때 */
    public static DiscordUser userFromBody(String body) {
        Map<String, String> fields = FormCodec.decode(body);
        String id = fields.get("id");
        if (id == null) throw new IllegalArgumentException("필수 항목이 없습니다.");
        try {
            return new DiscordUser(DiscordId.of(id), fields.get("name"));
        } catch (LetterException e) {
            throw new IllegalArgumentException("디스코드 사용자 형식이 올바르지 않습니다.", e);
        }
    }

    public static String receiptToBody(Receipt receipt) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("code", receipt.code().value());
        fields.put("delivery", receipt.delivery().name());
        return FormCodec.encode(fields);
    }

    /** @throws IllegalArgumentException 형식이 올바르지 않거나 항목이 빠졌을 때 */
    public static Receipt receiptFromBody(String body) {
        Map<String, String> fields = FormCodec.decode(body);
        String code = fields.get("code");
        String delivery = fields.get("delivery");
        if (code == null || delivery == null) throw new IllegalArgumentException("필수 항목이 없습니다.");
        try {
            return new Receipt(LetterCode.of(code), Delivery.valueOf(delivery));
        } catch (LetterException e) {
            throw new IllegalArgumentException("전송 결과 형식이 올바르지 않습니다.", e);
        }
    }
}
