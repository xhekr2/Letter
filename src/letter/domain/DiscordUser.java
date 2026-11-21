package letter.domain;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 디스코드 사용자. 받는 사람 확인, 편지의 보낸 사람/받는 사람 표시, 로그인한 사용자를 나타낸다. */
public record DiscordUser(DiscordId id, String name) {
    public static final String UNKNOWN_NAME = "(이름 없음)";
    /** 조회 서비스가 만드는 이름 형식: "표시 이름 (@핸들)" */
    private static final Pattern NAME_AND_HANDLE = Pattern.compile("^(.*) \\((@[^()]*)\\)$");

    public DiscordUser {
        Objects.requireNonNull(id, "id");
        // 이름은 디스코드가 정한 값이지만, 화면·DM 서식을 깨는 제어문자는 미리 지운다
        String cleaned = name == null ? "" : name.replaceAll("\\p{Cntrl}", "").strip();
        name = cleaned.isEmpty() ? UNKNOWN_NAME : cleaned;
    }

    /**
     * 편지에 "from:"/"to:"로 보여 주는 표기: {이름}({@핸들}, {디스코드ID}).
     * 이름을 알 수 없으면 ID만, 핸들만 있으면 핸들을 이름 자리에도 쓴다.
     */
    public String label() {
        if (name.equals(UNKNOWN_NAME)) return id.value();
        Matcher m = NAME_AND_HANDLE.matcher(name);
        if (m.matches()) return m.group(1) + "(" + m.group(2) + ", " + id.value() + ")";
        if (name.startsWith("@")) return name.substring(1) + "(" + name + ", " + id.value() + ")";
        return name + "(" + id.value() + ")";
    }

    /**
     * 편지의 작성자 표시 이름으로 쓰는 짧은 이름: 핸들을 뺀 이름을 {@link Letter#MAX_AUTHOR_CHARS}자까지만.
     * 이름을 알 수 없으면 "익명".
     */
    public String authorName() {
        String shown;
        if (name.equals(UNKNOWN_NAME)) {
            shown = "";
        } else {
            Matcher m = NAME_AND_HANDLE.matcher(name);
            if (m.matches()) shown = m.group(1);
            else shown = name.startsWith("@") ? name.substring(1) : name;
        }
        shown = shown.strip();
        if (shown.codePointCount(0, shown.length()) > Letter.MAX_AUTHOR_CHARS) {
            shown = shown.substring(0, shown.offsetByCodePoints(0, Letter.MAX_AUTHOR_CHARS)).strip();
        }
        return shown.isEmpty() ? Letter.ANONYMOUS : shown;
    }
}
