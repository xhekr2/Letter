package letter;

import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LetterException;
import letter.domain.LetterException.Kind;
import letter.protocol.FormCodec;
import letter.protocol.LetterForm;
import letter.server.ServerConfig;
import letter.server.http.RateLimiter;
import letter.server.service.CodeGenerator;
import letter.server.service.LetterService;
import letter.server.storage.FileLetterRepository;
import letter.server.storage.InMemoryLetterRepository;
import letter.server.storage.LetterRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 외부 라이브러리 없이 실행되는 간단한 테스트 모음. 실행: java -cp out letter.LetterTests
 * (JUnit을 쓸 수 있다면 각 test(...) 블록을 @Test 메서드로 옮기면 된다.)
 */
public class LetterTests {
    private static int passed = 0;
    private static int failed = 0;

    private interface Check {
        void run() throws Exception;
    }

    // ---- 테스트용 가짜 부품들 (인터페이스 덕분에 쉽게 갈아 끼울 수 있다) ----

    /** 미리 정해 둔 코드를 순서대로 돌려주고, 끝나면 마지막 코드를 반복한다. */
    private static class SequenceCodeGenerator implements CodeGenerator {
        private final String[] codes;
        private int index = 0;

        SequenceCodeGenerator(String... codes) {
            this.codes = codes;
        }

        @Override
        public LetterCode next() {
            return LetterCode.of(codes[Math.min(index++, codes.length - 1)]);
        }
    }

    /** 시간을 마음대로 움직일 수 있는 시계. */
    private static class MutableClock extends Clock {
        private long millis = 0;

        void advanceSeconds(long seconds) {
            millis += seconds * 1000;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-11-02T05:46:30Z"), ZoneId.of("Asia/Seoul"));   // 14:46:30 KST

    public static void main(String[] args) {
        // ---------- 도메인 ----------
        test("LetterDraft: 내용이 비어 있으면 거절", () -> kind(Kind.INVALID, () -> new LetterDraft("a", "  \n ")));
        test("LetterDraft: 이름이 없으면 익명", () -> {
            eq("익명", new LetterDraft(null, "hi").author());
            eq("익명", new LetterDraft("   ", "hi").author());
        });
        test("LetterDraft: 이름의 제어문자 제거와 공백 정리", () -> eq("홍길동", new LetterDraft(" 홍\u001B길동\n", "hi").author()));
        test("LetterDraft: 이름은 50자까지 허용, 51자는 거절", () -> {
            eq(50, new LetterDraft("가".repeat(50), "hi").author().length());
            kind(Kind.INVALID, () -> new LetterDraft("가".repeat(51), "hi"));
        });
        test("LetterCode: 소문자와 공백을 정규화", () -> eq("AB23CD45", LetterCode.of(" ab23cd45 ").value()));
        test("LetterCode: 잘못된 형식은 거절 (경로 조작 포함)", () -> {
            for (String bad : new String[]{"", "ABC", "AB23CD4", "AB23CD456", "../../ET", "AB23CD4!", null}) {
                kind(Kind.INVALID, () -> LetterCode.of(bad));
            }
        });
        test("Letter: 생성 시에도 작성 규칙이 적용됨", () -> {
            Letter letter = new Letter(" ", "내용", LocalDateTime.of(2026, 11, 2, 14, 46));
            eq("익명", letter.author());
            kind(Kind.INVALID, () -> new Letter("a", "", LocalDateTime.now()));
        });

        // ---------- 통신 형식 ----------
        test("FormCodec: 특수문자·한글·줄바꿈 왕복 변환", () -> {
            Map<String, String> in = new LinkedHashMap<>();
            in.put("a", "한글 & = % + 줄\n바꿈");
            in.put("b", "");
            eq(in.get("a"), FormCodec.decode(FormCodec.encode(in)).get("a"));
        });
        test("FormCodec: 잘못된 % 인코딩은 IllegalArgumentException", () -> {
            try {
                FormCodec.decode("content=%ZZ");
            } catch (IllegalArgumentException e) {
                return;
            }
            throw new AssertionError("예외가 발생하지 않음");
        });
        test("LetterForm: 이전 버전이 저장한 파일 형식도 읽을 수 있음", () -> {
            Letter letter = LetterForm.fromBody(
                    "author=%ED%99%8D%EA%B8%B8%EB%8F%99&content=%EC%95%88%EB%85%95%0A%ED%8E%B8%EC%A7%80&createdAt=2026-11-02+14%3A46");
            eq("홍길동", letter.author());
            eq("안녕\n편지", letter.content());
            eq(LocalDateTime.of(2026, 11, 2, 14, 46), letter.createdAt());
        });
        test("LetterForm: 저장했다가 읽으면 같은 편지", () -> {
            Letter letter = new Letter("a", "내용&1", LocalDateTime.of(2026, 1, 2, 3, 4));
            eq(letter, LetterForm.fromBody(LetterForm.toBody(letter)));
        });
        test("LetterForm: 손상된 데이터는 IllegalArgumentException", () -> {
            for (String bad : new String[]{"garbage%ZZ", "author=a", "content=x&createdAt=어제"}) {
                try {
                    LetterForm.fromBody(bad);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                throw new AssertionError("예외가 발생하지 않음: " + bad);
            }
        });

        // ---------- 서비스 (메모리 저장소로 파일 없이 검증) ----------
        test("LetterService: 발송하면 코드가 나오고 같은 코드로 읽을 수 있음", () -> {
            LetterService service = new LetterService(new InMemoryLetterRepository(),
                    new SequenceCodeGenerator("AAAAAAAA"), FIXED_CLOCK);
            LetterCode code = service.send(new LetterDraft("홍길동", "안녕"));
            Letter letter = service.read(code);
            eq("AAAAAAAA", code.value());
            eq("홍길동", letter.author());
            eq(LocalDateTime.of(2026, 11, 2, 14, 46), letter.createdAt());   // 서울 시간, 분 단위
        });
        test("LetterService: 코드가 겹치면 다른 코드로 다시 시도", () -> {
            LetterRepository repo = new InMemoryLetterRepository();
            repo.saveIfAbsent(LetterCode.of("AAAAAAAA"), new Letter("a", "먼저 온 편지", LocalDateTime.now()));
            LetterService service = new LetterService(repo, new SequenceCodeGenerator("AAAAAAAA", "BBBBBBBB"), FIXED_CLOCK);
            eq("BBBBBBBB", service.send(new LetterDraft("b", "새 편지")).value());
            eq("먼저 온 편지", service.read(LetterCode.of("AAAAAAAA")).content());   // 기존 편지는 덮어쓰지 않음
        });
        test("LetterService: 계속 겹치면 STORAGE_FAILURE", () -> {
            LetterRepository repo = new InMemoryLetterRepository();
            repo.saveIfAbsent(LetterCode.of("AAAAAAAA"), new Letter("a", "x", LocalDateTime.now()));
            LetterService service = new LetterService(repo, new SequenceCodeGenerator("AAAAAAAA"), FIXED_CLOCK);
            kind(Kind.STORAGE_FAILURE, () -> service.send(new LetterDraft("b", "y")));
        });
        test("LetterService: 없는 코드는 NOT_FOUND", () -> {
            LetterService service = new LetterService(new InMemoryLetterRepository(),
                    new SequenceCodeGenerator("AAAAAAAA"), FIXED_CLOCK);
            kind(Kind.NOT_FOUND, () -> service.read(LetterCode.of("ZZZZZZZZ")));
        });

        // ---------- 파일 저장소 ----------
        test("FileLetterRepository: 저장·조회·중복 거절·재시작 후 유지", () -> withTempDir(dir -> {
            Letter letter = new Letter("홍길동", "안녕\n편지", LocalDateTime.of(2026, 11, 2, 14, 46));
            LetterCode code = LetterCode.of("AB23CD45");

            LetterRepository repo = new FileLetterRepository(dir);
            eq(true, repo.saveIfAbsent(code, letter));
            eq(false, repo.saveIfAbsent(code, letter));                       // 같은 코드는 덮어쓰지 않음
            eq(letter, repo.find(code).orElseThrow());
            eq(true, repo.find(LetterCode.of("ZZZZZZZZ")).isEmpty());

            eq(letter, new FileLetterRepository(dir).find(code).orElseThrow()); // 새 객체(재시작)로도 읽힘
        }));
        test("FileLetterRepository: 손상된 파일은 CORRUPTED", () -> withTempDir(dir -> {
            Files.writeString(dir.resolve("AAAAAAAA.txt"), "garbage%ZZ", StandardCharsets.UTF_8);
            kind(Kind.CORRUPTED, () -> new FileLetterRepository(dir).find(LetterCode.of("AAAAAAAA")));
        }));

        // ---------- 요청 횟수 제한 ----------
        test("RateLimiter: 한도 초과 거절, 1분 뒤 다시 허용, 키별로 독립", () -> {
            MutableClock clock = new MutableClock();
            RateLimiter limiter = new RateLimiter(3, clock);
            eq(true, limiter.tryAcquire("1.1.1.1"));
            eq(true, limiter.tryAcquire("1.1.1.1"));
            eq(true, limiter.tryAcquire("1.1.1.1"));
            eq(false, limiter.tryAcquire("1.1.1.1"));
            eq(true, limiter.tryAcquire("2.2.2.2"));
            clock.advanceSeconds(61);
            eq(true, limiter.tryAcquire("1.1.1.1"));
        });

        // ---------- 설정 ----------
        test("ServerConfig: 기본값과 인자 덮어쓰기", () -> {
            ServerConfig defaults = ServerConfig.from(Map.of(), new String[0]);
            eq(8080, defaults.port());
            eq(ZoneId.of("Asia/Seoul"), defaults.zone());
            eq(9090, ServerConfig.from(Map.of("PORT", "8081"), new String[]{"9090"}).port());
            eq(8081, ServerConfig.from(Map.of("PORT", "8081"), new String[0]).port());
        });
        test("ServerConfig: 잘못된 값은 IllegalArgumentException", () -> {
            for (Map<String, String> bad : java.util.List.of(
                    Map.of("PORT", "abc"), Map.of("PORT", "70000"), Map.of("TIME_ZONE", "Mars/Base"))) {
                try {
                    ServerConfig.from(bad, new String[0]);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                throw new AssertionError("예외가 발생하지 않음: " + bad);
            }
        });

        System.out.printf("%n결과: %d개 통과, %d개 실패%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 도구

    private static void test(String name, Check check) {
        try {
            check.run();
            passed++;
            System.out.println("[통과] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("[실패] " + name + " -> " + t);
        }
    }

    private static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("기대값=" + expected + ", 실제값=" + actual);
        }
    }

    private static void kind(Kind expected, Check action) {
        try {
            action.run();
        } catch (LetterException e) {
            eq(expected, e.kind());
            return;
        } catch (Exception e) {
            throw new AssertionError("다른 예외가 발생함: " + e);
        }
        throw new AssertionError("예외가 발생하지 않음");
    }

    private interface DirCheck {
        void run(Path dir) throws Exception;
    }

    private static void withTempDir(DirCheck check) throws Exception {
        Path dir = Files.createTempDirectory("letter-test");
        try {
            check.run(dir);
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 임시 폴더 정리 실패는 무시
                    }
                });
            }
        }
    }
}
