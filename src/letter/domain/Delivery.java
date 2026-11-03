package letter.domain;

/** 편지 코드를 디스코드로 전달한 결과. 편지 자체의 저장 여부와는 별개다. */
public enum Delivery {
    /** 디스코드 DM으로 코드를 보냈다. */
    SENT,
    /** 편지는 저장되었지만 디스코드 전달에는 실패했다. (보낸 사람이 코드를 직접 전달해야 한다) */
    FAILED
}
