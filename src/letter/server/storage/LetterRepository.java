package letter.server.storage;

import letter.domain.DiscordId;
import letter.domain.Letter;
import letter.domain.LetterCode;

import java.util.List;
import java.util.Optional;

/** 편지 저장소의 추상화. 서비스는 이 인터페이스만 알고, 실제 저장 방식(파일, 메모리, DB...)은 모른다. */
public interface LetterRepository {

    /**
     * 해당 코드가 비어 있을 때만 저장한다.
     *
     * @return 저장했으면 true, 이미 같은 코드가 있으면 false
     * @throws letter.domain.LetterException 저장 장치 오류일 때 (Kind.STORAGE_FAILURE)
     */
    boolean saveIfAbsent(LetterCode code, Letter letter);

    /**
     * @throws letter.domain.LetterException 읽기 오류(STORAGE_FAILURE) 또는 데이터 손상(CORRUPTED)
     */
    Optional<Letter> find(LetterCode code);

    /**
     * 해당 디스코드 사용자에게 전달된 편지({@link Letter#addressee()})의 목록. 순서는 정해져 있지 않으며,
     * 최신순 정렬은 {@link LetterEntry#NEWEST_FIRST}로 한다. 편지 내용은 읽지 않는다.
     */
    List<LetterEntry> findByAddressee(DiscordId addressee);
}
