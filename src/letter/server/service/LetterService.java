package letter.server.service;

import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LetterException;
import letter.server.storage.LetterRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 편지 발송/조회의 업무 규칙. HTTP도 파일도 모른다.
 * 필요한 부품(저장소, 코드 생성기, 시계)은 생성자로 주입받는다(의존성 주입).
 */
public class LetterService {
    private static final int MAX_CODE_ATTEMPTS = 10;

    private final LetterRepository repository;
    private final CodeGenerator codeGenerator;
    private final Clock clock;

    public LetterService(LetterRepository repository, CodeGenerator codeGenerator, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.codeGenerator = Objects.requireNonNull(codeGenerator);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 편지를 저장하고 고유 코드를 돌려준다. */
    public LetterCode send(LetterDraft draft) {
        Letter letter = new Letter(draft.author(), draft.content(),
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES));

        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            LetterCode code = codeGenerator.next();
            if (repository.saveIfAbsent(code, letter)) {
                return code;
            }
        }
        throw LetterException.storageFailure("고유 코드를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.", null);
    }

    /** @throws LetterException 편지가 없으면 Kind.NOT_FOUND */
    public Letter read(LetterCode code) {
        return repository.find(code)
                .orElseThrow(() -> LetterException.notFound("해당 코드의 편지를 찾을 수 없습니다."));
    }
}
