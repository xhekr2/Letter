package letter.server.storage;

import letter.domain.DiscordId;
import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterException;
import letter.protocol.LetterForm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 편지 하나당 파일 하나({코드}.txt)로 저장하는 구현.
 * "받은 편지 목록"을 파일을 매번 훑지 않고 만들 수 있도록, 시작할 때 폴더를 한 번 읽어 받는 사람별 색인을
 * 메모리에 만들어 두고 편지를 저장할 때마다 갱신한다. (색인에는 코드와 시각만 있고 편지 내용은 없다)
 * 서버가 켜져 있는 동안 파일을 손으로 지우거나 추가하면 색인과 어긋날 수 있으며, 재시작하면 다시 만들어진다.
 */
public class FileLetterRepository implements LetterRepository {
    private static final Logger LOG = Logger.getLogger(FileLetterRepository.class.getName());
    private static final String EXTENSION = ".txt";

    private final Path directory;
    private final Map<DiscordId, List<LetterEntry>> byAddressee = new ConcurrentHashMap<>();
    private final AtomicLong lastOrder = new AtomicLong();

    /** @throws IllegalStateException 폴더를 만들 수 없거나 쓸 수 없을 때 */
    public FileLetterRepository(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("데이터 폴더를 만들 수 없습니다: " + directory.toAbsolutePath(), e);
        }
        if (!Files.isWritable(directory)) {
            throw new IllegalStateException("데이터 폴더에 쓸 수 없습니다: " + directory.toAbsolutePath());
        }
        this.directory = directory;
        buildIndex();
    }

    @Override
    public boolean saveIfAbsent(LetterCode code, Letter letter) {
        Path file = fileOf(code);
        try {
            Files.createFile(file);                      // 원자적으로 코드를 선점해 중복을 막는다
        } catch (FileAlreadyExistsException e) {
            return false;
        } catch (IOException e) {
            throw LetterException.storageFailure("편지를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
        try {
            Files.writeString(file, LetterForm.toStoredBody(letter), StandardCharsets.UTF_8);
        } catch (IOException e) {
            deleteQuietly(file);                         // 반쯤 쓴 파일이 남지 않게 정리
            throw LetterException.storageFailure("편지를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
        index(code, letter, nextOrder());
        return true;
    }

    @Override
    public Optional<Letter> find(LetterCode code) {
        try {
            String stored = Files.readString(fileOf(code), StandardCharsets.UTF_8);
            return Optional.of(LetterForm.fromBody(stored));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw LetterException.storageFailure("편지를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        } catch (IllegalArgumentException e) {
            throw LetterException.corrupted("편지 데이터가 손상되었습니다.");
        }
    }

    @Override
    public List<LetterEntry> findByAddressee(DiscordId addressee) {
        List<LetterEntry> entries = byAddressee.get(addressee);
        return entries == null ? List.of() : List.copyOf(entries);
    }

    // ---------------------------------------------------------------- 색인

    /** 폴더의 편지를 모두 읽어 받는 사람별 색인을 만든다. 손상된 파일은 건너뛰고 로그만 남긴다. */
    private void buildIndex() {
        int indexed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                LetterCode code;
                try {
                    code = LetterCode.of(name.substring(0, name.length() - EXTENSION.length()));
                } catch (LetterException e) {
                    continue;                            // 편지 파일이 아닌 것
                }
                try {
                    Letter letter = LetterForm.fromBody(Files.readString(file, StandardCharsets.UTF_8));
                    long order = Files.getLastModifiedTime(file).toMillis();
                    lastOrder.accumulateAndGet(order, Math::max);
                    if (index(code, letter, order)) indexed++;
                } catch (IOException | IllegalArgumentException e) {
                    LOG.warning("편지 목록 색인에서 건너뜀 (읽지 못하거나 손상됨): " + name);   // 내용은 남기지 않는다
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("데이터 폴더를 읽을 수 없습니다: " + directory.toAbsolutePath(), e);
        }
        LOG.info("받은 편지 색인 " + indexed + "통");
    }

    /** @return 받는 사람이 있는 편지라 색인에 넣었으면 true */
    private boolean index(LetterCode code, Letter letter, long order) {
        DiscordId addressee = letter.addressee();
        if (addressee == null) return false;
        byAddressee.computeIfAbsent(addressee, k -> new CopyOnWriteArrayList<>())
                .add(new LetterEntry(code, letter.createdAt(), order));
        return true;
    }

    /** 저장 순서: 시계가 같은 값을 주거나 뒤로 가도 항상 이전보다 큰 값이 나온다. */
    private long nextOrder() {
        return lastOrder.updateAndGet(prev -> Math.max(prev + 1, System.currentTimeMillis()));
    }

    private Path fileOf(LetterCode code) {
        return directory.resolve(code.value() + EXTENSION);   // LetterCode는 형식이 검증되어 경로 조작이 불가능
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 정리 실패는 무시한다
        }
    }
}
