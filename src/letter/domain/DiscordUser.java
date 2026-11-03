package letter.domain;

import java.util.Objects;

/** 편지를 받을 디스코드 사용자. 보내기 전에 "이 사람이 맞나요?"를 확인할 때 쓴다. */
public record DiscordUser(DiscordId id, String name) {

    public DiscordUser {
        Objects.requireNonNull(id, "id");
        name = (name == null || name.isBlank()) ? "(이름 없음)" : name.strip();
    }
}
