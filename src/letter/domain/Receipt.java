package letter.domain;

import java.util.Objects;

/** 디스코드 전송 요청의 결과: 발급된 편지 코드와 디스코드 전달 결과. */
public record Receipt(LetterCode code, Delivery delivery) {

    public Receipt {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(delivery, "delivery");
    }
}
