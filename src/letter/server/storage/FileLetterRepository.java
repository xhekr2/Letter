package letter.server.storage;

import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterException;
import letter.protocol.LetterForm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/** 편지 하나당 파일 하나({코드}.txt)로 저장하는 구현. */
public class FileLetterRepository implements LetterRepository {
    private final Path directory;

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
            Files.writeString(file, LetterForm.toBody(letter), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            deleteQuietly(file);                         // 반쯤 쓴 파일이 남지 않게 정리
            throw LetterException.storageFailure("편지를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
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

    private Path fileOf(LetterCode code) {
        return directory.resolve(code.value() + ".txt");   // LetterCode는 형식이 검증되어 경로 조작이 불가능
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 정리 실패는 무시한다
        }
    }
}
