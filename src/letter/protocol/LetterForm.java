package letter.protocol;

import letter.domain.Letter;
import letter.domain.LetterException;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@link Letter} 와 전송/저장 형식(author, content, createdAt)의 변환. 기존 파일 형식과 동일하다. */
public final class LetterForm {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

    private LetterForm() {
    }

    public static String toBody(Letter letter) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("author", letter.author());
        fields.put("content", letter.content());
        fields.put("createdAt", TIME.format(letter.createdAt()));
        return FormCodec.encode(fields);
    }

    /** @throws IllegalArgumentException 형식이 올바르지 않거나 항목이 빠졌을 때 */
    public static Letter fromBody(String body) {
        try {
            Map<String, String> fields = FormCodec.decode(body);
            String content = fields.get("content");
            String createdAt = fields.get("createdAt");
            if (content == null || createdAt == null) {
                throw new IllegalArgumentException("필수 항목이 없습니다.");
            }
            return new Letter(fields.get("author"), content, LocalDateTime.parse(createdAt, TIME));
        } catch (DateTimeException | LetterException e) {
            throw new IllegalArgumentException("편지 데이터 형식이 올바르지 않습니다.", e);
        }
    }
}
