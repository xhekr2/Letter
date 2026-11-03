package letter;

import letter.client.ApiException;
import letter.client.ConsoleView;
import letter.client.HttpLetterApi;
import letter.client.LetterApi;
import letter.client.LetterApp;
import letter.domain.Delivery;
import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LetterException;
import letter.domain.LetterException.Kind;
import letter.domain.Receipt;
import letter.protocol.DiscordForm;
import letter.protocol.FormCodec;
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
import letter.server.storage.FileLetterRepository;
import letter.server.storage.InMemoryLetterRepository;
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

        discordTests();

        System.out.printf("%n결과: %d개 통과, %d개 실패%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ======================================================================== 디스코드 전송

    private static final String ID_HONG = "123456789012345678";
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

            Receipt receipt = delivery.sendTo(new LetterDraft("철수", "비밀 내용"), DiscordId.of(ID_HONG));
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

            Receipt receipt = delivery.sendTo(new LetterDraft("철수", "안녕"), DiscordId.of(ID_HONG));
            eq(Delivery.FAILED, receipt.delivery());
            eq("안녕", letters.read(receipt.code()).content());
        });
        test("LetterDeliveryService: 같은 사람에게 너무 자주 보내면 RATE_LIMITED (편지도 저장 안 함)", () -> {
            MutableClock clock = new MutableClock();
            FakeDiscord discord = new FakeDiscord();
            LetterService letters = new LetterService(new InMemoryLetterRepository(), new SequenceCodeGenerator("AAAAAAAA", "BBBBBBBB", "CCCCCCCC", "DDDDDDDD", "EEEEEEEE"), FIXED_CLOCK);
            LetterDeliveryService delivery = new LetterDeliveryService(letters, discord, new RateLimiter(3, Duration.ofMinutes(10), clock));
            DiscordId target = DiscordId.of(ID_HONG);
            for (int i = 0; i < 3; i++) delivery.sendTo(new LetterDraft("a", "x"), target);

            kind(Kind.RATE_LIMITED, () -> delivery.sendTo(new LetterDraft("a", "x"), target));
            kind(Kind.NOT_FOUND, () -> letters.read(LetterCode.of("DDDDDDDD")));        // 4번째 편지는 저장되지 않음
            eq(3, discord.dms.size());
            delivery.sendTo(new LetterDraft("a", "x"), DiscordId.of("987654321098765432"));   // 다른 사람에게는 가능
            clock.advanceSeconds(601);
            delivery.sendTo(new LetterDraft("a", "x"), target);                            // 10분 뒤에는 다시 가능
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
        String sendFlow = "3\n철수\n안녕하세요\n두번째 줄\n/end\ny\n" + ID_HONG + "\n";

        test("[E2E] 메뉴 3: 편지 작성 → ID 입력 → 이름 확인 → 봇이 코드만 DM 전달", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동 (@gildong)");
            withServer(discord, (server, api, dir) -> {
                String out = runApp(api, sendFlow + "y\n4\n");
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
                String out = runApp(api, sendFlow + "n\n\n4\n");                    // 'n' 후 ID를 비워 취소
                has(out, "다시 입력해 주세요.");
                has(out, "발송을 취소했습니다.");
                eq(0, discord.dms.size());
                eq(0, countLetters(dir));
            });
        });
        test("[E2E] 없는 디스코드 ID는 안내하고 편지를 보내지 않음", () -> {
            FakeDiscord discord = new FakeDiscord();
            withServer(discord, (server, api, dir) -> {
                String out = runApp(api, "3\n철수\n안녕\n/end\ny\n111111111111111111\n\n4\n");
                has(out, "해당 ID의 디스코드 사용자를 찾을 수 없습니다.");
                eq(0, discord.dms.size());
                eq(0, countLetters(dir));
            });
        });
        test("[E2E] ID 형식이 틀리면 서버에 묻기 전에 안내", () -> {
            FakeDiscord discord = new FakeDiscord();
            withServer(discord, (server, api, dir) -> {
                String out = runApp(api, "3\n철수\n안녕\n/end\ny\nabc\n홍길동#1234\n\n4\n");
                has(out, "올바르지 않은 디스코드 ID입니다.");
                eq(0, discord.lookups);
                eq(0, countLetters(dir));
            });
        });
        test("[E2E] 디스코드 전달이 실패해도 편지는 발송되고 코드를 직접 전달하도록 안내", () -> {
            FakeDiscord discord = new FakeDiscord();
            discord.users.put(ID_HONG, "홍길동");
            discord.dmFailure = DiscordGatewayException.Reason.DM_NOT_ALLOWED;
            withServer(discord, (server, api, dir) -> {
                String out = runApp(api, sendFlow + "y\n4\n");
                has(out, "디스코드로 코드를 전달하지 못했어요.");
                has(out, "직접 전달해 주세요.");
                eq("안녕하세요\n두번째 줄", api.read(LetterCode.of(codeIn(out))).content());
                eq(0, discord.dms.size());
            });
        });
        test("[E2E] 디스코드 설정이 없는 서버(봇 토큰 없음)는 안내하고 편지를 보내지 않음", () -> {
            withServer(null, (server, api, dir) -> {                                // 토큰도 가짜도 없음 = 기능 꺼짐
                String out = runApp(api, "3\n철수\n안녕\n/end\ny\n" + ID_HONG + "\n\n4\n");
                has(out, "이 서버에서는 디스코드 전송 기능을 사용할 수 없습니다.");
                eq(0, countLetters(dir));
            });
        });
        test("[E2E] 같은 사람에게 연속으로 보내면 4번째부터 제한", () -> {
            FakeDiscord discord = new FakeDiscord();
            withServer(discord, (server, api, dir) -> {
                LetterDraft draft = new LetterDraft("철수", "안녕");
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
                String out = runApp(api, "1\n\n그냥 편지\n/end\ny\n4\n");
                has(out, "프로그램을 종료합니다.");
                String code = codeIn(out);
                String shown = runApp(api, "2\n" + code + "\n4\n");
                has(shown, "보낸 사람: 익명");
                has(shown, "그냥 편지");
                has(runApp(api, "9\n4\n"), "잘못된 입력입니다.");
            });
        });
    }

    // ---------------------------------------------------------------- 디스코드 테스트용 도구

    /** 디스코드 대신 쓰는 가짜 게이트웨이. */
    private static class FakeDiscord implements DiscordGateway {
        final Map<String, String> users = new LinkedHashMap<>();
        final List<String[]> dms = new CopyOnWriteArrayList<>();      // {받는 사람 ID, 내용}
        volatile DiscordGatewayException.Reason dmFailure = null;
        volatile int lookups = 0;

        @Override
        public Optional<DiscordUser> findUser(DiscordId id) {
            lookups++;
            String name = users.get(id.value());
            return name == null ? Optional.empty() : Optional.of(new DiscordUser(id, name));
        }

        @Override
        public void sendDirectMessage(DiscordId to, String content) throws DiscordGatewayException {
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

    private interface ServerCheck {
        void run(LetterServer server, HttpLetterApi api, Path dataDir) throws Exception;
    }

    /** 진짜 LetterServer를 비어 있는 포트에 띄운다. gateway가 null이면 디스코드 기능이 꺼진 서버(토큰 없음). */
    private static void withServer(DiscordGateway gateway, ServerCheck check) throws Exception {
        withTempDir(dir -> {
            ServerConfig config = new ServerConfig(0, dir, 2_000_000, 1000, ZoneId.of("Asia/Seoul"), null);
            LetterServer server = new LetterServer(config, gateway);
            server.start();
            try {
                check.run(server, new HttpLetterApi("http://127.0.0.1:" + server.port()), dir);
            } finally {
                server.stop();
            }
        });
    }

    /** 키보드 입력(script)을 흘려 넣어 콘솔 앱을 실행하고, 화면에 나온 출력을 돌려준다. */
    private static String runApp(LetterApi api, String script) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConsoleView view = new ConsoleView(
                new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8,
                new PrintStream(out, true, StandardCharsets.UTF_8));
        new LetterApp(api, view).run();
        return out.toString(StandardCharsets.UTF_8);
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
