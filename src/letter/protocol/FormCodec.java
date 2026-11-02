package letter.protocol;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

/** key=value&key=value 형태(URL 인코딩)의 변환. 서버와 클라이언트가 함께 쓴다. */
public final class FormCodec {

    private FormCodec() {
    }

    public static String encode(Map<String, String> fields) {
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> e : fields.entrySet()) {
            joiner.add(urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()));
        }
        return joiner.toString();
    }

    /** @throws IllegalArgumentException 잘못된 % 인코딩일 때 */
    public static Map<String, String> decode(String body) {
        Map<String, String> fields = new HashMap<>();
        if (body == null || body.isEmpty()) return fields;
        for (String pair : body.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            fields.put(urlDecode(pair.substring(0, idx)), urlDecode(pair.substring(idx + 1)));
        }
        return fields;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
