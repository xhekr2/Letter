package letter.server.storage;

import letter.domain.LetterCode;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Objects;

/**
 * 편지 목록의 한 줄: 코드와, 정렬에 필요한 시각 정보만 담는다. (편지 내용은 들어 있지 않다)
 *
 * @param createdAt  편지에 기록된 작성 시각. (분 단위)
 * @param savedOrder 저장된 순서. 클수록 나중에 저장되었다. 작성 시각이 같은(같은 분에 보낸) 편지의 순서를 가리는 데 쓴다.
 */
public record LetterEntry(LetterCode code, LocalDateTime createdAt, long savedOrder) {

    /** 최신순: 작성 시각이 늦은 것부터, 같으면 나중에 저장된 것부터, 그래도 같으면 코드 순. (항상 같은 순서가 나오게) */
    public static final Comparator<LetterEntry> NEWEST_FIRST = Comparator
            .comparing(LetterEntry::createdAt, Comparator.reverseOrder())
            .thenComparing(LetterEntry::savedOrder, Comparator.reverseOrder())
            .thenComparing(e -> e.code().value());

    public LetterEntry {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
