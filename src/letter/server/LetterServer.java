package letter.server;

import com.sun.net.httpserver.HttpServer;
import letter.server.discord.DiscordGateway;
import letter.server.discord.DiscordRestGateway;
import letter.server.discord.DisabledDiscordGateway;
import letter.server.http.DiscordUserHttpHandler;
import letter.server.http.HttpResponses;
import letter.server.http.LetterHttpHandler;
import letter.server.http.RateLimitedHandler;
import letter.server.http.RateLimiter;
import letter.server.service.LetterDeliveryService;
import letter.server.service.LetterService;
import letter.server.service.RandomCodeGenerator;
import letter.server.storage.FileLetterRepository;
import letter.server.storage.LetterRepository;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 프로그램의 시작점이자 "조립 담당". 어떤 구현체를 쓸지는 여기서만 결정하고,
 * 나머지 클래스들은 인터페이스에만 의존한다.
 */
public class LetterServer {
    /** 같은 사람에게 디스코드 DM이 몰리지 않도록: 받는 사람 한 명당 10분에 3번까지 */
    private static final int DM_LIMIT_PER_TARGET = 3;
    private static final Duration DM_LIMIT_WINDOW = Duration.ofMinutes(10);

    private final ServerConfig config;
    private final DiscordGateway discordOverride;
    private final Logger log = Logger.getLogger(LetterServer.class.getName());

    private HttpServer server;
    private ExecutorService executor;
    private ScheduledExecutorService cleaner;

    public LetterServer(ServerConfig config) {
        this(config, null);
    }

    /** 디스코드 게이트웨이를 직접 지정한다. (테스트에서 가짜 디스코드를 끼워 넣을 때 쓴다. null이면 설정에 따라 고른다.) */
    public LetterServer(ServerConfig config, DiscordGateway discordOverride) {
        this.config = config;
        this.discordOverride = discordOverride;
    }

    /** 실제로 열린 포트. (설정에서 포트를 0으로 주면 비어 있는 포트가 자동으로 정해진다.) */
    public int port() {
        return server.getAddress().getPort();
    }

    public void start() throws IOException {
        // 부품 조립
        LetterRepository repository = new FileLetterRepository(config.dataDir());
        LetterService service = new LetterService(
                repository, new RandomCodeGenerator(new SecureRandom()), Clock.system(config.zone()));
        RateLimiter limiter = new RateLimiter(config.rateLimitPerMinute(), Clock.systemUTC());

        DiscordGateway discord = discordOverride != null ? discordOverride
                : config.discordEnabled() ? new DiscordRestGateway(config.discordBotToken())
                : new DisabledDiscordGateway();
        RateLimiter targetLimiter = new RateLimiter(DM_LIMIT_PER_TARGET, DM_LIMIT_WINDOW, Clock.systemUTC());
        LetterDeliveryService delivery = new LetterDeliveryService(service, discord, targetLimiter);

        server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        executor = Executors.newFixedThreadPool(8);
        server.setExecutor(executor);
        server.createContext("/letters",
                new RateLimitedHandler(new LetterHttpHandler(service, delivery, config.maxBodyBytes()), limiter));
        server.createContext("/discord", new RateLimitedHandler(new DiscordUserHttpHandler(delivery), limiter));
        server.createContext("/health", ex -> HttpResponses.send(ex, 200, "ok"));
        server.createContext("/", ex -> HttpResponses.sendError(ex, 404, "지원하지 않는 주소입니다."));

        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(limiter::evictExpired, 1, 1, TimeUnit.MINUTES);
        cleaner.scheduleAtFixedRate(targetLimiter::evictExpired, 1, 1, TimeUnit.MINUTES);

        server.start();
        log.info("편지 서버 시작: 포트 " + config.port() + ", 데이터 폴더 " + config.dataDir().toAbsolutePath()
                + ", 디스코드 전송 " + (discord instanceof DisabledDiscordGateway ? "꺼짐" : "켜짐"));
    }

    public void stop() {
        log.info("서버를 종료합니다...");
        if (server != null) server.stop(1);
        if (executor != null) executor.shutdown();
        if (cleaner != null) cleaner.shutdownNow();
    }

    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %5$s%6$s%n");
        try {
            ServerConfig config = ServerConfig.from(System.getenv(), args);
            LetterServer server = new LetterServer(config);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        } catch (BindException e) {
            fail("해당 포트를 사용할 수 없습니다. (이미 사용 중이거나 권한이 없습니다)");
        } catch (IllegalArgumentException | IllegalStateException e) {
            fail(e.getMessage());
        } catch (IOException e) {
            fail("서버를 시작하지 못했습니다: " + e.getMessage());
        }
    }

    private static void fail(String message) {
        System.err.println("[오류] " + message);
        System.exit(1);
    }
}
