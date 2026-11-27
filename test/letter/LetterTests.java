package letter;

import letter.client.ApiException;
import letter.client.CodeWrongException;
import letter.client.ConsoleView;
import letter.client.HttpLetterApi;
import letter.client.LetterApi;
import letter.client.LetterApp;
import letter.client.LoginRequiredException;
import letter.client.PasswordException;
import letter.domain.Delivery;
import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LetterException;
import letter.domain.LetterException.Kind;
import letter.domain.LoginResult;
import letter.domain.LoginTicket;
import letter.domain.OpenedLetter;
import letter.domain.Password;
import letter.domain.Receipt;
import letter.protocol.AuthForm;
import letter.protocol.DiscordForm;
import letter.protocol.FormCodec;
import letter.protocol.InboxForm;
import letter.protocol.LetterForm;
import letter.server.LetterServer;
import letter.server.ServerConfig;
import letter.server.discord.DisabledDiscordGateway;
import letter.server.discord.DiscordGateway;
import letter.server.discord.DiscordGatewayException;
import letter.server.discord.DiscordGatewayException.Reason;
import letter.server.discord.DiscordRestGateway;
import letter.server.http.RateLimiter;
import letter.server.service.CodeGenerator;
import letter.server.service.DiscordMessages;
import letter.server.service.LetterDeliveryService;
import letter.server.service.LetterService;
import letter.server.service.LoginService;
import letter.server.service.PasswordHasher;
import letter.server.storage.FileLetterRepository;
import letter.server.storage.InMemoryLetterRepository;
import letter.server.storage.LetterEntry;
import letter.server.storage.LetterRepository;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        test("LetterDraft: 내용이 비어 있으면 거절", () -> kind(Kind.INVALID, () -> new LetterDraft("  \n ")));
        test("LetterDraft: 작성자 이름을 받지 않는다 (보낸 사람은 로그인 계정으로만 정해진다)", () -> {
            for (var c : LetterDraft.class.getRecordComponents()) {
                if (c.getName().toLowerCase().contains("author") || c.getName().toLowerCase().contains("from")) {
                    throw new AssertionError("초안에 작성자 항목이 있으면 안 됨: " + c.getName());
                }
            }
        });
        test("Letter.normalizeAuthor: 이름이 없으면 익명", () -> {
            eq("익명", Letter.normalizeAuthor(null));
            eq("익명", Letter.normalizeAuthor("   "));
        });
        test("Letter.normalizeAuthor: 제어문자 제거와 공백 정리", () -> eq("홍길동", Letter.normalizeAuthor(" 홍\u001B길동\n")));
        test("Letter.normalizeAuthor: 50자까지 허용, 51자는 거절", () -> {
            eq(50, Letter.normalizeAuthor("가".repeat(50)).length());
            kind(Kind.INVALID, () -> Letter.normalizeAuthor("가".repeat(51)));
        });
        test("DiscordUser.shortLabel: 이름(@핸들), ID는 뺌 / 이름 없으면 ID", () -> {
            eq("홍길동(@gildong)", new DiscordUser(DiscordId.of(ID_HONG), "홍길동 (@gildong)").shortLabel());
            eq("철수", new DiscordUser(DiscordId.of(ID_CHUL), "철수").shortLabel());
            eq("gildong(@gildong)", new DiscordUser(DiscordId.of(ID_HONG), "@gildong").shortLabel());
            eq(ID_HONG, new DiscordUser(DiscordId.of(ID_HONG), null).shortLabel());
        });
        test("DiscordUser.authorName: 핸들을 뺀 이름, 50자 제한, 없으면 익명", () -> {
            eq("홍길동", new DiscordUser(DiscordId.of(ID_HONG), "홍길동 (@gildong)").authorName());
            eq("gildong", new DiscordUser(DiscordId.of(ID_HONG), "@gildong").authorName());
            eq("익명", new DiscordUser(DiscordId.of(ID_HONG), null).authorName());
            eq(50, new DiscordUser(DiscordId.of(ID_HONG), "가".repeat(80) + " (@x)").authorName().length());
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
            LetterCode code = service.send(SENDER, new LetterDraft("안녕"));
            Letter letter = service.read(code);
            eq("AAAAAAAA", code.value());
            eq("철수", letter.author());                       // 보낸 사람은 로그인한 계정의 이름
            eq(SENDER, letter.from());                         // 확인된 보낸 사람이 편지에 남는다
            eq(LocalDateTime.of(2026, 11, 2, 14, 46), letter.createdAt());   // 서울 시간, 분 단위
        });
        test("LetterService: 코드가 겹치면 다른 코드로 다시 시도", () -> {
            LetterRepository repo = new InMemoryLetterRepository();
            repo.saveIfAbsent(LetterCode.of("AAAAAAAA"), new Letter("a", "먼저 온 편지", LocalDateTime.now()));
            LetterService service = new LetterService(repo, new SequenceCodeGenerator("AAAAAAAA", "BBBBBBBB"), FIXED_CLOCK);
            eq("BBBBBBBB", service.send(SENDER, new LetterDraft("새 편지")).value());
            eq("먼저 온 편지", service.read(LetterCode.of("AAAAAAAA")).content());   // 기존 편지는 덮어쓰지 않음
        });
        test("LetterService: 계속 겹치면 STORAGE_FAILURE", () -> {
            LetterRepository repo = new InMemoryLetterRepository();
            repo.saveIfAbsent(LetterCode.of("AAAAAAAA"), new Letter("a", "x", LocalDateTime.now()));
            LetterService service = new LetterService(repo, new SequenceCodeGenerator("AAAAAAAA"), FIXED_CLOCK);
            kind(Kind.STORAGE_FAILURE, () -> service.send(SENDER, new LetterDraft("y")));
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

        discordTests();
        replyTests();
        passwordTests();
        loginTests();

        System.out.printf("%n결과: %d개 통과, %d개 실패%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ======================================================================== 디스코드 전송

    private static final String ID_HONG = "123456789012345678";
    private static final String ID_KIM = "987654321098765432";
    private static final String ID_CHUL = "111222333444555666";
    /** 테스트에서 편지를 보내는 로그인 사용자. */
    private static final DiscordUser SENDER = new DiscordUser(DiscordId.of(ID_CHUL), "철수");
    /** 가짜 디스코드가 SENDER로 인정하는 승인 코드. */
    private static final String CODE_CHUL = "approval-chul";
    private static final String CHANNEL_ID = "555000111222333444";

    private static void discordTests() {
        // ---------- 도메인 / 통신 형식 ----------
        test("DiscordId: 숫자 17~20자리만 허용 (공백은 정리)", () -> {
            eq(ID_HONG, DiscordId.of(" " + ID_HONG + " ").value());
            eq(20, DiscordId.of("1".repeat(20)).value().length());
            for (String bad : new String[]{"", "abc", "1".repeat(16), "1".repeat(21), "123/../4567890123", "<@" + ID_HONG + ">", "홍길동#1234", null}) {
                kind(Kind.INVALID, () -> DiscordId.of(bad));
            }
        });
        test("DiscordForm: 사용자·전송 결과 왕복 변환, 항목이 빠지면 거절", () -> {
            DiscordUser user = new DiscordUser(DiscordId.of(ID_HONG), "홍길동 (@gildong) & 친구");
            eq(user, DiscordForm.userFromBody(DiscordForm.userToBody(user)));
            Receipt receipt = new Receipt(LetterCode.of("AB23CD45"), Delivery.FAILED);
            eq(receipt, DiscordForm.receiptFromBody(DiscordForm.receiptToBody(receipt)));
            for (String bad : new String[]{"", "name=x", "code=AB23CD45", "code=AB23CD45&delivery=NOPE", "code=zz&delivery=SENT"}) {
                try {
                    DiscordForm.receiptFromBody(bad);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                throw new AssertionError("예외가 발생하지 않음: " + bad);
            }
        });
        test("DiscordMessages: 코드만 담고, 이름의 링크/서식은 코드 조각으로 무력화", () -> {
            String text = DiscordMessages.letterArrived("철수 https://evil.example `x`", LetterCode.of("AB23CD45"));
            has(text, "`AB23CD45`");
            has(text, "`철수 https://evil.example 'x'`");
        });

        // ---------- 서비스 (가짜 디스코드) ----------
        test("LetterDeliveryService: 저장 후 DM으로 코드만 전달 (편지 내용은 DM에 없음)", () -> {
            FakeDiscord discord = new FakeDiscord();
            LetterService letters = new LetterService(new InMemoryLetterRepository(), new SequenceCodeGenerator("AAAAAAAA"), FIXED_CLOCK);
            LetterDeliveryService delivery = new LetterDeliveryService(letters, discord, new RateLimiter(3, Duration.ofMinutes(10), FIXED_CLOCK));

            Receipt receipt = delivery.sendTo(SENDER, new LetterDraft("비밀 내용"), DiscordId.of(ID_HONG));
            eq(Delivery.SENT, receipt.delivery());
            eq("비밀 내용", letters.read(receipt.code()).content());
            eq(1, discord.dms.size());
            eq(ID_HONG, discord.dms.get(0)[0]);
            has(discord.dms.get(0)[1], "AAAAAAAA");
            lacks(discord.dms.get(0)[1], "비밀 내용");
        });
        test("LetterDeliveryService: DM이 실패해도 편지는 저장되고 FAILED로 알림", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.dmFailure = DiscordGatewayException.Reason.DM_NOT_ALLOWED;
            LetterService letters = new LetterService(new InMemoryLetterRepository(), new SequenceCodeGenerator("AAAAAAAA"), FIXED_CLOCK);
            LetterDeliveryService delivery = new LetterDeliveryService(letters, discord, new RateLimiter(3, Duration.ofMinutes(10), FIXED_CLOCK));

            Receipt receipt = delivery.sendTo(SENDER, new LetterDraft("안녕"), DiscordId.of(ID_HONG));
            eq(Delivery.FAILED, receipt.delivery());
            eq("안녕", letters.read(receipt.code()).content());
        });
        test("LetterDeliveryService: 같은 사람에게 너무 자주 보내면 RATE_LIMITED (편지도 저장 안 함)", () -> {
            MutableClock clock = new MutableClock();
            FakeDiscord discord = new FakeDiscord();
            LetterService letters = new LetterService(new InMemoryLetterRepository(), new SequenceCodeGenerator("AAAAAAAA", "BBBBBBBB", "CCCCCCCC", "DDDDDDDD", "EEEEEEEE"), FIXED_CLOCK);
            LetterDeliveryService delivery = new LetterDeliveryService(letters, discord, new RateLimiter(3, Duration.ofMinutes(10), clock));
            DiscordId target = DiscordId.of(ID_HONG);
            for (int i = 0; i < 3; i++) delivery.sendTo(SENDER, new LetterDraft("x"), target);

            kind(Kind.RATE_LIMITED, () -> delivery.sendTo(SENDER, new LetterDraft("x"), target));
            kind(Kind.NOT_FOUND, () -> letters.read(LetterCode.of("DDDDDDDD")));        // 4번째 편지는 저장되지 않음
            eq(3, discord.dms.size());
            delivery.sendTo(SENDER, new LetterDraft("x"), DiscordId.of("987654321098765432"));   // 다른 사람에게는 가능
            clock.advanceSeconds(601);
            delivery.sendTo(SENDER, new LetterDraft("x"), target);                            // 10분 뒤에는 다시 가능
        });
        test("LetterDeliveryService: 조회 — 없는 사용자 NOT_FOUND, 기능이 꺼져 있으면 UNAVAILABLE", () -> {
            FakeDiscord discord = new FakeDiscord();
            LetterService letters = new LetterService(new InMemoryLetterRepository(), new SequenceCodeGenerator("AAAAAAAA"), FIXED_CLOCK);
            RateLimiter limiter = new RateLimiter(3, Duration.ofMinutes(10), FIXED_CLOCK);
            kind(Kind.NOT_FOUND, () -> new LetterDeliveryService(letters, discord, limiter).lookup(DiscordId.of(ID_HONG)));
            kind(Kind.UNAVAILABLE, () -> new LetterDeliveryService(letters, new DisabledDiscordGateway(), limiter).lookup(DiscordId.of(ID_HONG)));
        });

        // ---------- 디스코드 REST 구현 (가짜 디스코드 서버로 검증) ----------
        test("DiscordRestGateway: 사용자 조회 — 봇 인증 헤더, 이름 조합, JSON 이스케이프 해석", () -> {
            try (FakeDiscordRest rest = new FakeDiscordRest()) {
                rest.userBody = "{\"id\":\"" + ID_HONG + "\",\"username\":\"gil\",\"global_name\":\"\\ud64d\\uae38\\ub3d9 \\\"A\\\"\","
                        + "\"avatar_decoration_data\":{\"asset\":\"x\",\"id\":\"1\"},\"flags\":0}";
                DiscordGateway gateway = new DiscordRestGateway("TESTTOKEN", rest.baseUrl());
                eq("홍길동 \"A\" (@gil)", gateway.findUser(DiscordId.of(ID_HONG)).orElseThrow().name());
                has(rest.requests.get(0), "GET /users/" + ID_HONG);
                has(rest.requests.get(0), "auth=Bot TESTTOKEN");
                has(rest.requests.get(0), "ua=DiscordBot (");

                rest.userBody = "{\"username\":\"onlyname\",\"global_name\":null}";
                eq("@onlyname", gateway.findUser(DiscordId.of(ID_HONG)).orElseThrow().name());
            }
        });
        test("DiscordRestGateway: 없는 사용자(404)는 빈 값", () -> {
            try (FakeDiscordRest rest = new FakeDiscordRest()) {
                rest.userStatus = 404;
                rest.userBody = "{\"message\":\"Unknown User\",\"code\":10013}";
                eq(true, new DiscordRestGateway("T", rest.baseUrl()).findUser(DiscordId.of(ID_HONG)).isEmpty());
            }
        });
        test("DiscordRestGateway: DM 전송 — 채널 열기 후 메시지 전송, 멘션 차단과 JSON 이스케이프", () -> {
            try (FakeDiscordRest rest = new FakeDiscordRest()) {
                new DiscordRestGateway("TESTTOKEN", rest.baseUrl())
                        .sendDirectMessage(DiscordId.of(ID_HONG), "💌 \"안녕\"\n줄바꿈 \\ 탭\t끝");
                eq(2, rest.requests.size());
                has(rest.requests.get(0), "POST /users/@me/channels");
                has(rest.requests.get(0), "body={\"recipient_id\":\"" + ID_HONG + "\"}");
                has(rest.requests.get(1), "POST /channels/" + CHANNEL_ID + "/messages");
                has(rest.requests.get(1), "auth=Bot TESTTOKEN");
                has(rest.requests.get(1), "\"content\":\"💌 \\\"안녕\\\"\\n줄바꿈 \\\\ 탭\\t끝\"");
                has(rest.requests.get(1), "\"allowed_mentions\":{\"parse\":[]}");
            }
        });
        test("DiscordRestGateway: 오류 응답을 원인별로 구분 (403/50007=DM 불가, 401=설정 오류, 429·5xx=일시 장애)", () -> {
            Object[][] cases = {
                    {403, "{\"message\":\"Missing Access\",\"code\":50001}", Reason.DM_NOT_ALLOWED},
                    {400, "{\"message\":\"Cannot send messages to this user\",\"code\":50007}", Reason.DM_NOT_ALLOWED},
                    {401, "{\"message\":\"401: Unauthorized\",\"code\":0}", Reason.NOT_CONFIGURED},
                    {429, "{\"message\":\"You are being rate limited.\",\"retry_after\":1.0}", Reason.UNAVAILABLE},
                    {500, "oops", Reason.UNAVAILABLE},
            };
            for (Object[] c : cases) {
                try (FakeDiscordRest rest = new FakeDiscordRest()) {
                    rest.messageStatus = (Integer) c[0];
                    rest.messageBody = (String) c[1];
                    DiscordGateway gateway = new DiscordRestGateway("T", rest.baseUrl());
                    reason((Reason) c[2], () -> gateway.sendDirectMessage(DiscordId.of(ID_HONG), "hi"));
                }
            }
        });
        test("DiscordRestGateway: JSON이 아닌 응답이나 연결 실패는 UNAVAILABLE", () -> {
            try (FakeDiscordRest rest = new FakeDiscordRest()) {
                rest.channelBody = "<html>bad gateway</html>";
                DiscordGateway gateway = new DiscordRestGateway("T", rest.baseUrl());
                reason(Reason.UNAVAILABLE, () -> gateway.sendDirectMessage(DiscordId.of(ID_HONG), "hi"));
            }
            int closedPort;
            try (ServerSocket socket = new ServerSocket(0)) {
                closedPort = socket.getLocalPort();
            }
            DiscordGateway unreachable = new DiscordRestGateway("T", "http://127.0.0.1:" + closedPort);
            reason(Reason.UNAVAILABLE, () -> unreachable.findUser(DiscordId.of(ID_HONG)));
        });
        test("DisabledDiscordGateway: 항상 NOT_CONFIGURED", () -> {
            reason(Reason.NOT_CONFIGURED, () -> new DisabledDiscordGateway().findUser(DiscordId.of(ID_HONG)));
            reason(Reason.NOT_CONFIGURED, () -> new DisabledDiscordGateway().sendDirectMessage(DiscordId.of(ID_HONG), "x"));
        });

        // ---------- 설정 / 요청 제한 ----------
        test("ServerConfig: DISCORD_BOT_TOKEN — 없거나 비면 꺼짐, 있으면 켜짐, toString에는 토큰이 없음", () -> {
            eq(false, ServerConfig.from(Map.of(), new String[0]).discordEnabled());
            eq(false, ServerConfig.from(Map.of("DISCORD_BOT_TOKEN", "   "), new String[0]).discordEnabled());
            ServerConfig on = ServerConfig.from(Map.of("DISCORD_BOT_TOKEN", " SECRET.TOKEN "), new String[0]);
            eq(true, on.discordEnabled());
            eq("SECRET.TOKEN", on.discordBotToken());
            lacks(on.toString(), "SECRET");
        });
        test("RateLimiter: 창 길이를 지정할 수 있음 (10분 창)", () -> {
            MutableClock clock = new MutableClock();
            RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(10), clock);
            eq(true, limiter.tryAcquire("k"));
            clock.advanceSeconds(120);
            eq(false, limiter.tryAcquire("k"));
            clock.advanceSeconds(481);
            eq(true, limiter.tryAcquire("k"));
        });

        // ---------- 실제 서버 + 실제 클라이언트 + 콘솔 흐름 (가짜 디스코드만 끼워 넣음) ----------
        String sendFlow = "3\n안녕하세요\n두번째 줄\n/end\n\ny\n" + ID_HONG + "\n";

        test("[E2E] 메뉴 3: 편지 작성 → ID 입력 → 이름 확인 → 봇이 코드만 DM 전달", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동 (@gildong)");
            withServer(discord, (server, api, dir) -> {
                String out = runApp(api, sendFlow + "y\n5\n");
                has(out, "3. 디스코드로 편지 전송하기");
                has(out, "받는 사람: 홍길동 (@gildong)");
                has(out, "이 사람에게 보내는 게 맞나요?");
                has(out, "님에게 디스코드로 고유 코드를 전달했어요.");

                String code = codeIn(out);
                eq(1, discord.dms.size());
                eq(ID_HONG, discord.dms.get(0)[0]);
                has(discord.dms.get(0)[1], code);
                lacks(discord.dms.get(0)[1], "안녕하세요");                        // 편지 내용은 DM에 없다
                Letter letter = api.read(LetterCode.of(code));                      // 내 프로그램(API)으로 읽힌다
                eq("철수", letter.author());
                eq("안녕하세요\n두번째 줄", letter.content());
            });
        });
        test("[E2E] 받는 사람이 아니라고 답하면 편지를 보내지 않음", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동");
            withServer(discord, (server, api, dir) -> {
                String out = runApp(api, sendFlow + "n\n\n5\n");                    // 'n' 후 ID를 비워 취소
                has(out, "다시 입력해 주세요.");
                has(out, "발송을 취소했습니다.");
                eq(0, discord.dms.size());
                eq(0, countLetters(dir));
            });
        });
        test("[E2E] 없는 디스코드 ID는 안내하고 편지를 보내지 않음", () -> {
            FakeDiscord discord = new FakeDiscord();
            withServer(discord, (server, api, dir) -> {
                String out = runApp(api, "3\n안녕\n/end\n\ny\n111111111111111111\n\n5\n");
                has(out, "해당 ID의 디스코드 사용자를 찾을 수 없습니다.");
                eq(0, discord.dms.size());
                eq(0, countLetters(dir));
            });
        });
        test("[E2E] ID 형식이 틀리면 서버에 묻기 전에 안내", () -> {
            FakeDiscord discord = new FakeDiscord();
            withServer(discord, (server, api, dir) -> {
                String out = runApp(api, "3\n안녕\n/end\n\ny\nabc\n홍길동#1234\n\n5\n");
                has(out, "올바르지 않은 디스코드 ID입니다.");
                eq(0, discord.letterLookups());
                eq(0, countLetters(dir));
            });
        });
        test("[E2E] 디스코드 전달이 실패해도 편지는 발송되고 코드를 직접 전달하도록 안내", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동");
            discord.dmFailure = DiscordGatewayException.Reason.DM_NOT_ALLOWED;
            withServer(discord, (server, api, dir) -> {
                String out = runApp(api, sendFlow + "y\n5\n");
                has(out, "디스코드로 코드를 전달하지 못했어요.");
                has(out, "직접 전달해 주세요.");
                eq("안녕하세요\n두번째 줄", api.read(LetterCode.of(codeIn(out))).content());
                eq(0, discord.dms.size());
            });
        });
        test("[E2E] 같은 사람에게 연속으로 보내면 4번째부터 제한", () -> {
            FakeDiscord discord = new FakeDiscord();
            withServer(discord, (server, api, dir) -> {
                LetterDraft draft = new LetterDraft("안녕");
                for (int i = 0; i < 3; i++) eq(Delivery.SENT, api.sendToDiscord(draft, DiscordId.of(ID_HONG)).delivery());
                try {
                    api.sendToDiscord(draft, DiscordId.of(ID_HONG));
                    throw new AssertionError("제한되지 않음");
                } catch (ApiException e) {
                    has(e.getMessage(), "너무 자주");
                }
                eq(3, countLetters(dir));
            });
        });
        test("[E2E] 기존 메뉴(편지 쓰기·편지 확인·종료)는 그대로 동작", () -> {
            withServer(new FakeDiscord(), (server, api, dir) -> {
                String out = runApp(api, "1\n그냥 편지\n/end\n\ny\n5\n");
                has(out, "프로그램을 종료합니다.");
                String code = codeIn(out);
                String shown = runApp(api, "2\n" + code + "\n5\n");
                has(shown, "from: 철수(" + ID_CHUL + ")");
                has(shown, "그냥 편지");
                has(runApp(api, "9\n5\n"), "잘못된 입력입니다.");
            });
        });
    }

    // ---------------------------------------------------------------- 답장 기능 테스트

    private static LetterDeliveryService replyDelivery(FakeDiscord discord, LetterRepository repo, Clock limiterClock, String... codes) {
        LetterService letters = new LetterService(repo, new SequenceCodeGenerator(codes), FIXED_CLOCK);
        return new LetterDeliveryService(letters, discord, new RateLimiter(3, Duration.ofMinutes(10), limiterClock));
    }

    private static void replyTests() {
        // ---------- 통신/저장 형식 ----------
        test("답장[형식]: 읽는 사람에게는 replyable만 보내고 답장받을 ID는 절대 보내지 않음", () -> {
            Letter letter = new Letter("철수", "안녕", LocalDateTime.of(2026, 11, 2, 14, 46), DiscordId.of(ID_KIM));
            String transfer = LetterForm.toBody(letter);
            lacks(transfer, ID_KIM);
            eq(true, LetterForm.replyableFromBody(transfer));
            eq(null, LetterForm.fromBody(transfer).replyTo());
            lacks(LetterForm.toBody(new Letter("철수", "안녕", letter.createdAt())), "replyable");
            eq(false, LetterForm.replyableFromBody(LetterForm.toBody(new Letter("철수", "안녕", letter.createdAt()))));
        });
        test("답장[형식]: 저장 형식은 답장받을 ID를 보존하고, 옛 파일(항목 없음)도 그대로 읽힘", () -> {
            Letter letter = new Letter("철수", "안녕", LocalDateTime.of(2026, 11, 2, 14, 46), DiscordId.of(ID_KIM));
            eq(letter, LetterForm.fromBody(LetterForm.toStoredBody(letter)));
            Letter old = LetterForm.fromBody("author=a&content=hi&createdAt=2026-11-02+14%3A46");
            eq(null, old.replyTo());
            eq(false, old.replyable());
        });
        test("답장[DM 문구]: 답장한 사람과 새 코드를 알리고 편지 내용은 없음", () -> {
            String text = DiscordMessages.replyArrived("철수(@chul)", LetterCode.of("BBBBBBBB"));
            eq("💌`철수(@chul)`님이 답장했어요!\n고유 코드: `BBBBBBBB`\n\n[편지 프로그램 다운로드 하기](<https://github.com/xhekr2/Letter>)", text);
        });

        // ---------- 서비스 ----------
        test("답장[서비스]: 일반 편지가 '코드에 대한 답장입니다.' 이름을 쓰면 거절 (답장 위조 방지)", () -> {
            LetterService letters = new LetterService(new InMemoryLetterRepository(), new SequenceCodeGenerator("AAAAAAAA"), FIXED_CLOCK);
            DiscordUser faker = new DiscordUser(DiscordId.of(ID_KIM), "AAAAAAAA에 대한 답장입니다.");
            DiscordUser fakerLower = new DiscordUser(DiscordId.of(ID_KIM), "aaaaaaaa에 대한 답장입니다. (@fake)");
            DiscordUser similar = new DiscordUser(DiscordId.of(ID_KIM), "AAAAAAAA 답장");
            kind(Kind.INVALID, () -> letters.send(faker, new LetterDraft("x")));
            kind(Kind.INVALID, () -> letters.send(fakerLower, new LetterDraft("x")));
            eq("AAAAAAAA", letters.send(similar, new LetterDraft("x")).value());   // 비슷한 이름은 허용
        });
        test("답장[서비스]: 답장은 새 코드·고정 작성자·작성 시각을 갖고, 저장된 ID로 DM이 가며, 답장에는 답장할 수 없음", () -> {
            FakeDiscord discord = new FakeDiscord();
            InMemoryLetterRepository repo = new InMemoryLetterRepository();
            LetterDeliveryService delivery = replyDelivery(discord, repo, FIXED_CLOCK, "AAAAAAAA", "BBBBBBBB");
            LetterService reader = new LetterService(repo, new SequenceCodeGenerator("ZZZZZZZZ"), FIXED_CLOCK);

            delivery.sendTo(SENDER, new LetterDraft("비밀 내용"), DiscordId.of(ID_HONG), DiscordId.of(ID_KIM));
            Receipt receipt = delivery.reply(SENDER, LetterCode.of("AAAAAAAA"), "답장 내용");

            eq("BBBBBBBB", receipt.code().value());
            eq(Delivery.SENT, receipt.delivery());
            Letter reply = reader.read(receipt.code());
            eq("AAAAAAAA에 대한 답장입니다.", reply.author());
            eq("답장 내용", reply.content());
            eq(LocalDateTime.of(2026, 11, 2, 14, 46), reply.createdAt());
            eq(false, reply.replyable());
            eq(2, discord.dms.size());
            eq(ID_HONG, discord.dms.get(0)[0]);                                // 원래 편지는 받는 사람에게
            eq(ID_KIM, discord.dms.get(1)[0]);                                 // 답장 코드는 저장된 답장받을 ID로
            has(discord.dms.get(1)[1], "💌`철수`님이 답장했어요!");
            has(discord.dms.get(1)[1], "`BBBBBBBB`");
            lacks(discord.dms.get(1)[1], "답장 내용");
            kind(Kind.INVALID, () -> delivery.reply(SENDER, receipt.code(), "또 답장"));   // 답장에는 답장 불가
        });
        test("답장[서비스]: 답장을 받지 않는 편지·없는 편지·빈 내용은 거절하고 아무것도 저장/전송하지 않음", () -> {
            FakeDiscord discord = new FakeDiscord();
            InMemoryLetterRepository repo = new InMemoryLetterRepository();
            LetterDeliveryService delivery = replyDelivery(discord, repo, FIXED_CLOCK, "AAAAAAAA", "BBBBBBBB", "CCCCCCCC");
            delivery.sendTo(SENDER, new LetterDraft("안녕"), DiscordId.of(ID_HONG));          // 답장 없음
            delivery.sendTo(SENDER, new LetterDraft("안녕"), DiscordId.of(ID_HONG), DiscordId.of(ID_KIM));
            int dms = discord.dms.size();

            kind(Kind.INVALID, () -> delivery.reply(SENDER, LetterCode.of("AAAAAAAA"), "답장"));
            kind(Kind.NOT_FOUND, () -> delivery.reply(SENDER, LetterCode.of("QQQQQQQQ"), "답장"));
            kind(Kind.INVALID, () -> delivery.reply(SENDER, LetterCode.of("BBBBBBBB"), "  \n "));
            eq(dms, discord.dms.size());
            eq(false, repo.find(LetterCode.of("CCCCCCCC")).isPresent());
        });
        test("답장[서비스]: DM이 실패해도 답장은 저장되고 FAILED", () -> {
            FakeDiscord discord = new FakeDiscord();
            InMemoryLetterRepository repo = new InMemoryLetterRepository();
            LetterDeliveryService delivery = replyDelivery(discord, repo, FIXED_CLOCK, "AAAAAAAA", "BBBBBBBB");
            delivery.sendTo(SENDER, new LetterDraft("안녕"), DiscordId.of(ID_HONG), DiscordId.of(ID_KIM));
            discord.dmFailure = DiscordGatewayException.Reason.DM_NOT_ALLOWED;
            Receipt receipt = delivery.reply(SENDER, LetterCode.of("AAAAAAAA"), "답장");
            eq(Delivery.FAILED, receipt.delivery());
            eq("답장", repo.find(receipt.code()).orElseThrow().content());
        });
        test("답장[서비스]: 답장받을 사람에게 DM이 너무 몰리면 RATE_LIMITED (저장 안 함)", () -> {
            FakeDiscord discord = new FakeDiscord();
            InMemoryLetterRepository repo = new InMemoryLetterRepository();
            LetterDeliveryService delivery = replyDelivery(discord, repo, FIXED_CLOCK, "AAAAAAAA", "BBBBBBB2", "BBBBBBB3", "BBBBBBB4", "BBBBBBB5");
            delivery.sendTo(SENDER, new LetterDraft("안녕"), DiscordId.of(ID_HONG), DiscordId.of(ID_KIM));   // 홍길동 1/3
            delivery.reply(SENDER, LetterCode.of("AAAAAAAA"), "1");                                                 // 김 1/3
            delivery.reply(SENDER, LetterCode.of("AAAAAAAA"), "2");                                                 // 김 2/3
            delivery.reply(SENDER, LetterCode.of("AAAAAAAA"), "3");                                                 // 김 3/3
            kind(Kind.RATE_LIMITED, () -> delivery.reply(SENDER, LetterCode.of("AAAAAAAA"), "4"));
            eq(false, repo.find(LetterCode.of("BBBBBBB5")).isPresent());
        });

        // ---------- 실제 서버 ----------
        test("답장[서버]: 편지 조회 응답에 답장받을 ID가 없고, 파일에는 저장되며, 옛 파일도 읽힘", () -> {
            FakeDiscord discord = new FakeDiscord();
            withServer(discord, (server, api, dir) -> {
                Receipt receipt = api.sendToDiscord(new LetterDraft("안녕"), DiscordId.of(ID_HONG), DiscordId.of(ID_KIM));
                OpenedLetter opened = api.open(receipt.code());
                eq(true, opened.replyable());
                eq("안녕", api.read(receipt.code()).content());                       // 기존 read도 그대로
                String raw = java.net.http.HttpClient.newHttpClient().send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/letters/" + receipt.code())).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString()).body();
                lacks(raw, ID_KIM);
                has(Files.readString(dir.resolve(receipt.code() + ".txt")), ID_KIM);

                Files.writeString(dir.resolve("OLDOLDOL.txt"), "author=a&content=hi&createdAt=2026-11-02+14%3A46");
                eq(false, api.open(LetterCode.of("OLDOLDOL")).replyable());
                kind(Kind.INVALID, () -> {
                    try {
                        api.reply(LetterCode.of("OLDOLDOL"), "답장");
                    } catch (ApiException e) {
                        throw LetterException.invalid(e.getMessage());
                    }
                });
            });
        });
        test("답장[서버]: replyTo는 디스코드 전송과 함께일 때만 허용 (아니면 400, 저장 안 함)", () -> {
            withServer(new FakeDiscord(), (server, api, dir) -> {
                for (String body : new String[]{
                        "author=a&content=hi&replyTo=" + ID_KIM,                                   // 수신자 없음
                        "author=a&content=hi&discordId=" + ID_HONG + "&replyTo=abc"}) {            // 형식 오류
                    var res = http("POST", "http://127.0.0.1:" + server.port() + "/letters", body);
                    eq(400, res.statusCode());
                }
                eq(0, countLetters(dir));
            });
        });
        test("답장[서버]: 메서드/주소 오류 — 답장 주소는 POST만, 조회 주소는 GET만", () -> {
            withServer(new FakeDiscord(), (server, api, dir) -> {
                String base = "http://127.0.0.1:" + server.port() + "/letters/AAAAAAAA";
                eq(405, http("GET", base + "/replies", null).statusCode());
                eq(404, http("POST", base + "/replies", "content=x").statusCode());     // 없는 원본
                eq(400, http("GET", base + "/x", null).statusCode());                   // 기존 동작 유지
            });
        });

        // ---------- 콘솔 흐름 (E2E) ----------
        test("답장[E2E]: 보내기(답장은 로그인한 내 계정으로) → 읽고 '답장하시겠어요?' 수락 → 답장 DM(답장한 사람 표시) → 답장 확인", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동");
            discord.users.put(ID_KIM, "김철수");
            withServer(discord, (server, api, dir) -> {
                String sent = runApp(api, "3\n안녕하세요\n/end\n\ny\n" + ID_HONG + "\ny\n5\n");
                lacks(sent, "답장을 받으시겠어요?");                                       // 더 이상 묻지 않는다
                has(sent, "편지를 발송했습니다!\n홍길동 님에게 디스코드로 고유 코드를 전달했어요.\n답장을 받을 사용자: 철수\n고유 코드: ");
                String original = codeIn(sent);

                String replied = runApp(api, "2\n" + original + "\ny\n고마워요\n둘째 줄\n/end\n5\n");
                has(replied, "안녕하세요");
                has(replied, "답장하시겠어요?");
                has(replied, "답장을 보냈습니다!");
                lacks(replied, "작성자 이름");                                             // 답장은 작성자를 묻지 않는다
                eq(2, discord.dms.size());
                eq(ID_CHUL, discord.dms.get(1)[0]);                                        // 답장은 로그인한 보낸 사람(철수)에게
                has(discord.dms.get(1)[1], "💌`철수`님이 답장했어요!");
                lacks(discord.dms.get(1)[1], "고마워요");
                String replyCode = Pattern.compile("고유 코드: `([A-Z2-9]{8})`").matcher(discord.dms.get(1)[1]).results()
                        .findFirst().orElseThrow().group(1);
                if (replyCode.equals(original)) throw new AssertionError("새 코드여야 함");

                String shown = runApp(api, "2\n" + replyCode + "\n5\n");
                has(shown, "from: 철수님이 " + original + "편지에 답장했어요!");
                has(shown, "작성 시각: 20");
                has(shown, "고마워요\n둘째 줄");
                lacks(shown, "답장하시겠어요?");                                          // 답장에는 다시 답장하지 않는다
            });
        });
        test("답장[E2E]: '답장하시겠어요?'를 거절하면 바로 메뉴로 돌아가고 아무것도 보내지 않음", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동");
            discord.users.put(ID_KIM, "김철수");
            withServer(discord, (server, api, dir) -> {
                String original = codeIn(runApp(api, "3\n안녕\n/end\n\ny\n" + ID_HONG + "\ny\n5\n"));
                int letters = countLetters(dir);
                String out = runApp(api, "2\n" + original + "\nn\n5\n");
                has(out, "답장하시겠어요?");
                lacks(out, "답장 내용을 입력하세요");
                has(out, "프로그램을 종료합니다.");
                eq(letters, countLetters(dir));
                eq(1, discord.dms.size());
            });
        });
        test("답장[E2E]: 답장받을 ID는 묻지 않고 로그인한 계정으로 정해져, 보낸 편지는 항상 답장 가능", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동 (@gildong)");
            withServer(discord, (server, api, dir) -> {
                String sent = runApp(api, "3\n안녕\n/end\n\ny\n" + ID_HONG + "\ny\n5\n");
                lacks(sent, "답장을 받으시겠어요?");
                has(sent, "홍길동(@gildong) 님에게 디스코드로 고유 코드를 전달했어요.");
                has(sent, "답장을 받을 사용자: 철수");
                eq(true, api.open(LetterCode.of(codeIn(sent))).replyable());
                has(runApp(api, "2\n" + codeIn(sent) + "\nn\n5\n"), "답장하시겠어요?");
            });
        });
        test("답장[E2E]: 내용이 비면 답장을 취소하고, 답장 DM 실패 시 새 코드를 직접 전달하도록 안내", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동");
            discord.users.put(ID_KIM, "김철수");
            withServer(discord, (server, api, dir) -> {
                String original = codeIn(runApp(api, "3\n안녕\n/end\n\ny\n" + ID_HONG + "\ny\n5\n"));
                int letters = countLetters(dir);
                has(runApp(api, "2\n" + original + "\ny\n/end\n5\n"), "답장 내용이 비어 있어 취소했습니다.");
                eq(letters, countLetters(dir));

                discord.dmFailure = DiscordGatewayException.Reason.DM_NOT_ALLOWED;
                String out = runApp(api, "2\n" + original + "\ny\n답장\n/end\n5\n");
                has(out, "디스코드로 전달하지 못했어요");
                has(api.read(LetterCode.of(codeIn(out))).author(), "에 대한 답장입니다.");
            });
        });
    }

    // ---------------------------------------------------------------- 비밀번호 / from·to 표시 테스트

    /** withServer가 로그인시킨 api. 날것의 HTTP 요청이 이 api의 세션 토큰을 실어 보내게 한다. */
    private static volatile TestApi sessionOwner;

    /** 현재 로그인한 사용자(withServer의 철수)의 세션 토큰을 싣고 보내는 날것의 HTTP 요청. */
    private static java.net.http.HttpResponse<String> http(String method, String url, String body) throws Exception {
        TestApi owner = sessionOwner;
        return http(method, url, body, owner == null ? null : owner.token);
    }

    /** 로그인하지 않은 채 보내는 날것의 HTTP 요청. */
    private static java.net.http.HttpResponse<String> anonymousHttp(String method, String url, String body) throws Exception {
        return http(method, url, body, null);
    }

    /** 지정한 세션 토큰(null·빈 값 포함)만 싣고 보내는 날것의 HTTP 요청. 세션 토큰이 빈 값이면 헤더를 아예 보내지 않는다. */
    private static java.net.http.HttpResponse<String> anonymousHttp(String method, String url, String body, String token) throws Exception {
        return http(method, url, body, token == null || token.isEmpty() ? null : token);
    }

    /** 세션 토큰(token)을 싣고 보내는 날것의 HTTP 요청. token이 null이면 싣지 않는다. */
    private static java.net.http.HttpResponse<String> http(String method, String url, String body, String token) throws Exception {
        var builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body == null) builder.method(method, java.net.http.HttpRequest.BodyPublishers.noBody());
        else builder.method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body));
        return java.net.http.HttpClient.newHttpClient().send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private static void passwordTests() {
        // ---------- 도메인 / 형식 ----------
        test("비밀번호[도메인]: 비우면 없음, 공백 정리, 30자까지, '|'·'`'·제어문자는 거절", () -> {
            eq(null, Password.ofOptional(null));
            eq(null, Password.ofOptional("   "));
            eq("비밀 1234", Password.ofOptional("  비밀 1234 ").value());
            eq(30, new Password("가".repeat(30)).value().length());
            for (String bad : new String[]{"가".repeat(31), "a|b", "||", "a`b", "a\u0007b", "a\nb"}) {
                kind(Kind.INVALID, () -> new Password(bad));
            }
            eq("Password[****]", new Password("secret").toString());                     // 로그에 원문이 찍히지 않는다
            lacks(new LetterDraft("b", new Password("secret")).toString(), "secret");
        });
        test("비밀번호[해시]: 원문이 남지 않고, 맞는 값만 통과하며, 같은 비밀번호도 매번 다른 해시", () -> {
            Password pw = new Password("비밀1234");
            String h1 = PasswordHasher.hash(pw);
            String h2 = PasswordHasher.hash(pw);
            lacks(h1, "비밀1234");
            has(h1, "pbkdf2$");
            if (h1.equals(h2)) throw new AssertionError("소금이 매번 달라야 함");
            eq(true, PasswordHasher.matches(pw, h1));
            eq(true, PasswordHasher.matches(pw, h2));
            eq(false, PasswordHasher.matches(new Password("비밀12345"), h1));
            for (String broken : new String[]{"", "x", "pbkdf2$abc$!!$!!", "md5$1$a$b", "pbkdf2$100000$AAAA"}) {
                eq(false, PasswordHasher.matches(pw, broken));
            }
        });
        test("to 표기: 이름(@핸들, ID) 형식, 이름을 모르면 ID만, 핸들만 있으면 핸들을 이름처럼", () -> {
            DiscordId id = DiscordId.of(ID_HONG);
            eq("홍길동(@gildong, " + ID_HONG + ")", new DiscordUser(id, "홍길동 (@gildong)").label());
            eq("gildong(@gildong, " + ID_HONG + ")", new DiscordUser(id, "@gildong").label());
            eq(ID_HONG, new DiscordUser(id, null).label());
            eq("철수(" + ID_HONG + ")", new DiscordUser(id, "철수").label());
            eq("a (@b)(@c, " + ID_HONG + ")", new DiscordUser(id, "a (@b) (@c)").label());   // 이름 안의 괄호는 그대로
        });
        test("비밀번호[형식]: 읽는 사람에게는 해시·답장받을 ID 없이 to만, 저장 형식에는 모두 보존, 옛 데이터도 읽힘", () -> {
            Letter letter = new Letter("철수", "안녕", LocalDateTime.of(2026, 11, 2, 14, 46), DiscordId.of(ID_KIM),
                    new DiscordUser(DiscordId.of(ID_HONG), "홍길동 (@gildong)"), PasswordHasher.hash(new Password("pw1")));
            String transfer = LetterForm.toBody(letter);
            lacks(transfer, "pbkdf2");
            lacks(transfer, ID_KIM);
            Letter received = LetterForm.fromBody(transfer);
            eq(letter.to(), received.to());
            eq(null, received.passwordHash());
            eq(letter, LetterForm.fromBody(LetterForm.toStoredBody(letter)));
            Letter old = LetterForm.fromBody("author=a&content=hi&createdAt=2026-11-02+14%3A46");
            eq(null, old.to());
            eq(false, old.passwordProtected());
        });
        test("비밀번호[DM 문구]: 비밀번호가 있으면 || `비밀번호` || 로 가려서 알리고, 없으면 줄 자체가 없음", () -> {
            LetterCode code = LetterCode.of("AB23CD45");
            has(DiscordMessages.letterArrived("철수", code, new Password("a_b*c")), "비밀번호: || `a_b*c` ||");
            lacks(DiscordMessages.letterArrived("철수", code, null), "비밀번호");
            lacks(DiscordMessages.letterArrived("철수", code), "||");
        });

        // ---------- 서비스 ----------
        test("비밀번호[서비스]: 해시로만 저장하고, 없음/틀림/맞음을 구분하며, 비밀번호 없는 편지는 그대로 열림", () -> {
            InMemoryLetterRepository repo = new InMemoryLetterRepository();
            LetterService letters = new LetterService(repo, new SequenceCodeGenerator("AAAAAAAA", "BBBBBBBB"), FIXED_CLOCK);
            LetterCode locked = letters.send(SENDER, new LetterDraft("비밀 편지", new Password("열쇠1")));
            LetterCode open = letters.send(SENDER, new LetterDraft("공개 편지"));

            String stored = repo.find(locked).orElseThrow().passwordHash();
            lacks(stored, "열쇠1");
            kind(Kind.PASSWORD_REQUIRED, () -> letters.open(locked, null));
            kind(Kind.PASSWORD_REQUIRED, () -> letters.open(locked, "  "));
            kind(Kind.PASSWORD_WRONG, () -> letters.open(locked, "열쇠2"));
            kind(Kind.PASSWORD_WRONG, () -> letters.open(locked, "a|b"));                  // 형식이 틀린 입력도 그냥 '틀림'
            eq("비밀 편지", letters.open(locked, "열쇠1").content());
            eq("비밀 편지", letters.open(locked, " 열쇠1 ").content());                     // 앞뒤 공백은 무시
            eq("공개 편지", letters.open(open, null).content());
            eq("공개 편지", letters.open(open, "아무거나").content());
            kind(Kind.NOT_FOUND, () -> letters.open(LetterCode.of("QQQQQQQQ"), "x"));
        });
        test("비밀번호[서비스]: 한 편지에 비밀번호를 너무 여러 번 대 보면 RATE_LIMITED (맞는 비밀번호도 잠시 거절)", () -> {
            MutableClock clock = new MutableClock();
            LetterService letters = new LetterService(new InMemoryLetterRepository(), new SequenceCodeGenerator("AAAAAAAA", "BBBBBBBB"),
                    FIXED_CLOCK, new RateLimiter(3, Duration.ofMinutes(10), clock));
            LetterCode locked = letters.send(SENDER, new LetterDraft("내용", new Password("열쇠1")));
            LetterCode other = letters.send(SENDER, new LetterDraft("내용", new Password("열쇠1")));
            for (int i = 0; i < 3; i++) kind(Kind.PASSWORD_WRONG, () -> letters.open(locked, "틀림"));
            kind(Kind.RATE_LIMITED, () -> letters.open(locked, "열쇠1"));
            eq("내용", letters.open(other, "열쇠1").content());                             // 다른 편지는 영향 없음
            clock.advanceSeconds(11 * 60);
            eq("내용", letters.open(locked, "열쇠1").content());                            // 시간이 지나면 풀림
        });
        test("비밀번호[서비스]: 디스코드 전송 — 받는 사람은 서버가 조회해서 저장, DM에는 가려진 비밀번호만, 편지 내용은 없음", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동 (@gildong)");
            InMemoryLetterRepository repo = new InMemoryLetterRepository();
            LetterDeliveryService delivery = replyDelivery(discord, repo, FIXED_CLOCK, "AAAAAAAA");

            Receipt receipt = delivery.sendTo(SENDER, new LetterDraft("비밀 내용", new Password("열쇠1")), DiscordId.of(ID_HONG));
            eq(Delivery.SENT, receipt.delivery());
            Letter stored = repo.find(receipt.code()).orElseThrow();
            eq("홍길동 (@gildong)", stored.to().name());
            eq(ID_HONG, stored.to().id().value());
            has(discord.dms.get(0)[1], "비밀번호: || `열쇠1` ||");
            lacks(discord.dms.get(0)[1], "비밀 내용");
        });
        test("비밀번호[서비스]: 받는 사람 조회에 실패해도 편지는 저장되고 to는 ID만 남음", () -> {
            FakeDiscord discord = new FakeDiscord();                                         // 등록된 사용자 없음
            InMemoryLetterRepository repo = new InMemoryLetterRepository();
            LetterDeliveryService delivery = replyDelivery(discord, repo, FIXED_CLOCK, "AAAAAAAA");
            Receipt receipt = delivery.sendTo(SENDER, new LetterDraft("안녕"), DiscordId.of(ID_HONG));
            eq(ID_HONG, repo.find(receipt.code()).orElseThrow().to().label());
        });
        test("비밀번호[서비스]: 비밀번호가 걸린 편지는 비밀번호를 알아야 답장할 수 있고, 답장 편지에는 비밀번호·to가 없음", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동");
            InMemoryLetterRepository repo = new InMemoryLetterRepository();
            LetterDeliveryService delivery = replyDelivery(discord, repo, FIXED_CLOCK, "AAAAAAAA", "BBBBBBBB");
            delivery.sendTo(SENDER, new LetterDraft("안녕", new Password("열쇠1")), DiscordId.of(ID_HONG), DiscordId.of(ID_KIM));
            int dms = discord.dms.size();

            kind(Kind.PASSWORD_REQUIRED, () -> delivery.reply(SENDER, LetterCode.of("AAAAAAAA"), "답장"));
            kind(Kind.PASSWORD_WRONG, () -> delivery.reply(SENDER, LetterCode.of("AAAAAAAA"), "답장", "틀림"));
            eq(dms, discord.dms.size());
            Receipt receipt = delivery.reply(SENDER, LetterCode.of("AAAAAAAA"), "답장", "열쇠1");
            Letter reply = repo.find(receipt.code()).orElseThrow();
            eq(false, reply.passwordProtected());
            eq(null, reply.to());
        });

        // ---------- 실제 서버 ----------
        test("비밀번호[서버]: 조회 401 → 틀리면 403 → 맞으면 200, 응답에 해시·답장받을 ID 없음, 파일에도 원문 없음", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동 (@gildong)");
            withServer(discord, (server, api, dir) -> {
                String base = "http://127.0.0.1:" + server.port() + "/letters/";
                Receipt receipt = api.sendToDiscord(new LetterDraft("비밀 내용", new Password("열쇠1")),
                        DiscordId.of(ID_HONG), DiscordId.of(ID_KIM));
                String code = receipt.code().value();

                var needed = http("GET", base + code, null);
                eq(401, needed.statusCode());
                lacks(needed.body(), "비밀%20내용");
                lacks(java.net.URLDecoder.decode(needed.body(), StandardCharsets.UTF_8), "비밀 내용");
                eq(403, http("POST", base + code + "/open", "password=" + java.net.URLEncoder.encode("틀림", StandardCharsets.UTF_8)).statusCode());
                eq(401, http("POST", base + code + "/open", "x=y").statusCode());                       // 비밀번호 항목이 없으면 '필요함'
                var ok = http("POST", base + code + "/open", "password=" + java.net.URLEncoder.encode("열쇠1", StandardCharsets.UTF_8));
                eq(200, ok.statusCode());
                lacks(ok.body(), "pbkdf2");
                lacks(ok.body(), ID_KIM);
                has(ok.body(), "toId=" + ID_HONG);
                eq("비밀 내용", LetterForm.fromBody(ok.body()).content());

                String file = Files.readString(dir.resolve(code + ".txt"));
                lacks(java.net.URLDecoder.decode(file, StandardCharsets.UTF_8), "열쇠1");
                has(file, "pw=pbkdf2");
                eq(405, http("GET", base + code + "/open", null).statusCode());
            });
        });
        test("비밀번호[서버]: 규칙에 맞지 않는 비밀번호로 보내면 400이고 저장하지 않음", () -> {
            withServer(new FakeDiscord(), (server, api, dir) -> {
                for (String pw : new String[]{"a|b", "a`b", "가".repeat(31)}) {
                    var res = http("POST", "http://127.0.0.1:" + server.port() + "/letters",
                            "author=a&content=hi&password=" + java.net.URLEncoder.encode(pw, StandardCharsets.UTF_8));
                    eq(400, res.statusCode());
                }
                eq(0, countLetters(dir));
            });
        });
        test("비밀번호[서버]: 비밀번호 있는 일반 편지(디스코드 아님)도 같은 규칙, to는 없음", () -> {
            withServer(new FakeDiscord(), (server, api, dir) -> {
                LetterCode code = api.send(new LetterDraft("안녕", new Password("열쇠1")));
                try {
                    api.open(code);
                    throw new AssertionError("비밀번호 없이 열림");
                } catch (PasswordException e) {
                    eq(false, e.isWrong());
                }
                try {
                    api.open(code, "틀림");
                    throw new AssertionError("틀린 비밀번호로 열림");
                } catch (PasswordException e) {
                    eq(true, e.isWrong());
                }
                OpenedLetter opened = api.open(code, "열쇠1");
                eq("안녕", opened.letter().content());
                eq(null, opened.letter().to());
            });
        });

        // ---------- 콘솔 흐름 (E2E) ----------
        test("비밀번호[E2E]: 메뉴 1 — 작성 후 비밀번호 입력, 읽을 때 틀리면 다시, 맞으면 열림, from만 보이고 to는 없음", () -> {
            withServer(new FakeDiscord(), (server, api, dir) -> {
                String sent = runApp(api, "1\n안녕하세요\n/end\n열쇠1\ny\n5\n");
                has(sent, "비밀번호> ");
                has(sent, "비밀번호가 걸린 편지예요.");
                String code = codeIn(sent);

                String shown = runApp(api, "2\n" + code + "\n틀림\n열쇠1\n5\n");
                has(shown, "비밀번호가 걸린 편지예요.");
                has(shown, "비밀번호가 올바르지 않습니다.");
                has(shown, "from: 철수");
                has(shown, "안녕하세요");
                lacks(shown, "to: ");
                has(shown, "작성 시각: 20");

                String cancelled = runApp(api, "2\n" + code + "\n\n5\n");                  // 비워 두면 취소
                has(cancelled, "취소했습니다.");
                lacks(cancelled, "안녕하세요");
                String tooMany = runApp(api, "2\n" + code + "\na\nb\nc\n열쇠1\n5\n");        // 3번 틀리면 중단(맞는 값도 읽히지 않음)
                has(tooMany, "비밀번호를 여러 번 틀려 취소했습니다.");
                lacks(tooMany, "안녕하세요");
            });
        });
        test("비밀번호[E2E]: 규칙에 안 맞는 비밀번호는 안내하고 다시 입력받음, 비우면 비밀번호 없는 편지", () -> {
            withServer(new FakeDiscord(), (server, api, dir) -> {
                String out = runApp(api, "1\n안녕\n/end\na|b\n열쇠1\ny\n5\n");
                has(out, "비밀번호에는 '|' 와 '`' 를 쓸 수 없습니다.");
                eq(true, api.open(LetterCode.of(codeIn(out)), "열쇠1").letter().content().equals("안녕"));

                String plain = runApp(api, "1\n공개\n/end\n\ny\n5\n");
                lacks(plain, "비밀번호가 걸린 편지예요.");
                String shown = runApp(api, "2\n" + codeIn(plain) + "\n5\n");
                lacks(shown, "비밀번호");
                has(shown, "공개");
            });
        });
        test("비밀번호[E2E]: 메뉴 3 — DM에 가려진 비밀번호, 읽으면 from/to(이름·핸들·ID) 표시, 비밀번호 편지에 답장 가능", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동 (@gildong)");
            discord.users.put(ID_KIM, "김철수");
            withServer(discord, (server, api, dir) -> {
                String sent = runApp(api, "3\n안녕하세요\n/end\n열쇠1\ny\n" + ID_HONG + "\ny\n5\n");
                lacks(sent, "비밀번호는 가려진 상태");                                       // 안내 줄은 없앴지만 DM에는 가려서 들어간다
                String code = codeIn(sent);
                has(discord.dms.get(0)[1], "비밀번호: || `열쇠1` ||");
                lacks(discord.dms.get(0)[1], "안녕하세요");

                String replied = runApp(api, "2\n" + code + "\n열쇠1\ny\n고마워요\n/end\n5\n");
                has(replied, "from: 철수");
                has(replied, "to: 홍길동(@gildong, " + ID_HONG + ")");
                has(replied, "답장하시겠어요?");
                has(replied, "답장을 보냈습니다!");                                         // 열 때 입력한 비밀번호로 답장도 통과

                String replyCode = Pattern.compile("고유 코드: `([A-Z2-9]{8})`").matcher(discord.dms.get(1)[1]).results()
                        .findFirst().orElseThrow().group(1);
                String reply = runApp(api, "2\n" + replyCode + "\n5\n");                     // 답장 편지에는 비밀번호 없음
                has(reply, "from: 철수님이 " + code + "편지에 답장했어요!");
                lacks(reply, "to: ");
                has(reply, "고마워요");
            });
        });
        test("비밀번호[E2E]: 메뉴 3 DM 전달 실패 시 코드와 비밀번호를 직접 전달하도록 안내", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동");
            discord.dmFailure = DiscordGatewayException.Reason.DM_NOT_ALLOWED;
            withServer(discord, (server, api, dir) -> {
                String out = runApp(api, "3\n안녕\n/end\n열쇠1\ny\n" + ID_HONG + "\ny\n\n5\n");
                has(out, "고유 코드와 설정한 비밀번호를 상대에게 직접 전달해 주세요.");
            });
        });
        test("비밀번호[호환]: 비밀번호 없는 옛 편지는 옛 방식(GET)으로 그대로 열리고 to 줄이 없음", () -> {
            withServer(new FakeDiscord(), (server, api, dir) -> {
                Files.writeString(dir.resolve("OLDOLDOL.txt"), "author=a&content=hi&createdAt=2026-11-02+14%3A46");
                var res = http("GET", "http://127.0.0.1:" + server.port() + "/letters/OLDOLDOL", null);
                eq(200, res.statusCode());
                eq("hi", LetterForm.fromBody(res.body()).content());
                lacks(runApp(api, "2\nOLDOLDOL\n5\n"), "to: ");
            });
        });
    }

    // ======================================================================== 로그인 / 받은 편지 목록

    private static final DiscordUser HONG = new DiscordUser(DiscordId.of(ID_HONG), "홍길동 (@gildong)");
    private static final DiscordUser KIM = new DiscordUser(DiscordId.of(ID_KIM), "김철수");

    /** 테스트 서버가 만드는 로그인 인증 코드는 항상 이 값이다. (프로그램에 입력하는 줄을 미리 써 둘 수 있게) */
    private static final String LOGIN_CODE = "123456";

    /** 인증 코드 자리(6자리 숫자)에 항상 {@link #LOGIN_CODE}를 내주는 난수. */
    private static class FixedCodeRandom extends java.util.Random {
        private static final java.util.concurrent.atomic.AtomicInteger SEEDS = new java.util.concurrent.atomic.AtomicInteger();

        FixedCodeRandom() {
            super(SEEDS.incrementAndGet());   // 서버마다 다른 토큰이 나오게 한다. (서버를 다시 켜도 옛 토큰이 되살아나지 않는지 시험하려면 필요)
        }

        @Override
        public int nextInt(int bound) {
            return bound == 1_000_000 ? Integer.parseInt(LOGIN_CODE) : super.nextInt(bound);
        }
    }

    private static LoginService loginService(FakeDiscord discord, MutableClock clock) {
        return new LoginService(discord, clock, new java.util.Random(7), new RateLimiter(3, Duration.ofMinutes(10), clock));
    }

    /** 가장 최근에 그 사용자에게 간 로그인 인증 코드 DM에서 6자리 코드를 꺼낸다. */
    private static String loginCodeSentTo(FakeDiscord discord, String id) {
        String code = null;
        for (String[] dm : discord.loginDms) {
            if (!dm[0].equals(id)) continue;
            Matcher m = Pattern.compile("인증 코드: `([0-9]{6})`").matcher(dm[1]);
            if (!m.find()) throw new AssertionError("DM에 인증 코드가 없음:\n" + dm[1]);
            code = m.group(1);
        }
        if (code == null) throw new AssertionError("그 사용자에게 간 인증 코드 DM이 없음: " + id);
        return code;
    }

    /** 서비스에서 로그인을 끝까지 진행한다. (DM으로 간 코드를 그대로 입력) */
    private static LoginResult fullLogin(LoginService login, FakeDiscord discord, DiscordUser who) {
        LoginTicket ticket = login.start(who.id());
        return login.verify(ticket.loginId(), loginCodeSentTo(discord, who.id().value()));
    }

    /** 로그인할 수 있는 사용자(철수, 홍길동, 김철수)가 있는 가짜 디스코드. */
    private static FakeDiscord discordWithUsers() {
        FakeDiscord discord = new FakeDiscord();
        discord.users.put(ID_HONG, "홍길동 (@gildong)");
        discord.users.put(ID_KIM, "김철수");
        return discord;
    }

    /** 앱 테스트용 가짜 API. 어떤 응답을 할지 필드로 정한다. */
    private static class StubApi implements LetterApi {
        int wrongCodes = 0;                   // 이만큼은 "코드가 틀렸어요"라고 답한다
        ApiException verifyFailure = null;    // 한 번 던지고 비운다
        ApiException startFailure = null;     // 한 번 던지고 비운다
        boolean failStartAfterFirstLogin = false;
        int loginRequiredCalls = 0;           // 다음 이만큼의 업무 호출은 "로그인 필요"로 거절한다
        List<LetterCode> inbox = List.of();
        int logins = 0;
        int logouts = 0;
        final List<DiscordId> startedFor = new java.util.ArrayList<>();
        final List<String> codesTried = new java.util.ArrayList<>();
        final List<String> sent = new java.util.ArrayList<>();

        private void guard() throws ApiException {
            if (loginRequiredCalls > 0) {
                loginRequiredCalls--;
                throw new LoginRequiredException("로그인이 만료되었습니다.");
            }
        }

        @Override
        public LoginTicket startLogin(DiscordId id) throws ApiException {
            if (startFailure != null) {
                ApiException failure = startFailure;
                startFailure = null;
                throw failure;
            }
            if (failStartAfterFirstLogin && logins >= 1) throw new ApiException("서버에 연결할 수 없습니다.", false);
            logins++;
            startedFor.add(id);
            return new LoginTicket("login" + logins, 300);
        }

        @Override
        public LoginResult verifyLogin(LoginTicket ticket, String code) throws ApiException {
            codesTried.add(code);
            if (wrongCodes > 0) {
                wrongCodes--;
                throw new CodeWrongException("인증 코드가 맞지 않아요. (남은 횟수 " + (wrongCodes + 1) + "번)");
            }
            if (verifyFailure != null) {
                ApiException failure = verifyFailure;
                verifyFailure = null;
                throw failure;
            }
            return new LoginResult("token" + logins, HONG);
        }

        @Override
        public void logout() {
            logouts++;
        }

        @Override
        public List<LetterCode> inbox() throws ApiException {
            guard();
            return inbox;
        }

        @Override
        public LetterCode send(LetterDraft draft) throws ApiException {
            guard();
            sent.add(draft.content());
            return LetterCode.of("AAAAAAAA");
        }

        @Override
        public OpenedLetter open(LetterCode code, String password) throws ApiException {
            guard();
            throw new ApiException("없는 편지입니다.", false);
        }

        @Override
        public DiscordUser findDiscordUser(DiscordId id) throws ApiException {
            guard();
            return new DiscordUser(id, "홍길동");
        }

        @Override
        public Receipt sendToDiscord(LetterDraft draft, DiscordId to, DiscordId replyTo) throws ApiException {
            guard();
            sent.add(draft.content());
            return new Receipt(LetterCode.of("AAAAAAAA"), Delivery.SENT);
        }

        @Override
        public Receipt reply(LetterCode original, String content, String password) throws ApiException {
            guard();
            return new Receipt(LetterCode.of("BBBBBBBB"), Delivery.SENT);
        }
    }

    /** 앱 테스트에서 로그인할 때 입력하는 두 줄: 내 디스코드 ID, DM으로 받은 인증 코드. */
    private static final String STUB_LOGIN = ID_HONG + "\n" + LOGIN_CODE + "\n";

    /** 가짜 API로 콘솔 앱을 돌린다. 로그인 입력(STUB_LOGIN)을 자동으로 앞에 붙인다. */
    private static String runStub(StubApi api, String script) {
        return runStubRaw(api, STUB_LOGIN + script);
    }

    /** 가짜 API로 콘솔 앱을 돌린다. 입력을 그대로 흘려 넣는다. (로그인 과정 자체를 시험할 때) */
    private static String runStubRaw(StubApi api, String script) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConsoleView view = new ConsoleView(
                new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8,
                new PrintStream(out, true, StandardCharsets.UTF_8));
        new LetterApp(api, view).run();
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void loginTests() {
        // ---------- 도메인 / 통신 형식 ----------
        test("Letter.addressee: 전달 대상이 먼저, 없으면 받는 사람(to)의 ID, 둘 다 없으면 null", () -> {
            LocalDateTime at = LocalDateTime.of(2026, 11, 2, 14, 46);
            eq(DiscordId.of(ID_KIM), new Letter("a", "x", at, null, HONG, null, SENDER, DiscordId.of(ID_KIM)).addressee());
            eq(DiscordId.of(ID_HONG), new Letter("a", "x", at, null, HONG, null).addressee());     // 옛 편지
            eq(null, new Letter("a", "x", at).addressee());
        });
        test("Letter.fromLabel: 답장 편지는 '이름(@핸들)님이 코드편지에 답장했어요!', 답장한 사람을 모르는 옛 답장은 작성자 그대로", () -> {
            LocalDateTime at = LocalDateTime.of(2026, 11, 2, 14, 46);
            String author = Letter.replyAuthor(LetterCode.of("AAAAAAAA"));
            eq("AAAAAAAA에 대한 답장입니다.", author);
            eq("홍길동(@gildong)님이 AAAAAAAA편지에 답장했어요!", new Letter(author, "x", at, null, null, null, HONG, null).fromLabel());
            eq(author, new Letter(author, "x", at).fromLabel());
        });
        test("Letter.fromLabel: 확인된 보낸 사람은 이름(@핸들, ID), 없으면 작성자 이름", () -> {
            LocalDateTime at = LocalDateTime.of(2026, 11, 2, 14, 46);
            eq("홍길동(@gildong, " + ID_HONG + ")", new Letter("홍길동", "x", at, null, null, null, HONG, null).fromLabel());
            eq("옛 작성자", new Letter("옛 작성자", "x", at).fromLabel());
        });
        test("LetterForm: from은 전송 형식에 있고, 전달 대상·답장받을 ID는 저장 형식에만 있음", () -> {
            Letter letter = new Letter("홍길동", "안녕", LocalDateTime.of(2026, 11, 2, 14, 46),
                    DiscordId.of(ID_CHUL), KIM, null, HONG, DiscordId.of(ID_KIM));
            String transfer = LetterForm.toBody(letter);
            has(transfer, "fromId=" + ID_HONG);
            lacks(transfer, "deliveredTo");
            lacks(transfer, ID_CHUL);                                         // 답장받을 ID
            String stored = LetterForm.toStoredBody(letter);
            has(stored, "deliveredTo=" + ID_KIM);
            has(stored, "replyTo=" + ID_CHUL);
            eq(HONG, LetterForm.fromBody(transfer).from());
        });
        test("LetterForm: 저장했다가 읽으면 from·deliveredTo까지 같은 편지", () -> {
            Letter letter = new Letter("홍길동", "안녕&1", LocalDateTime.of(2026, 1, 2, 3, 4),
                    DiscordId.of(ID_CHUL), KIM, null, HONG, DiscordId.of(ID_KIM));
            eq(letter, LetterForm.fromBody(LetterForm.toStoredBody(letter)));
        });
        test("AuthForm: 로그인 안내·결과 왕복 변환, 잘못된 형식은 거절", () -> {
            LoginTicket ticket = new LoginTicket("lid", 300);
            eq(ticket, AuthForm.ticketFromBody(AuthForm.ticketToBody(ticket)));
            LoginResult done = new LoginResult("tok", HONG);
            eq(done.user(), AuthForm.resultFromBody(AuthForm.resultToBody(done)).user());
            eq("tok", AuthForm.resultFromBody(AuthForm.resultToBody(done)).token());
            for (String bad : new String[]{"", "token=t", "token=t&id=abc", "id=" + ID_HONG}) {
                try {
                    AuthForm.resultFromBody(bad);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                throw new AssertionError("예외가 발생하지 않음: " + bad);
            }
            for (String bad : new String[]{"", "loginId=a", "expiresIn=300", "loginId=a&expiresIn=x", "loginId=a&expiresIn=0"}) {
                try {
                    AuthForm.ticketFromBody(bad);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                throw new AssertionError("예외가 발생하지 않음: " + bad);
            }
        });
        test("LoginResult: 토큰은 toString에 나오지 않음", () -> lacks(new LoginResult("SECRET-TOKEN", HONG).toString(), "SECRET-TOKEN"));
        test("InboxForm: 순서를 지킨 왕복 변환, 빈 목록, 잘못된 코드는 거절", () -> {
            List<LetterCode> codes = List.of(LetterCode.of("CCCCCCCC"), LetterCode.of("AAAAAAAA"), LetterCode.of("BBBBBBBB"));
            eq(codes, InboxForm.fromBody(InboxForm.toBody(codes)));
            eq(List.of(), InboxForm.fromBody(InboxForm.toBody(List.of())));
            for (String bad : new String[]{"", "codes=AAAAAAAA,zz", "codes=AAAAAAAA,,BBBBBBBB"}) {
                try {
                    InboxForm.fromBody(bad);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                throw new AssertionError("예외가 발생하지 않음: " + bad);
            }
        });

        // ---------- 설정 ----------
        test("ServerConfig: 로그인은 봇 토큰으로 켜지고, 예전 OAuth 설정값(CLIENT_ID 등)이 남아 있어도 무시됨", () -> {
            eq(false, ServerConfig.from(Map.of(), new String[0]).discordEnabled());
            eq(true, ServerConfig.from(Map.of("DISCORD_BOT_TOKEN", "BOT"), new String[0]).discordEnabled());
            ServerConfig old = ServerConfig.from(Map.of("DISCORD_BOT_TOKEN", "BOT", "DISCORD_CLIENT_ID", "abc",
                    "DISCORD_CLIENT_SECRET", "s", "PUBLIC_URL", "not a url"), new String[0]);
            eq(true, old.discordEnabled());
        });
        test("ServerConfig: toString에 봇 토큰이 나오지 않음", () -> {
            ServerConfig config = ServerConfig.from(Map.of("DISCORD_BOT_TOKEN", "BOT-TOKEN-VALUE"), new String[0]);
            lacks(config.toString(), "BOT-TOKEN-VALUE");
        });

        // ---------- 로그인 서비스 (디스코드 DM 인증 코드) ----------
        test("DiscordMessages.loginCode: 코드·유효 시간·'본인이 아니면 무시' 안내만 있고 링크는 없음", () -> {
            String text = DiscordMessages.loginCode("482913", 5);
            has(text, "🔐 편지 프로그램 로그인 인증 코드: `482913`");
            has(text, "(5분 동안만 유효해요)");
            has(text, "본인이 요청한 게 아니라면 코드를 알려 주지 말고 이 메시지를 무시해 주세요.");
            lacks(text, "http");
        });
        test("LoginService: 시작 → DM으로 6자리 코드 → 코드 입력 → 세션 토큰 (코드는 한 번만, 토큰으로 사용자 확인)", () -> {
            FakeDiscord discord = discordWithUsers();
            LoginService login = loginService(discord, new MutableClock());
            LoginTicket ticket = login.start(DiscordId.of(ID_HONG));
            eq(300, ticket.expiresInSeconds());
            eq(1, discord.loginDms.size());
            eq(ID_HONG, discord.loginDms.get(0)[0]);
            String code = loginCodeSentTo(discord, ID_HONG);
            eq(6, code.length());
            lacks(discord.loginDms.get(0)[1], ticket.loginId());                      // DM에는 loginId가 없다

            LoginResult result = login.verify(ticket.loginId(), code);
            eq(HONG, result.user());
            eq(HONG, login.authenticate(result.token()));
            kind(Kind.NOT_FOUND, () -> login.verify(ticket.loginId(), code));          // 코드는 한 번만
            eq(1, login.sessionCount());
        });
        test("LoginService: 로그인하지 않았거나 모르는 토큰은 UNAUTHENTICATED", () -> {
            LoginService login = loginService(discordWithUsers(), new MutableClock());
            kind(Kind.UNAUTHENTICATED, () -> login.authenticate(null));
            kind(Kind.UNAUTHENTICATED, () -> login.authenticate(""));
            kind(Kind.UNAUTHENTICATED, () -> login.authenticate("nope"));
        });
        test("LoginService: 사용자마다 다른 토큰·다른 계정", () -> {
            FakeDiscord discord = discordWithUsers();
            LoginService login = loginService(discord, new MutableClock());
            LoginResult a = fullLogin(login, discord, HONG);
            LoginResult b = fullLogin(login, discord, KIM);
            eq(true, !a.token().equals(b.token()));
            eq(HONG, login.authenticate(a.token()));
            eq(KIM, login.authenticate(b.token()));
        });
        test("LoginService: 모르는 loginId나 null은 NOT_FOUND (코드만 알아서는 로그인할 수 없음)", () -> {
            FakeDiscord discord = discordWithUsers();
            LoginService login = loginService(discord, new MutableClock());
            login.start(DiscordId.of(ID_HONG));
            String code = loginCodeSentTo(discord, ID_HONG);
            kind(Kind.NOT_FOUND, () -> login.verify("모르는-id", code));
            kind(Kind.NOT_FOUND, () -> login.verify(null, code));
            eq(0, login.sessionCount());
        });
        test("LoginService: 코드는 공백·하이픈을 무시하고, 틀리면 남은 횟수와 함께 CODE_WRONG", () -> {
            FakeDiscord discord = discordWithUsers();
            LoginService login = loginService(discord, new MutableClock());
            LoginTicket ticket = login.start(DiscordId.of(ID_HONG));
            String code = loginCodeSentTo(discord, ID_HONG);
            String wrong = code.equals("000000") ? "111111" : "000000";
            LetterException first = expect(() -> login.verify(ticket.loginId(), wrong));
            eq(Kind.CODE_WRONG, first.kind());
            has(first.getMessage(), "남은 횟수 4번");
            kind(Kind.CODE_WRONG, () -> login.verify(ticket.loginId(), null));
            kind(Kind.CODE_WRONG, () -> login.verify(ticket.loginId(), ""));
            LoginResult ok = login.verify(ticket.loginId(), " " + code.substring(0, 3) + "-" + code.substring(3) + " ");
            eq(HONG, login.authenticate(ok.token()));
        });
        test("LoginService: 코드를 5번 틀리면 그 로그인은 취소 (맞는 코드도 소용없음)", () -> {
            FakeDiscord discord = discordWithUsers();
            LoginService login = loginService(discord, new MutableClock());
            LoginTicket ticket = login.start(DiscordId.of(ID_HONG));
            String code = loginCodeSentTo(discord, ID_HONG);
            String wrong = code.equals("000000") ? "111111" : "000000";
            for (int i = 0; i < 4; i++) kind(Kind.CODE_WRONG, () -> login.verify(ticket.loginId(), wrong));
            kind(Kind.INVALID, () -> login.verify(ticket.loginId(), wrong));            // 5번째: 취소
            kind(Kind.NOT_FOUND, () -> login.verify(ticket.loginId(), code));
            eq(0, login.sessionCount());
        });
        test("LoginService: 5분이 지나면 코드가 만료 (4분 59초까지는 유효)", () -> {
            MutableClock clock = new MutableClock();
            FakeDiscord discord = discordWithUsers();
            LoginService login = loginService(discord, clock);
            LoginTicket early = login.start(DiscordId.of(ID_HONG));
            clock.advanceSeconds(299);
            eq(HONG, login.verify(early.loginId(), loginCodeSentTo(discord, ID_HONG)).user());

            LoginTicket late = login.start(DiscordId.of(ID_KIM));
            clock.advanceSeconds(301);
            kind(Kind.NOT_FOUND, () -> login.verify(late.loginId(), loginCodeSentTo(discord, ID_KIM)));
        });
        test("LoginService: 같은 사람이 다시 시작하면 이전 로그인 요청은 무효 (옛 코드·옛 loginId 모두)", () -> {
            FakeDiscord discord = discordWithUsers();
            LoginService login = loginService(discord, new MutableClock());
            LoginTicket first = login.start(DiscordId.of(ID_HONG));
            String firstCode = loginCodeSentTo(discord, ID_HONG);
            LoginTicket second = login.start(DiscordId.of(ID_HONG));
            kind(Kind.NOT_FOUND, () -> login.verify(first.loginId(), firstCode));
            String secondCode = loginCodeSentTo(discord, ID_HONG);
            eq(HONG, login.verify(second.loginId(), secondCode).user());
        });
        test("LoginService: 세션은 12시간 뒤 만료되고, 로그아웃하면 바로 끝남", () -> {
            MutableClock clock = new MutableClock();
            FakeDiscord discord = discordWithUsers();
            LoginService login = loginService(discord, clock);
            String token = fullLogin(login, discord, HONG).token();
            clock.advanceSeconds(11 * 3600);
            eq(HONG, login.authenticate(token));
            clock.advanceSeconds(2 * 3600);
            kind(Kind.UNAUTHENTICATED, () -> login.authenticate(token));

            String second = fullLogin(login, discord, HONG).token();
            login.logout(second);
            kind(Kind.UNAUTHENTICATED, () -> login.authenticate(second));
            login.logout("모르는 토큰");                                              // 아무 일도 없음
            login.logout(null);
        });
        test("LoginService: 없는 사용자는 NOT_FOUND(DM 없음), DM을 막았으면 INVALID, 디스코드 장애면 UNAVAILABLE — 모두 로그인 요청이 남지 않음", () -> {
            FakeDiscord discord = discordWithUsers();
            LoginService login = loginService(discord, new MutableClock());
            kind(Kind.NOT_FOUND, () -> login.start(DiscordId.of("99999999999999999")));
            eq(0, discord.loginDms.size());

            discord.loginFailure = Reason.DM_NOT_ALLOWED;
            LetterException dm = expect(() -> login.start(DiscordId.of(ID_HONG)));
            eq(Kind.INVALID, dm.kind());
            has(dm.getMessage(), "DM으로 보내지 못했어요");
            has(dm.getMessage(), "서버에 함께 있는지");

            discord.loginFailure = Reason.UNAVAILABLE;
            kind(Kind.UNAVAILABLE, () -> login.start(DiscordId.of(ID_KIM)));
            eq(0, login.sessionCount());
            eq(0, discord.loginDms.size());

            discord.loginFailure = null;
            login.start(DiscordId.of(ID_HONG));                                       // 실패한 요청이 한도나 상태를 망치지 않음
            eq(1, discord.loginDms.size());
        });
        test("LoginService: 봇이 꺼져 있으면(Disabled) 시작할 때 UNAVAILABLE", () -> {
            MutableClock clock = new MutableClock();
            LoginService login = new LoginService(new DisabledDiscordGateway(), clock, new java.util.Random(1),
                    new RateLimiter(3, Duration.ofMinutes(10), clock));
            LetterException e = expect(() -> login.start(DiscordId.of(ID_HONG)));
            eq(Kind.UNAVAILABLE, e.kind());
            has(e.getMessage(), "디스코드 로그인을 사용할 수 없습니다");
        });
        test("LoginService: 같은 사람에게 인증 코드 DM은 10분에 3번까지 (다른 사람은 영향 없음, 지나면 다시 가능)", () -> {
            MutableClock clock = new MutableClock();
            FakeDiscord discord = discordWithUsers();
            LoginService login = loginService(discord, clock);
            for (int i = 0; i < 3; i++) login.start(DiscordId.of(ID_HONG));
            kind(Kind.RATE_LIMITED, () -> login.start(DiscordId.of(ID_HONG)));
            eq(3, discord.loginDms.size());                                           // 4번째는 DM도 보내지 않음
            login.start(DiscordId.of(ID_KIM));
            clock.advanceSeconds(10 * 60 + 1);
            login.start(DiscordId.of(ID_HONG));
        });
        test("LoginService: 진행 중인 로그인이 너무 많으면 RATE_LIMITED, 만료되면 다시 가능", () -> {
            MutableClock clock = new MutableClock();
            FakeDiscord discord = new FakeDiscord();
            LoginService login = loginService(discord, clock);
            for (int i = 0; i < 2000; i++) {
                String id = String.valueOf(10_000_000_000_000_000L + i);
                discord.users.put(id, "사용자" + i);
                login.start(DiscordId.of(id));
            }
            discord.users.put("20000000000000000", "마지막");
            kind(Kind.RATE_LIMITED, () -> login.start(DiscordId.of("20000000000000000")));
            clock.advanceSeconds(5 * 60 + 1);
            login.start(DiscordId.of("20000000000000000"));
        });
        test("LoginService: 만료된 것을 정리하면 세션 수가 줄어듦", () -> {
            MutableClock clock = new MutableClock();
            FakeDiscord discord = discordWithUsers();
            LoginService login = loginService(discord, clock);
            fullLogin(login, discord, HONG);
            eq(1, login.sessionCount());
            clock.advanceSeconds(13 * 3600);
            login.evictExpired();
            eq(0, login.sessionCount());
        });

        // ---------- 받은 편지 목록 (서비스·저장소) ----------
        test("받은 편지[서비스]: 작성 시각이 늦은 것부터, 같은 분이면 나중에 보낸 것부터", () -> {
            MutableClock clock = new MutableClock();                       // 1970-01-01 00:00 UTC에서 시작
            LetterRepository repo = new InMemoryLetterRepository();
            LetterService letters = new LetterService(repo, new SequenceCodeGenerator("AAAAAAAA", "BBBBBBBB", "CCCCCCCC", "DDDDDDDD"), clock);
            LetterDeliveryService delivery = new LetterDeliveryService(letters, new FakeDiscord(), new RateLimiter(10, Duration.ofMinutes(10), FIXED_CLOCK));
            DiscordId hong = DiscordId.of(ID_HONG);

            delivery.sendTo(SENDER, new LetterDraft("첫째"), hong);          // A  00:00
            clock.advanceSeconds(300);
            delivery.sendTo(SENDER, new LetterDraft("둘째"), hong);          // B  00:05
            delivery.sendTo(SENDER, new LetterDraft("셋째"), hong);          // C  00:05 (B와 같은 분)
            clock.advanceSeconds(3600);
            delivery.sendTo(SENDER, new LetterDraft("넷째"), hong);          // D  01:05

            eq(List.of("DDDDDDDD", "CCCCCCCC", "BBBBBBBB", "AAAAAAAA"),
                    letters.inboxOf(hong).stream().map(LetterCode::value).toList());
        });
        test("받은 편지[서비스]: 내게 온 편지만 (보낸 것·남의 것·디스코드로 안 보낸 것 제외), 없으면 빈 목록", () -> {
            LetterRepository repo = new InMemoryLetterRepository();
            LetterService letters = new LetterService(repo, new SequenceCodeGenerator("AAAAAAAA", "BBBBBBBB", "CCCCCCCC"), FIXED_CLOCK);
            LetterDeliveryService delivery = new LetterDeliveryService(letters, new FakeDiscord(), new RateLimiter(10, Duration.ofMinutes(10), FIXED_CLOCK));
            delivery.sendTo(SENDER, new LetterDraft("홍에게"), DiscordId.of(ID_HONG));      // A
            delivery.sendTo(SENDER, new LetterDraft("김에게"), DiscordId.of(ID_KIM));       // B
            letters.send(SENDER, new LetterDraft("디스코드 없이"));                          // C

            eq(List.of(LetterCode.of("AAAAAAAA")), letters.inboxOf(DiscordId.of(ID_HONG)));
            eq(List.of(LetterCode.of("BBBBBBBB")), letters.inboxOf(DiscordId.of(ID_KIM)));
            eq(List.of(), letters.inboxOf(SENDER.id()));                                    // 보낸 사람의 목록에는 없다
            eq(List.of(), letters.inboxOf(DiscordId.of("999999999999999999")));
        });
        test("받은 편지[서비스]: 디스코드 전달에 실패한 편지도 목록에 있음", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.dmFailure = Reason.DM_NOT_ALLOWED;
            LetterService letters = new LetterService(new InMemoryLetterRepository(), new SequenceCodeGenerator("AAAAAAAA"), FIXED_CLOCK);
            LetterDeliveryService delivery = new LetterDeliveryService(letters, discord, new RateLimiter(10, Duration.ofMinutes(10), FIXED_CLOCK));
            eq(Delivery.FAILED, delivery.sendTo(SENDER, new LetterDraft("x"), DiscordId.of(ID_HONG)).delivery());
            eq(List.of(LetterCode.of("AAAAAAAA")), letters.inboxOf(DiscordId.of(ID_HONG)));
        });
        test("받은 편지[서비스]: 답장 편지는 원본을 보낸 사람(답장받을 ID)의 목록에 올라가고, 답장한 사람의 목록에는 안 올라감", () -> {
            LetterService letters = new LetterService(new InMemoryLetterRepository(), new SequenceCodeGenerator("AAAAAAAA", "BBBBBBBB"), FIXED_CLOCK);
            LetterDeliveryService delivery = new LetterDeliveryService(letters, new FakeDiscord(), new RateLimiter(10, Duration.ofMinutes(10), FIXED_CLOCK));
            delivery.sendTo(SENDER, new LetterDraft("안녕"), DiscordId.of(ID_HONG), SENDER.id());    // A: 홍에게, 답장은 철수가 받음
            delivery.reply(HONG, LetterCode.of("AAAAAAAA"), "답장입니다");                                  // B: 철수에게

            eq(List.of(LetterCode.of("BBBBBBBB")), letters.inboxOf(SENDER.id()));
            eq(List.of(LetterCode.of("AAAAAAAA")), letters.inboxOf(DiscordId.of(ID_HONG)));
            lacks(LetterForm.toBody(letters.read(LetterCode.of("BBBBBBBB"))), ID_CHUL);              // 답장에서 받는 사람이 드러나지 않음
        });
        test("받은 편지[서비스]: 보낸 사람 표시 — 편지에는 로그인 계정이 from으로 남음", () -> {
            LetterService letters = new LetterService(new InMemoryLetterRepository(), new SequenceCodeGenerator("AAAAAAAA"), FIXED_CLOCK);
            Letter letter = letters.read(letters.send(HONG, new LetterDraft("안녕")));
            eq(HONG, letter.from());
            eq("홍길동", letter.author());
        });
        test("받은 편지[파일]: 재시작해도 목록이 유지되고, 옛 편지(to만 있음)·손상된 파일·남의 편지는 알맞게 처리", () -> withTempDir(dir -> {
            LetterRepository repo = new FileLetterRepository(dir);
            Letter older = new Letter("a", "예전", LocalDateTime.of(2026, 1, 1, 9, 0), null, null, null, SENDER, DiscordId.of(ID_HONG));
            Letter newer = new Letter("a", "최근", LocalDateTime.of(2026, 5, 1, 9, 0), null, null, null, SENDER, DiscordId.of(ID_HONG));
            Letter legacy = new Letter("옛", "옛 편지", LocalDateTime.of(2026, 3, 1, 9, 0), null, HONG, null);   // deliveredTo 없이 to만
            Letter other = new Letter("a", "남의 것", LocalDateTime.of(2026, 6, 1, 9, 0), null, null, null, SENDER, DiscordId.of(ID_KIM));
            repo.saveIfAbsent(LetterCode.of("AAAAAAAA"), older);
            repo.saveIfAbsent(LetterCode.of("BBBBBBBB"), newer);
            repo.saveIfAbsent(LetterCode.of("CCCCCCCC"), other);
            Files.writeString(dir.resolve("DDDDDDDD.txt"), LetterForm.toStoredBody(legacy), StandardCharsets.UTF_8);   // 목록 기능 이전에 저장된 파일
            Files.writeString(dir.resolve("EEEEEEEE.txt"), "garbage%ZZ", StandardCharsets.UTF_8);                         // 손상
            Files.writeString(dir.resolve("README.txt"), "편지 아님", StandardCharsets.UTF_8);

            for (LetterRepository r : List.of(repo, new FileLetterRepository(dir))) {                // 방금 쓴 것과 재시작한 것 모두
                if (r != repo) {
                    List<String> order = r.findByAddressee(DiscordId.of(ID_HONG)).stream()
                            .sorted(LetterEntry.NEWEST_FIRST).map(e -> e.code().value()).toList();
                    eq(List.of("BBBBBBBB", "DDDDDDDD", "AAAAAAAA"), order);
                    eq(List.of("CCCCCCCC"), r.findByAddressee(DiscordId.of(ID_KIM)).stream().map(e -> e.code().value()).toList());
                    eq(List.of(), r.findByAddressee(DiscordId.of(ID_CHUL)));
                }
            }
        }));

        // ---------- 콘솔 앱 (가짜 API) ----------
        test("[앱] 시작하면 로그인부터: 내 ID 입력 → DM 안내 → 인증 코드 입력 → 메뉴가 열림", () -> {
            StubApi api = new StubApi();
            String out = runStubRaw(api, STUB_LOGIN + "5\n");
            has(out, "내 디스코드 ID");
            has(out, "편지 봇이 DM으로 인증 코드를 보내 줘요");
            has(out, "디스코드 DM으로 인증 코드를 보냈어요");
            has(out, "5분 안에 입력해 주세요");
            has(out, "홍길동 (@gildong) 님으로 로그인했어요.");
            eq(List.of(DiscordId.of(ID_HONG)), api.startedFor);
            eq(List.of(LOGIN_CODE), api.codesTried);
            lacks(out, "브라우저");
            eq(true, out.indexOf("님으로 로그인했어요.") < out.indexOf("===== 편지 ====="));   // 로그인이 먼저
            eq(1, api.logouts);                                                      // 종료할 때 로그아웃
        });
        test("[앱] 메뉴에 받은 편지 목록이 있고 종료는 5번", () -> {
            String out = runStub(new StubApi(), "5\n");
            has(out, "4. 받은 편지 목록");
            has(out, "5. 종료");
            has(out, "프로그램을 종료합니다.");
        });
        test("[앱] ID 형식이 틀리면 다시 입력받고, 비워 두면 그만둠 (서버에는 묻지 않음)", () -> {
            StubApi api = new StubApi();
            String out = runStubRaw(api, "abc\n홍길동#1234\n" + STUB_LOGIN + "5\n");
            has(out, "올바르지 않은 디스코드 ID입니다.");
            eq(1, api.logins);
            has(out, "님으로 로그인했어요.");

            StubApi quit = new StubApi();
            String out2 = runStubRaw(quit, "\n");
            has(out2, "로그인하지 못해 프로그램을 종료합니다.");
            lacks(out2, "===== 편지 =====");
            eq(0, quit.logins);
            eq(1, quit.logouts);
        });
        test("[앱] 인증 코드를 보내지 못하면 다시 시도할지 묻고, 그만두면 메뉴 없이 종료", () -> {
            StubApi api = new StubApi();
            api.startFailure = new ApiException("서버 점검 중", true);
            String out = runStubRaw(api, ID_HONG + "\nn\n");
            has(out, "인증 코드를 보내지 못했어요: 서버 점검 중");
            has(out, "로그인하지 못해 프로그램을 종료합니다.");
            lacks(out, "===== 편지 =====");
            eq(1, api.logouts);

            StubApi retry = new StubApi();
            retry.startFailure = new ApiException("서버 점검 중", true);
            has(runStubRaw(retry, ID_HONG + "\ny\n" + STUB_LOGIN + "5\n"), "님으로 로그인했어요.");   // 다시 시도하면 로그인됨
        });
        test("[앱] 인증 코드가 틀리면 남은 횟수를 알리고 다시 입력받음", () -> {
            StubApi api = new StubApi();
            api.wrongCodes = 2;
            String out = runStubRaw(api, ID_HONG + "\n111111\n222222\n" + LOGIN_CODE + "\n5\n");
            has(out, "인증 코드가 맞지 않아요. (남은 횟수 2번)");
            has(out, "인증 코드가 맞지 않아요. (남은 횟수 1번)");
            eq(List.of("111111", "222222", LOGIN_CODE), api.codesTried);
            eq(1, api.logins);                                                       // 같은 로그인 안에서 다시 입력
            has(out, "님으로 로그인했어요.");
        });
        test("[앱] 인증 코드 칸을 비워 두면 인증을 취소하고, 다시 로그인할지 물음", () -> {
            StubApi api = new StubApi();
            String out = runStubRaw(api, ID_HONG + "\n\nn\n");
            has(out, "인증을 취소했어요.");
            has(out, "다시 로그인할까요?");
            lacks(out, "===== 편지 =====");
            eq(List.of(), api.codesTried);
        });
        test("[앱] 인증 코드가 만료·취소되면 알리고 다시 로그인할지 물음, 다시 하면 새 코드를 요청", () -> {
            StubApi api = new StubApi();
            api.verifyFailure = new ApiException("로그인 요청이 없거나 만료되었습니다.", false);
            String out = runStubRaw(api, ID_HONG + "\n" + LOGIN_CODE + "\ny\n" + STUB_LOGIN + "5\n");
            has(out, "로그인에 실패했어요: 로그인 요청이 없거나 만료되었습니다.");
            has(out, "다시 로그인할까요?");
            eq(2, api.logins);
            has(out, "님으로 로그인했어요.");
        });
        test("[앱] 통신이 잠깐 끊긴 경우(다시 해 볼 만한 오류)에는 같은 로그인으로 코드를 다시 입력받음", () -> {
            StubApi api = new StubApi();
            api.verifyFailure = new ApiException("서버에 연결할 수 없습니다.", true);
            String out = runStubRaw(api, ID_HONG + "\n" + LOGIN_CODE + "\n" + LOGIN_CODE + "\n5\n");
            has(out, "로그인에 실패했어요: 서버에 연결할 수 없습니다.");
            eq(1, api.logins);
            eq(List.of(LOGIN_CODE, LOGIN_CODE), api.codesTried);
            has(out, "님으로 로그인했어요.");
        });
        test("[앱] 메뉴 4: 받은 편지 코드만 한 줄씩, 서버가 준 순서(최신순) 그대로", () -> {
            StubApi api = new StubApi();
            api.inbox = List.of(LetterCode.of("CCCCCCCC"), LetterCode.of("AAAAAAAA"), LetterCode.of("BBBBBBBB"));
            String out = runStub(api, "4\n5\n");
            has(out, "받은 편지 3통 (최신순)");
            eq(true, out.indexOf("CCCCCCCC") < out.indexOf("AAAAAAAA") && out.indexOf("AAAAAAAA") < out.indexOf("BBBBBBBB"));
            // 구분선 사이에는 코드만 있다
            String block = out.substring(out.indexOf("받은 편지 3통"));
            String[] lines = block.split("\n");
            eq("CCCCCCCC", lines[2].trim());
            eq("AAAAAAAA", lines[3].trim());
            eq("BBBBBBBB", lines[4].trim());
        });
        test("[앱] 받은 편지가 없으면 안내", () -> has(runStub(new StubApi(), "4\n5\n"), "받은 편지가 없습니다."));
        test("[앱] 목록 조회 중 로그인이 만료되면 다시 로그인한 뒤 조회를 이어 감", () -> {
            StubApi api = new StubApi();
            api.inbox = List.of(LetterCode.of("AAAAAAAA"));
            api.loginRequiredCalls = 1;
            String out = runStub(api, "4\n" + LOGIN_CODE + "\n5\n");         // 다시 로그인할 때는 ID를 다시 묻지 않고 코드만
            has(out, "로그인이 만료되었습니다.");
            has(out, "님 계정으로 다시 로그인해요.");
            eq(2, api.logins);
            eq(List.of(DiscordId.of(ID_HONG), DiscordId.of(ID_HONG)), api.startedFor);
            has(out, "받은 편지 1통 (최신순)");
        });
        test("[앱] 편지를 보내는 중 로그인이 만료되면 다시 로그인하고, 쓴 편지를 그대로 보냄 (두 번 보내지 않음)", () -> {
            StubApi api = new StubApi();
            api.loginRequiredCalls = 1;
            String out = runStub(api, "1\n안녕\n/end\n\ny\n" + LOGIN_CODE + "\n5\n");
            eq(List.of("안녕"), api.sent);
            eq(2, api.logins);
            has(out, "편지를 발송했습니다!");
        });
        test("[앱] 만료 뒤 다시 로그인하지 않으면 프로그램을 마침", () -> {
            StubApi api = new StubApi();
            api.loginRequiredCalls = 1;
            api.failStartAfterFirstLogin = true;
            String out = runStub(api, "4\nn\n");
            has(out, "로그인하지 못해 프로그램을 종료합니다.");
            lacks(out, "(최신순)");
            lacks(out, "받은 편지가 없습니다.");
            eq(1, api.logouts);
        });
        test("[앱] 편지를 쓸 때 작성자 이름을 묻지 않고, 로그인한 계정을 보내는 사람으로 안내", () -> {
            StubApi api = new StubApi();
            String out = runStub(api, "1\n안녕\n/end\n\ny\n5\n");
            lacks(out, "작성자 이름");
            has(out, "보내는 사람: 홍길동 (@gildong)");
            eq(List.of("안녕"), api.sent);
        });
        test("[앱] 입력이 끊겨도 로그아웃함", () -> {
            StubApi api = new StubApi();
            has(runStub(api, ""), "입력이 종료되어 프로그램을 마칩니다.");
            eq(1, api.logouts);
        });

        // ---------- 실제 서버 + 실제 클라이언트 ----------
        test("[E2E] 시작할 때 로그인(ID 입력 → DM으로 인증 코드 → 코드 입력)하고, 종료하면 서버의 로그인도 끝남", () -> {
            FakeDiscord discord = new FakeDiscord();
            withLoginServer(discord, (server, dir) -> {
                TestApi api = new TestApi(server.port(), SENDER);
                String out = runAppOnly(api, "5\n");
                has(out, "디스코드 DM으로 인증 코드를 보냈어요");
                has(out, "철수 님으로 로그인했어요.");
                eq(1, discord.loginDms.size());
                eq(ID_CHUL, discord.loginDms.get(0)[0]);
                has(discord.loginDms.get(0)[1], "인증 코드: `" + LOGIN_CODE + "`");
                String token = api.tokens.get(api.tokens.size() - 1);
                var res = anonymousHttp("GET", "http://127.0.0.1:" + server.port() + "/letters/inbox", null, token);
                eq(401, res.statusCode());                                           // 종료하면서 로그아웃했으므로 그 토큰은 더 이상 못 씀
            });
        });
        test("[E2E] 로그인 없이는 편지·목록·디스코드 조회가 모두 401(login=required), 로그인 주소는 열려 있음", () -> withServer(new FakeDiscord(), (server, api, dir) -> {
            String base = "http://127.0.0.1:" + server.port();
            for (String[] call : new String[][]{
                    {"POST", "/letters", "content=hi"}, {"GET", "/letters/inbox", null}, {"GET", "/letters/AAAAAAAA", null},
                    {"POST", "/letters/AAAAAAAA/open", "password=x"}, {"POST", "/letters/AAAAAAAA/replies", "content=x"},
                    {"GET", "/discord/users/" + ID_HONG, null}}) {
                for (String token : new String[]{null, "", "nope"}) {
                    var res = anonymousHttp(call[0], base + call[1], call[2], token);
                    eq(401, res.statusCode());
                    has(res.body(), "login=required");
                    eq("Bearer", res.headers().firstValue("WWW-Authenticate").orElse(""));
                }
            }
            eq(400, anonymousHttp("POST", base + "/auth/login", null).statusCode());      // 로그인 주소는 로그인 없이 열려 있다 (ID가 없으니 400)
            eq(405, anonymousHttp("GET", base + "/auth/login", null).statusCode());
            eq(404, anonymousHttp("GET", base + "/auth/unknown", null).statusCode());

            HttpLetterApi loggedOut = new HttpLetterApi(base);
            try {
                loggedOut.inbox();
                throw new AssertionError("예외가 발생하지 않음");
            } catch (LoginRequiredException expected) {
                eq(false, expected.isRetryable());
            }
        }));
        test("[E2E] 비밀번호가 필요한 401과 로그인이 필요한 401은 클라이언트에서 구분됨", () -> withServer(new FakeDiscord(), (server, api, dir) -> {
            LetterCode locked = api.send(new LetterDraft("비밀", new Password("열쇠1")));
            try {
                api.open(locked, null);
                throw new AssertionError("예외가 발생하지 않음");
            } catch (PasswordException e) {
                eq(false, e.isWrong());
            }
            HttpLetterApi loggedOut = new HttpLetterApi("http://127.0.0.1:" + server.port());
            try {
                loggedOut.open(locked, null);
                throw new AssertionError("예외가 발생하지 않음");
            } catch (LoginRequiredException expected) {
                // 로그인하지 않았으면 비밀번호보다 로그인이 먼저
            }
        }));
        test("[E2E] 작성자 이름을 보내도 무시하고, 보낸 사람은 로그인한 계정으로만 정해짐", () -> withServer(new FakeDiscord(), (server, api, dir) -> {
            String base = "http://127.0.0.1:" + server.port();
            var res = http("POST", base + "/letters", "author=" + java.net.URLEncoder.encode("사칭범", StandardCharsets.UTF_8) + "&content=hi");
            eq(200, res.statusCode());
            String code = FormCodec.decode(res.body()).get("code");
            Letter letter = LetterForm.fromBody(http("GET", base + "/letters/" + code, null).body());
            eq("철수", letter.author());
            eq(SENDER, letter.from());
            lacks(Files.readString(dir.resolve(code + ".txt")), "사칭범");
        }));
        test("[E2E] 편지를 읽으면 from에 로그인 계정이 이름(@핸들, ID)으로 보임", () -> withServer(new FakeDiscord(), (server, api, dir) -> {
            String code = codeIn(runApp(api, "1\n안녕\n/end\n\ny\n5\n"));
            has(runApp(api, "2\n" + code + "\n5\n"), "from: 철수(" + ID_CHUL + ")");
        }));
        test("[E2E] 디스코드로 보내면 DM에 보낸 사람이 확인된 계정으로 적힘", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동 (@gildong)");
            withServer(discord, (server, api, dir) -> {
                api.sendToDiscord(new LetterDraft("안녕"), DiscordId.of(ID_HONG));
                has(discord.dms.get(0)[1], "💌 편지가 도착했어요!\n보낸 사람: `철수`\n고유 코드: `");
                has(discord.dms.get(0)[1], "[편지 프로그램 다운로드 하기](<https://github.com/xhekr2/Letter>)");
                lacks(discord.dms.get(0)[1], "로그인으로 확인된 계정");
                lacks(discord.dms.get(0)[1], ID_CHUL);                                  // DM에는 ID 없이 이름(@핸들)만
            });
        });
        test("[E2E] 받은 편지 목록: 내게 전달된 편지만, 최신순(같은 분이면 나중에 보낸 것 먼저), 전달 실패한 편지도 포함", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동 (@gildong)");
            discord.users.put(ID_KIM, "김철수");
            withLoginServer(discord, (server, dir) -> {
                TestApi chul = new TestApi(server.port(), SENDER);
                chul.login();
                LetterCode first = chul.sendToDiscord(new LetterDraft("첫째"), DiscordId.of(ID_HONG)).code();
                LetterCode second = chul.sendToDiscord(new LetterDraft("둘째"), DiscordId.of(ID_HONG)).code();
                discord.dmFailure = Reason.DM_NOT_ALLOWED;
                Receipt failed = chul.sendToDiscord(new LetterDraft("셋째"), DiscordId.of(ID_HONG));
                eq(Delivery.FAILED, failed.delivery());
                discord.dmFailure = null;
                LetterCode toKim = chul.sendToDiscord(new LetterDraft("김에게"), DiscordId.of(ID_KIM)).code();
                chul.send(new LetterDraft("디스코드 없이"));

                TestApi hong = new TestApi(server.port(), HONG);
                hong.login();
                eq(List.of(failed.code(), second, first), hong.inbox());

                TestApi kim = new TestApi(server.port(), KIM);
                kim.login();
                eq(List.of(toKim), kim.inbox());
                eq(List.of(), chul.inbox());                                          // 보낸 사람 목록에는 없다
            });
        });
        test("[E2E] 받은 편지 목록: 메뉴 4에서 코드만 보임", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동 (@gildong)");
            withLoginServer(discord, (server, dir) -> {
                TestApi chul = new TestApi(server.port(), SENDER);
                chul.login();
                LetterCode a = chul.sendToDiscord(new LetterDraft("비밀 내용 A"), DiscordId.of(ID_HONG)).code();
                LetterCode b = chul.sendToDiscord(new LetterDraft("비밀 내용 B"), DiscordId.of(ID_HONG)).code();

                String out = runApp(new TestApi(server.port(), HONG), "4\n5\n");
                has(out, "받은 편지 2통 (최신순)");
                eq(true, out.indexOf(b.value()) < out.indexOf(a.value()));
                lacks(out, "비밀 내용");
                lacks(out, "철수");                                                   // 보낸 사람 정보도 목록에는 없다
            });
        });
        test("[E2E] 받은 편지 목록: 답장 편지는 원본을 보낸 사람의 목록에 올라가고, 서버 응답에 전달 대상은 드러나지 않음", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동 (@gildong)");
            withLoginServer(discord, (server, dir) -> {
                TestApi chul = new TestApi(server.port(), SENDER);
                chul.login();
                LetterCode original = chul.sendToDiscord(new LetterDraft("안녕"), DiscordId.of(ID_HONG), SENDER.id()).code();

                TestApi hong = new TestApi(server.port(), HONG);
                hong.login();
                LetterCode reply = hong.reply(original, "답장이에요").code();
                eq(List.of(original), hong.inbox());                                  // 홍길동 목록에는 받은 원본뿐, 자기가 쓴 답장은 없음
                eq(List.of(reply), chul.inbox());
                String body = http("GET", "http://127.0.0.1:" + server.port() + "/letters/" + reply.value(), null, chul.token).body();
                lacks(body, ID_CHUL);
                lacks(body, "deliveredTo");
            });
        });
        test("[E2E] 서버를 다시 시작해도 받은 편지 목록이 유지됨 (세션은 사라져 다시 로그인)", () -> withTempDir(dir -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동 (@gildong)");
            ServerConfig config = new ServerConfig(0, dir, 2_000_000, 1000, ZoneId.of("Asia/Seoul"), null);
            LetterServer first = testServer(config, discord, true);
            first.start();
            LetterCode code;
            TestApi oldSession;
            try {
                oldSession = new TestApi(first.port(), SENDER);
                oldSession.login();
                code = oldSession.sendToDiscord(new LetterDraft("안녕"), DiscordId.of(ID_HONG)).code();
            } finally {
                first.stop();
            }
            LetterServer second = testServer(config, discord, true);
            second.start();
            try {
                TestApi hong = new TestApi(second.port(), HONG);
                hong.login();
                eq(List.of(code), hong.inbox());
                var res = anonymousHttp("GET", "http://127.0.0.1:" + second.port() + "/letters/inbox", null, oldSession.token);
                eq(401, res.statusCode());                                           // 옛 세션은 무효
            } finally {
                second.stop();
            }
        }));
        test("[E2E] 로그인 API: 인증 코드는 DM으로만 가고, 틀리면 403(남은 횟수), 맞으면 로그인 완료", () -> {
            FakeDiscord discord = discordWithUsers();
            withLoginServer(discord, (server, dir) -> {
                HttpLetterApi api = new HttpLetterApi("http://127.0.0.1:" + server.port());
                LoginTicket ticket = api.startLogin(HONG.id());
                eq(300, ticket.expiresInSeconds());
                eq(ID_HONG, discord.loginDms.get(0)[0]);
                has(discord.loginDms.get(0)[1], "인증 코드: `" + LOGIN_CODE + "`");

                try {
                    api.verifyLogin(ticket, "000000");
                    throw new AssertionError("예외가 발생하지 않음");
                } catch (CodeWrongException e) {
                    has(e.getMessage(), "남은 횟수 4번");
                    eq(false, e.isRetryable());
                }
                try {
                    api.inbox();
                    throw new AssertionError("예외가 발생하지 않음");
                } catch (LoginRequiredException expected) {
                    // 아직 로그인 전
                }
                LoginResult ok = api.verifyLogin(ticket, LOGIN_CODE.substring(0, 3) + " " + LOGIN_CODE.substring(3));
                eq(HONG, ok.user());
                eq(List.of(), api.inbox());                                           // 이 api는 이제 로그인 상태
            });
        });
        test("[E2E] 인증 코드를 5번 틀리면 로그인 취소 (프로그램도 코드 재입력 대신 실패를 알게 됨)", () -> {
            FakeDiscord discord = discordWithUsers();
            withLoginServer(discord, (server, dir) -> {
                HttpLetterApi api = new HttpLetterApi("http://127.0.0.1:" + server.port());
                LoginTicket ticket = api.startLogin(HONG.id());
                for (int i = 0; i < 4; i++) {
                    try {
                        api.verifyLogin(ticket, "00000" + i);
                        throw new AssertionError("예외가 발생하지 않음");
                    } catch (CodeWrongException expected) {
                        // 아직 다시 입력할 수 있다
                    }
                }
                try {
                    api.verifyLogin(ticket, "000009");
                    throw new AssertionError("예외가 발생하지 않음");
                } catch (CodeWrongException e) {
                    throw new AssertionError("5번째는 취소여야 함: " + e.getMessage());
                } catch (ApiException e) {
                    eq(false, e.isRetryable());
                    has(e.getMessage(), "취소");
                }
                try {
                    api.verifyLogin(ticket, LOGIN_CODE);                              // 맞는 코드도 소용없다
                    throw new AssertionError("예외가 발생하지 않음");
                } catch (ApiException e) {
                    has(e.getMessage(), "만료되었습니다");
                }
            });
        });
        test("[E2E] loginId를 모르면 코드를 알아도 로그인할 수 없고, 로그인 시작 응답에는 코드가 들어 있지 않음", () -> {
            FakeDiscord discord = discordWithUsers();
            withLoginServer(discord, (server, dir) -> {
                String base = "http://127.0.0.1:" + server.port();
                var start = anonymousHttp("POST", base + "/auth/login", "discordId=" + ID_HONG);
                eq(200, start.statusCode());
                lacks(start.body(), LOGIN_CODE);
                lacks(discord.loginDms.get(0)[1], FormCodec.decode(start.body()).get("loginId"));
                eq(404, anonymousHttp("POST", base + "/auth/verify", "loginId=guess&code=" + LOGIN_CODE).statusCode());
                eq(404, anonymousHttp("POST", base + "/auth/verify", "code=" + LOGIN_CODE).statusCode());
                eq(405, anonymousHttp("GET", base + "/auth/verify", null).statusCode());
            });
        });
        test("[E2E] 로그인 시작 오류: ID 없음·형식 오류는 400, 없는 사용자는 404, DM을 못 보내면 안내와 함께 400", () -> {
            FakeDiscord discord = discordWithUsers();
            withLoginServer(discord, (server, dir) -> {
                String base = "http://127.0.0.1:" + server.port();
                eq(400, anonymousHttp("POST", base + "/auth/login", "discordId=abc").statusCode());
                eq(400, anonymousHttp("POST", base + "/auth/login", "x=y").statusCode());
                eq(404, anonymousHttp("POST", base + "/auth/login", "discordId=99999999999999999").statusCode());
                discord.loginFailure = Reason.DM_NOT_ALLOWED;
                var res = anonymousHttp("POST", base + "/auth/login", "discordId=" + ID_HONG);
                eq(400, res.statusCode());
                has(FormCodec.decode(res.body()).get("error"), "DM으로 보내지 못했어요");
                discord.loginFailure = Reason.UNAVAILABLE;
                eq(503, anonymousHttp("POST", base + "/auth/login", "discordId=" + ID_HONG).statusCode());
                eq(0, discord.loginDms.size());
            });
        });
        test("[E2E] 같은 사람에게 인증 코드 요청은 10분에 3번까지, 4번째는 429 (DM도 가지 않음)", () -> withTempDir(dir -> {
            FakeDiscord discord = discordWithUsers();
            ServerConfig config = new ServerConfig(0, dir, 2_000_000, 1000, ZoneId.of("Asia/Seoul"), null);
            LetterServer server = testServer(config, discord, false);   // 운영과 같은 횟수 제한
            server.start();
            try {
                String base = "http://127.0.0.1:" + server.port();
                for (int i = 0; i < 3; i++) eq(200, anonymousHttp("POST", base + "/auth/login", "discordId=" + ID_HONG).statusCode());
                eq(429, anonymousHttp("POST", base + "/auth/login", "discordId=" + ID_HONG).statusCode());
                eq(3, discord.loginDms.size());
                eq(200, anonymousHttp("POST", base + "/auth/login", "discordId=" + ID_KIM).statusCode());
            } finally {
                server.stop();
            }
        }));
        test("[E2E] 봇이 꺼진 서버(토큰 없음): 로그인을 시작할 수 없다고 안내하고, 편지 기능은 모두 막힘", () -> withTempDir(dir -> {
            ServerConfig config = new ServerConfig(0, dir, 2_000_000, 1000, ZoneId.of("Asia/Seoul"), null);
            LetterServer server = new LetterServer(config);
            server.start();
            try {
                HttpLetterApi api = new HttpLetterApi("http://127.0.0.1:" + server.port());
                try {
                    api.startLogin(SENDER.id());
                    throw new AssertionError("예외가 발생하지 않음");
                } catch (ApiException e) {
                    has(e.getMessage(), "디스코드 로그인을 사용할 수 없습니다");
                }
                eq(401, anonymousHttp("POST", "http://127.0.0.1:" + server.port() + "/letters", "content=hi").statusCode());
            } finally {
                server.stop();
            }
        }));
        test("[E2E] 예전 브라우저 로그인 주소(/auth/callback·poll·confirm)는 더 이상 없음", () -> withLoginServer(new FakeDiscord(), (server, dir) -> {
            String base = "http://127.0.0.1:" + server.port();
            eq(404, anonymousHttp("GET", base + "/auth/callback?state=x&code=y", null).statusCode());
            eq(404, anonymousHttp("POST", base + "/auth/poll", "loginId=x").statusCode());
            eq(404, anonymousHttp("POST", base + "/auth/confirm", "state=x&code=y").statusCode());
        }));
        test("[E2E] 로그아웃하면 그 세션은 바로 무효, 다른 세션은 영향 없음", () -> {
            FakeDiscord discord = discordWithUsers();
            withLoginServer(discord, (server, dir) -> {
                TestApi a = new TestApi(server.port(), SENDER);
                TestApi b = new TestApi(server.port(), HONG);
                a.login();
                b.login();
                String oldToken = a.token;
                a.logout();
                eq(401, anonymousHttp("GET", "http://127.0.0.1:" + server.port() + "/letters/inbox", null, oldToken).statusCode());
                eq(List.of(), b.inbox());
            });
        });
    }

    // ---------------------------------------------------------------- 디스코드 테스트용 도구

    /** 디스코드 대신 쓰는 가짜 게이트웨이. 로그인 인증 코드 DM은 편지·답장 DM과 따로 기록한다. */
    private static class FakeDiscord implements DiscordGateway {
        final Map<String, String> users = new LinkedHashMap<>();
        final List<String[]> dms = new CopyOnWriteArrayList<>();       // {받는 사람 ID, 내용}: 편지·답장 DM
        final List<String[]> loginDms = new CopyOnWriteArrayList<>();  // {받는 사람 ID, 내용}: 로그인 인증 코드 DM
        volatile DiscordGatewayException.Reason dmFailure = null;      // 편지·답장 DM 실패
        volatile DiscordGatewayException.Reason loginFailure = null;   // 로그인 인증 코드 DM 실패
        volatile int lookups = 0;

        FakeDiscord() {
            users.put(ID_CHUL, "철수");                                 // 철수(SENDER)는 항상 로그인할 수 있다
        }

        /** 로그인(사용자 조회 1번 + 인증 코드 DM 1번)을 뺀, 편지 쪽에서 한 사용자 조회 수. */
        int letterLookups() {
            return lookups - loginDms.size();
        }

        @Override
        public Optional<DiscordUser> findUser(DiscordId id) {
            lookups++;
            String name = users.get(id.value());
            return name == null ? Optional.empty() : Optional.of(new DiscordUser(id, name));
        }

        @Override
        public void sendDirectMessage(DiscordId to, String content) throws DiscordGatewayException {
            if (content.startsWith("🔐")) {
                if (loginFailure != null) throw new DiscordGatewayException(loginFailure, "가짜 실패");
                loginDms.add(new String[]{to.value(), content});
                return;
            }
            if (dmFailure != null) throw new DiscordGatewayException(dmFailure, "가짜 실패");
            dms.add(new String[]{to.value(), content});
        }
    }

    /** 디스코드 REST API 흉내를 내는 로컬 HTTP 서버. 받은 요청을 기록한다. */
    private static class FakeDiscordRest implements AutoCloseable {
        final HttpServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();
        volatile int userStatus = 200;
        volatile String userBody = "{\"id\":\"" + ID_HONG + "\",\"username\":\"gildong\"}";
        volatile String channelBody = "{\"id\":\"" + CHANNEL_ID + "\",\"type\":1}";
        volatile int messageStatus = 200;
        volatile String messageBody = "{\"id\":\"1\"}";

        FakeDiscordRest() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String path = ex.getRequestURI().getPath();
                requests.add(ex.getRequestMethod() + " " + path
                        + " | auth=" + ex.getRequestHeaders().getFirst("Authorization")
                        + " | ua=" + ex.getRequestHeaders().getFirst("User-Agent")
                        + " | body=" + body);

                int status;
                String reply;
                if (path.startsWith("/users/@me/channels")) {
                    status = 200;
                    reply = channelBody;
                } else if (path.startsWith("/users/")) {
                    status = userStatus;
                    reply = userBody;
                } else {
                    status = messageStatus;
                    reply = messageBody;
                }
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * 진짜 HttpLetterApi에, 로그인 정보(누구로 로그인할지)와 테스트용 도구를 더한 것.
     * 테스트 서버는 인증 코드를 항상 {@link #LOGIN_CODE}로 만들므로, 콘솔 앱에 입력할 로그인 두 줄을 미리 알 수 있다.
     */
    private static class TestApi extends HttpLetterApi {
        final int port;
        final DiscordUser who;
        volatile String token;
        volatile LoginTicket lastTicket;
        final List<String> tokens = new CopyOnWriteArrayList<>();   // 지금까지 받은 세션 토큰

        TestApi(int port, DiscordUser who) {
            super("http://127.0.0.1:" + port);
            this.port = port;
            this.who = who;
        }

        @Override
        public LoginTicket startLogin(DiscordId id) throws ApiException {
            token = null;
            LoginTicket ticket = super.startLogin(id);
            lastTicket = ticket;
            return ticket;
        }

        @Override
        public LoginResult verifyLogin(LoginTicket ticket, String code) throws ApiException {
            LoginResult result = super.verifyLogin(ticket, code);
            token = result.token();
            tokens.add(token);
            return result;
        }

        @Override
        public void logout() {
            token = null;
            super.logout();
        }

        /** 로그인을 끝까지 해서 이 객체를 로그인 상태로 만든다. */
        LoginResult login() throws ApiException {
            return verifyLogin(startLogin(who.id()), LOGIN_CODE);
        }

        /** 콘솔 앱이 로그인할 때 입력받는 두 줄: 내 디스코드 ID, DM으로 받은 인증 코드. */
        String loginInput() {
            return who.id().value() + "\n" + LOGIN_CODE + "\n";
        }
    }

    /** 테스트 서버를 만든다. 인증 코드는 항상 {@link #LOGIN_CODE}이다. generousLimits면 로그인 시작 횟수 제한을 사실상 없앤다. */
    private static LetterServer testServer(ServerConfig config, DiscordGateway gateway, boolean generousLimits) {
        RateLimiter limiter = generousLimits ? new RateLimiter(100_000, Duration.ofMinutes(10), Clock.systemUTC()) : null;
        return new LetterServer(config, gateway, new FixedCodeRandom(), limiter);
    }

    private interface ServerCheck {
        void run(LetterServer server, TestApi api, Path dataDir) throws Exception;
    }

    private interface LoginServerCheck {
        void run(LetterServer server, Path dataDir) throws Exception;
    }

    /** 진짜 LetterServer를 비어 있는 포트에 띄우고, 철수(SENDER)로 로그인한 api를 준다. gateway가 null이면 디스코드 기능이 꺼진 서버. */
    private static void withServer(DiscordGateway gateway, ServerCheck check) throws Exception {
        withLoginServer(gateway, (server, dir) -> {
            TestApi api = new TestApi(server.port(), SENDER);
            api.login();
            sessionOwner = api;
            try {
                check.run(server, api, dir);
            } finally {
                sessionOwner = null;
            }
        });
    }

    /** 위와 같지만 로그인은 하지 않은 채 서버만 준다. (로그인 자체를 시험할 때) gateway는 철수가 로그인할 수 있어야 한다. */
    private static void withLoginServer(DiscordGateway gateway, LoginServerCheck check) throws Exception {
        withTempDir(dir -> {
            ServerConfig config = new ServerConfig(0, dir, 2_000_000, 1000, ZoneId.of("Asia/Seoul"), null);
            LetterServer server = testServer(config, gateway, true);
            server.start();
            try {
                check.run(server, dir);
            } finally {
                server.stop();
            }
        });
    }

    /** 키보드 입력(script)을 흘려 넣어 콘솔 앱을 실행하고, 화면에 나온 출력을 돌려준다. 시작할 때 로그인이 먼저 이루어진다. */
    private static String runApp(LetterApi api, String script) {
        String output = runAppOnly(api, script);
        relogin(api);   // 앱은 끝날 때 로그아웃하므로, 테스트가 같은 api로 결과를 직접 확인할 수 있게 다시 로그인해 둔다
        return output;
    }

    /** runApp과 같지만 끝난 뒤 다시 로그인하지 않는다. (종료 시 로그아웃을 확인할 때) */
    private static String runAppOnly(LetterApi api, String script) {
        String input = (api instanceof TestApi test ? test.loginInput() : "") + script;   // 로그인 입력을 앞에 붙인다
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConsoleView view = new ConsoleView(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8,
                new PrintStream(out, true, StandardCharsets.UTF_8));
        new LetterApp(api, view).run();
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void relogin(LetterApi api) {
        if (!(api instanceof TestApi test)) return;
        try {
            test.login();
        } catch (ApiException e) {
            throw new AssertionError("다시 로그인하지 못함: " + e.getMessage());
        }
    }

    private static String codeIn(String output) {
        Matcher m = Pattern.compile("고유 코드: ([A-Z2-9]{8})").matcher(output);
        if (!m.find()) throw new AssertionError("출력에 고유 코드가 없음:\n" + output);
        return m.group(1);
    }

    private static int countLetters(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(p -> p.getFileName().toString().endsWith(".txt")).count();
        }
    }

    private static void has(String text, String part) {
        if (!text.contains(part)) throw new AssertionError("'" + part + "' 이(가) 없음:\n" + text);
    }

    private static void lacks(String text, String part) {
        if (text.contains(part)) throw new AssertionError("'" + part + "' 이(가) 있으면 안 됨:\n" + text);
    }

    private interface GatewayCheck {
        void run() throws Exception;
    }

    private static void reason(Reason expected, GatewayCheck action) {
        try {
            action.run();
        } catch (DiscordGatewayException e) {
            eq(expected, e.reason());
            return;
        } catch (Exception e) {
            throw new AssertionError("다른 예외가 발생함: " + e);
        }
        throw new AssertionError("예외가 발생하지 않음");
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

    /** 동작이 LetterException을 던지는지 확인하고 그 예외를 돌려준다. */
    private static LetterException expect(Check action) {
        try {
            action.run();
        } catch (LetterException e) {
            return e;
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
