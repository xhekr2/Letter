package letter.server.storage;

import letter.domain.DiscordId;
import letter.domain.Letter;
import letter.domain.LetterCode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 메모리에만 저장하는 구현. 테스트에서 파일 없이 서비스를 검증할 때 쓴다. */
public class InMemoryLetterRepository implements LetterRepository {
    private record Stored(Letter letter, long order) {
    }

    private final Map<LetterCode, Stored> letters = new ConcurrentHashMap<>();
    private final AtomicLong orders = new AtomicLong();

    @Override
    public boolean saveIfAbsent(LetterCode code, Letter letter) {
        return letters.putIfAbsent(code, new Stored(letter, orders.incrementAndGet())) == null;
    }

    @Override
    public Optional<Letter> find(LetterCode code) {
        return Optional.ofNullable(letters.get(code)).map(Stored::letter);
    }

    @Override
    public List<LetterEntry> findByAddressee(DiscordId addressee) {
        return letters.entrySet().stream()
                .filter(e -> addressee.equals(e.getValue().letter().addressee()))
                .map(e -> new LetterEntry(e.getKey(), e.getValue().letter().createdAt(), e.getValue().order()))
                .toList();
    }
}
