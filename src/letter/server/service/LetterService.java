package letter.server.service;

import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LetterException;
import letter.domain.Password;
import letter.server.http.RateLimiter;
import letter.server.storage.LetterEntry;
import letter.server.storage.LetterRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 편지 발송/조회/받은 편지 목록의 업무 규칙. HTTP도 파일도 모른다.
 * 필요한 부품(저장소, 코드 생성기, 시계)은 생성자로 주입받는다(의존성 주입).
 */
public class LetterService {
    private static final int MAX_CODE_ATTEMPTS = 10;
    private static final String REPLY_AUTHOR_SUFFIX = "에 대한 답장입니다.";
    /** 답장 편지에만 붙는 작성자 표기. 일반 편지가 이 이름을 쓰면 답장인 척 할 수 있어 막는다. */
    private static final Pattern RESERVED_AUTHOR = Pattern.compile("[A-Za-z2-9]{8}" + Pattern.quote(REPLY_AUTHOR_SUFFIX));

    private final LetterRepository repository;
    private final CodeGenerator codeGenerator;
    private final Clock clock;
    private final RateLimiter passwordAttempts;

    /** 비밀번호를 대 보는 횟수 제한: 편지 하나당 10분에 10번까지 */
    public LetterService(LetterRepository repository, CodeGenerator codeGenerator, Clock clock) {
        this(repository, codeGenerator, clock, new RateLimiter(10, Duration.ofMinutes(10), clock));
    }

    /** @param passwordAttempts 비밀번호 시도 횟수 제한. (키: 편지 코드) 무작위로 비밀번호를 맞춰 보는 것을 막는다. */
    public LetterService(LetterRepository repository, CodeGenerator codeGenerator, Clock clock, RateLimiter passwordAttempts) {
        this.repository = Objects.requireNonNull(repository);
        this.codeGenerator = Objects.requireNonNull(codeGenerator);
        this.clock = Objects.requireNonNull(clock);
        this.passwordAttempts = Objects.requireNonNull(passwordAttempts);
    }

    /**
     * 로그인한 사용자가 쓴 편지를 저장하고 고유 코드를 돌려준다. 편지의 보낸 사람은 항상 {@code sender}이다.
     *
     * @throws LetterException 보낸 사람의 이름이 답장 전용 표기("코드에 대한 답장입니다.")와 같으면 INVALID
     */
    public LetterCode send(DiscordUser sender, LetterDraft draft) {
        return send(sender, draft, null, null);
    }

    /**
     * 로그인한 사용자가 쓴 편지를 저장하고 고유 코드를 돌려준다.
     *
     * @param sender  보낸 사람. 로그인으로 확인된 사용자이며, 편지에 "from:"으로 남는다.
     * @param replyTo 답장을 받을 디스코드 ID. 답장을 받지 않으면 null.
     * @param to      디스코드로 전달하는 편지의 받는 사람. 읽는 사람에게 "to:"로 보이고, 그 사람의 "받은 편지 목록"에 올라간다.
     *                (디스코드로 보내지 않으면 null)
     */
    public LetterCode send(DiscordUser sender, LetterDraft draft, DiscordId replyTo, DiscordUser to) {
        Objects.requireNonNull(sender, "sender");
        String author = sender.authorName();
        if (RESERVED_AUTHOR.matcher(author).matches()) {
            // 읽는 사람 화면에는 이름 대신 {이름}(@핸들, ID)가 나오지만, DM과 옛 프로그램에서는 이름만 보이므로 막아 둔다
            throw LetterException.invalid("디스코드 표시 이름이 답장 편지 전용 표기와 같아 편지를 보낼 수 없습니다. 이름을 바꾼 뒤 다시 시도해 주세요.");
        }
        return store(author, sender, draft, replyTo, to, null);
    }

    /**
     * 원본 편지에 답장할 수 있으면, 답장 코드를 전달할 디스코드 ID를 돌려준다.
     *
     * @throws LetterException 원본이 없으면 NOT_FOUND, 답장을 받지 않는 편지면 INVALID
     */
    public DiscordId replyTargetOf(LetterCode original) {
        return replyTargetOf(original, null);
    }

    /** 비밀번호가 걸린 편지라면 비밀번호도 맞아야 한다. (읽은 사람만 답장할 수 있게) */
    public DiscordId replyTargetOf(LetterCode original, String rawPassword) {
        DiscordId replyTo = open(original, rawPassword).replyTo();
        if (replyTo == null) {
            throw LetterException.invalid("이 편지는 답장을 받지 않습니다.");
        }
        return replyTo;
    }

    /**
     * 답장 편지를 새 고유 코드로 저장한다. 작성자는 "{원본코드}에 대한 답장입니다."로 고정되고,
     * 답장에는 답장받을 ID가 없으므로 다시 답장할 수 없다.
     *
     * @param deliveredTo 답장 코드를 전달할 디스코드 ID. 그 사람의 "받은 편지 목록"에 올라간다.
     */
    public LetterCode saveReply(LetterCode original, LetterDraft reply, DiscordId deliveredTo) {
        return store(original.value() + REPLY_AUTHOR_SUFFIX, null, reply, null, null, deliveredTo);
    }

    /**
     * 해당 사용자에게 전달된 편지의 코드를 최신순(작성 시각이 늦은 것부터)으로 돌려준다.
     * 디스코드로 받은 편지와 답장 편지가 모두 들어 있고, 편지 내용은 읽지 않는다.
     */
    public List<LetterCode> inboxOf(DiscordId owner) {
        return repository.findByAddressee(owner).stream()
                .sorted(LetterEntry.NEWEST_FIRST)
                .map(LetterEntry::code)
                .toList();
    }

    private LetterCode store(String author, DiscordUser from, LetterDraft draft, DiscordId replyTo, DiscordUser to,
                             DiscordId deliveredTo) {
        String passwordHash = draft.password() == null ? null : PasswordHasher.hash(draft.password());
        Letter letter = new Letter(author, draft.content(),
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES), replyTo, to, passwordHash, from, deliveredTo);

        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            LetterCode code = codeGenerator.next();
            if (repository.saveIfAbsent(code, letter)) {
                return code;
            }
        }
        throw LetterException.storageFailure("고유 코드를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.", null);
    }

    /**
     * 비밀번호를 확인하고 편지를 돌려준다. 비밀번호가 없는 편지는 rawPassword와 상관없이 바로 열린다.
     *
     * @throws LetterException 편지가 없으면 NOT_FOUND, 비밀번호가 필요한데 없으면 PASSWORD_REQUIRED,
     *                         틀리면 PASSWORD_WRONG, 너무 여러 번 대 보면 RATE_LIMITED
     */
    public Letter open(LetterCode code, String rawPassword) {
        Letter letter = read(code);
        if (!letter.passwordProtected()) return letter;

        if (rawPassword == null || rawPassword.isBlank()) {
            throw LetterException.passwordRequired("비밀번호가 필요한 편지입니다. (최신 편지 프로그램에서 확인해 주세요)");
        }
        if (!passwordAttempts.tryAcquire(code.value())) {
            throw LetterException.rateLimited("비밀번호를 너무 여러 번 입력했습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (!matches(rawPassword, letter.passwordHash())) {
            throw LetterException.passwordWrong("비밀번호가 올바르지 않습니다.");
        }
        return letter;
    }

    /** 만료된 시도 기록을 정리한다. (주기적으로 호출) */
    public void evictExpiredAttempts() {
        passwordAttempts.evictExpired();
    }

    private static boolean matches(String rawPassword, String hash) {
        try {
            return PasswordHasher.matches(new Password(rawPassword), hash);
        } catch (LetterException e) {
            return false;   // 형식이 안 맞는 입력은 맞을 수 없는 비밀번호
        }
    }

    /**
     * 비밀번호 확인 없이 편지를 읽는다. 서버 내부용이며, 사용자에게 내용을 돌려줄 때는 {@link #open}을 쓴다.
     *
     * @throws LetterException 편지가 없으면 Kind.NOT_FOUND */
    public Letter read(LetterCode code) {
        return repository.find(code)
                .orElseThrow(() -> LetterException.notFound("해당 코드의 편지를 찾을 수 없습니다."));
    }
}
