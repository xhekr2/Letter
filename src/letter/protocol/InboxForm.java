package letter.protocol;

import letter.domain.LetterCode;
import letter.domain.LetterException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 받은 편지 목록(코드들의 나열)과 전송 형식의 변환. 서버와 클라이언트가 함께 쓴다. 순서는 그대로 유지된다. */
public final class InboxForm {
    private static final String CODES = "codes";
    private static final String SEPARATOR = ",";   // 코드는 영문 대문자와 숫자뿐이라 쉼표가 섞일 수 없다

    private InboxForm() {
    }

    public static String toBody(List<LetterCode> codes) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("count", String.valueOf(codes.size()));
        fields.put(CODES, codes.stream().map(LetterCode::value).collect(Collectors.joining(SEPARATOR)));
        return FormCodec.encode(fields);
    }

    /** @throws IllegalArgumentException 형식이 올바르지 않거나 항목이 빠졌을 때 */
    public static List<LetterCode> fromBody(String body) {
        String joined = FormCodec.decode(body).get(CODES);
        if (joined == null) throw new IllegalArgumentException("필수 항목이 없습니다.");

        List<LetterCode> codes = new ArrayList<>();
        try {
            if (!joined.isEmpty()) {
                for (String raw : joined.split(SEPARATOR, -1)) codes.add(LetterCode.of(raw));
            }
        } catch (LetterException e) {
            throw new IllegalArgumentException("편지 목록 형식이 올바르지 않습니다.", e);
        }
        return codes;
    }
}
