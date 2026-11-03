package letter.server.discord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 디스코드 REST 호출에 필요한 만큼만 구현한 작은 JSON 도구. (외부 라이브러리를 쓰지 않기 위해)
 * 파싱 결과: 객체 → Map, 배열 → List, 문자열 → String, 숫자 → Double, true/false → Boolean, null → null
 */
final class MiniJson {
    private final String text;
    private int pos = 0;

    private MiniJson(String text) {
        this.text = text;
    }

    /** @throws IllegalArgumentException JSON 형식이 올바르지 않을 때 */
    static Object parse(String json) {
        if (json == null) throw new IllegalArgumentException("JSON이 비어 있습니다.");
        MiniJson parser = new MiniJson(json);
        Object value = parser.value(0);
        parser.skipSpaces();
        if (parser.pos != json.length()) throw parser.error("JSON 뒤에 불필요한 내용이 있습니다.");
        return value;
    }

    /** 문자열을 JSON 문자열 리터럴(따옴표 포함)로 만든다. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static final int MAX_DEPTH = 32;

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw error("JSON이 너무 깊습니다.");
        skipSpaces();
        if (pos >= text.length()) throw error("JSON이 중간에 끝났습니다.");
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;                                   // {
        skipSpaces();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipSpaces();
            if (peek() != '"') throw error("객체의 키는 문자열이어야 합니다.");
            String key = string();
            skipSpaces();
            expect(':');
            map.put(key, value(depth + 1));
            skipSpaces();
            if (peek() == ',') {
                pos++;
            } else {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        pos++;                                   // [
        skipSpaces();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(value(depth + 1));
            skipSpaces();
            if (peek() == ',') {
                pos++;
            } else {
                expect(']');
                return list;
            }
        }
    }

    private String string() {
        pos++;                                   // 여는 따옴표
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) throw error("문자열이 닫히지 않았습니다.");
            char c = text.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) throw error("잘못된 이스케이프입니다.");
            char e = text.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) throw error("잘못된 \\u 이스케이프입니다.");
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("잘못된 \\u 이스케이프입니다.");
                    }
                    pos += 4;
                }
                default -> throw error("알 수 없는 이스케이프입니다.");
            }
        }
    }

    private Object literal(String word, Object result) {
        if (!text.startsWith(word, pos)) throw error("알 수 없는 값입니다.");
        pos += word.length();
        return result;
    }

    private Double number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        if (start == pos) throw error("알 수 없는 값입니다.");
        try {
            return Double.parseDouble(text.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("잘못된 숫자입니다.");
        }
    }

    private void skipSpaces() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) throw error("'" + c + "' 가 필요합니다.");
        pos++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " (위치 " + pos + ")");
    }
}
