package letter.domain;

import java.util.Objects;

/** 편지를 읽은 결과: 편지 내용과, 이 편지에 답장할 수 있는지 여부. (답장받을 사람의 ID는 포함하지 않는다.) */
public record OpenedLetter(Letter letter, boolean replyable) {

    public OpenedLetter {
        Objects.requireNonNull(letter, "letter");
    }
}
