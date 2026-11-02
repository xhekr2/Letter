package letter.server;

import com.sun.net.httpserver.HttpServer;
import letter.server.http.HttpResponses;
import letter.server.http.LetterHttpHandler;
import letter.server.http.RateLimitedHandler;
import letter.server.http.RateLimiter;
import letter.server.service.LetterService;
import letter.server.service.RandomCodeGenerator;
import letter.server.storage.FileLetterRepository;
import letter.server.storage.LetterRepository;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Clock;
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
    private final ServerConfig config;
    private final Logger log = Logger.getLogger(LetterServer.class.getName());

    private HttpServer server;
    private ExecutorService executor;
    private ScheduledExecutorService cleaner;

    public LetterServer(ServerConfig config) {
        this.config = config;
    }

    public void start() throws IOException {
        // 부품 조립
        LetterRepository repository = new FileLetterRepository(config.dataDir());
        LetterService service = new LetterService(
                repository, new RandomCodeGenerator(new SecureRandom()), Clock.system(config.zone()));
        RateLimiter limiter = new RateLimiter(config.rateLimitPerMinute(), Clock.systemUTC());

        server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        executor = Executors.newFixedThreadPool(8);
        server.setExecutor(executor);
        server.createContext("/letters",
                new RateLimitedHandler(new LetterHttpHandler(service, config.maxBodyBytes()), limiter));
        server.createContext("/health", ex -> HttpResponses.send(ex, 200, "ok"));
        server.createContext("/", ex -> HttpResponses.sendError(ex, 404, "지원하지 않는 주소입니다."));

        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(limiter::evictExpired, 1, 1, TimeUnit.MINUTES);

        server.start();
        log.info("편지 서버 시작: 포트 " + config.port() + ", 데이터 폴더 " + config.dataDir().toAbsolutePath());
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
