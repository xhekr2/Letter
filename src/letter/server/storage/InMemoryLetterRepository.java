package letter.server.storage;

import letter.domain.Letter;
import letter.domain.LetterCode;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 메모리에만 저장하는 구현. 테스트에서 파일 없이 서비스를 검증할 때 쓴다. */
public class InMemoryLetterRepository implements LetterRepository {
    private final Map<LetterCode, Letter> letters = new ConcurrentHashMap<>();

    @Override
    public boolean saveIfAbsent(LetterCode code, Letter letter) {
        return letters.putIfAbsent(code, letter) == null;
    }

    @Override
    public Optional<Letter> find(LetterCode code) {
        return Optional.ofNullable(letters.get(code));
    }
}
