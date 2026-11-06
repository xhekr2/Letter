package letter.domain;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 편지를 받을 디스코드 사용자. 보내기 전에 "이 사람이 맞나요?"를 확인할 때 쓴다. */
public record DiscordUser(DiscordId id, String name) {
    public static final String UNKNOWN_NAME = "(이름 없음)";
    /** 조회 서비스가 만드는 이름 형식: "표시 이름 (@핸들)" */
    private static final Pattern NAME_AND_HANDLE = Pattern.compile("^(.*) \\((@[^()]*)\\)$");

    public DiscordUser {
        Objects.requireNonNull(id, "id");
        name = (name == null || name.isBlank()) ? UNKNOWN_NAME : name.strip();
    }

    /**
     * 편지에 "to:"로 보여 주는 표기: {이름}({@핸들}, {디스코드ID}).
     * 이름을 알 수 없으면 ID만, 핸들만 있으면 핸들을 이름 자리에도 쓴다.
     */
    public String label() {
        if (name.equals(UNKNOWN_NAME)) return id.value();
        Matcher m = NAME_AND_HANDLE.matcher(name);
        if (m.matches()) return m.group(1) + "(" + m.group(2) + ", " + id.value() + ")";
        if (name.startsWith("@")) return name.substring(1) + "(" + name + ", " + id.value() + ")";
        return name + "(" + id.value() + ")";
    }
}
