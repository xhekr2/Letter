package letter.server.service;

import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.LetterException;
import letter.domain.LoginResult;
import letter.domain.LoginTicket;
import letter.server.discord.DiscordGateway;
import letter.server.discord.DiscordGatewayException;
import letter.server.http.RateLimiter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 디스코드 DM 인증 코드 로그인과 세션 관리. HTTP를 모른다.
 *
 * <ol>
 *   <li>{@link #start}: 사용자가 프로그램에 자기 디스코드 ID를 입력한다. 서버는 6자리 숫자 코드를 만들어
 *       봇이 그 사람에게 DM으로 보내게 하고, 프로그램만 아는 {@code loginId}를 돌려준다.</li>
 *   <li>{@link #verify}: 사용자가 DM에서 확인한 코드를 프로그램에 입력한다. 맞으면 세션 토큰을 돌려준다.
 *       그 계정의 DM을 볼 수 있는 사람만 로그인할 수 있다.</li>
 * </ol>
 * 코드는 {@link #LOGIN_TTL} 동안만 유효하고 {@value #MAX_CODE_ATTEMPTS}번까지만 틀릴 수 있다. 같은 사람에게 DM이 몰리지 않도록
 * 로그인 시작도 횟수를 제한한다. 세션과 진행 중인 로그인은 메모리에만 있으므로 서버를 재시작하면 모두 다시 로그인해야 한다.
 */
public class LoginService implements Authenticator {
    private static final Logger LOG = Logger.getLogger(LoginService.class.getName());

    /** 인증 코드가 유효한 시간 */
    public static final Duration LOGIN_TTL = Duration.ofMinutes(5);
    /** 한 번 로그인한 세션이 유지되는 시간 (프로그램을 다시 시작하면 어차피 다시 로그인한다) */
    public static final Duration SESSION_TTL = Duration.ofHours(12);
    /** 동시에 진행 중일 수 있는 로그인 수. 로그인 시작은 누구나 할 수 있어서 메모리가 무한히 늘지 않게 한다. */
    static final int MAX_PENDING_LOGINS = 2000;
    /** 인증 코드를 틀릴 수 있는 횟수. 넘으면 그 로그인은 취소된다. */
    static final int MAX_CODE_ATTEMPTS = 5;

    private static final int CODE_DIGITS = 6;
    private static final int TOKEN_BYTES = 32;

    /** 진행 중인 로그인 하나. 모든 필드는 {@link #lock}을 잡고 읽고 쓴다. */
    private static final class Pending {
        final String loginId;
        final DiscordUser user;
        final String code;
        final long expiresAtMillis;
        int wrongAttempts;

        Pending(String loginId, DiscordUser user, String code, long expiresAtMillis) {
            this.loginId = loginId;
            this.user = user;
            this.code = code;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private record Session(DiscordUser user, long expiresAtMillis) {
    }

    private final DiscordGateway discord;
    private final Clock clock;
    private final Random random;
    private final RateLimiter startLimiter;

    private final Object lock = new Object();
    private final Map<String, Pending> byLoginId = new HashMap<>();
    private final Map<String, Pending> byUserId = new HashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * @param random       토큰과 인증 코드를 만드는 난수. 운영에서는 SecureRandom을 넣는다.
     * @param startLimiter 로그인 시작 횟수 제한. (키: 디스코드 ID) 같은 사람에게 인증 코드 DM이 몰리는 것을 막는다.
     */
    public LoginService(DiscordGateway discord, Clock clock, Random random, RateLimiter startLimiter) {
        this.discord = Objects.requireNonNull(discord);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
        this.startLimiter = Objects.requireNonNull(startLimiter);
    }

    /**
     * 로그인을 시작한다. 봇이 해당 사용자에게 인증 코드를 DM으로 보낸다.
     * 같은 사용자의 이전 로그인 요청은 이 요청으로 대체된다.
     *
     * @throws LetterException 없는 사용자면 NOT_FOUND, DM을 보낼 수 없으면 INVALID, 너무 자주 요청하거나 진행 중인
     *                         로그인이 너무 많으면 RATE_LIMITED, 디스코드를 쓸 수 없으면 UNAVAILABLE
     */
    public LoginTicket start(DiscordId id) {
        Objects.requireNonNull(id, "id");
        if (!startLimiter.tryAcquire(id.value())) {
            throw LetterException.rateLimited("인증 코드를 너무 자주 요청했습니다. 잠시 후 다시 시도해 주세요.");
        }

        DiscordUser user;
        try {
            user = discord.findUser(id)
                    .orElseThrow(() -> LetterException.notFound("해당 ID의 디스코드 사용자를 찾을 수 없습니다."));
        } catch (DiscordGatewayException e) {
            throw failure(e);
        }

        String code = randomCode();
        Pending pending = new Pending(randomToken(), user, code, clock.millis() + LOGIN_TTL.toMillis());
        synchronized (lock) {
            evictExpiredLocked();
            Pending previous = byUserId.get(id.value());
            if (previous == null && byLoginId.size() >= MAX_PENDING_LOGINS) {
                throw LetterException.rateLimited("지금 로그인하려는 사람이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
            }
            if (previous != null) remove(previous);
            byLoginId.put(pending.loginId, pending);
            byUserId.put(id.value(), pending);
        }

        try {
            discord.sendDirectMessage(id, DiscordMessages.loginCode(code, (int) LOGIN_TTL.toMinutes()));
        } catch (DiscordGatewayException e) {
            synchronized (lock) {
                remove(pending);
            }
            throw failure(e);
        }
        return new LoginTicket(pending.loginId, (int) LOGIN_TTL.toSeconds());
    }

    /**
     * DM으로 받은 인증 코드를 확인하고, 맞으면 로그인을 끝낸다. 코드는 한 번만 쓸 수 있다.
     * 틀린 횟수가 {@value #MAX_CODE_ATTEMPTS}번에 이르면 그 로그인은 취소된다.
     *
     * @throws LetterException 로그인 요청이 없거나 만료되었으면 NOT_FOUND, 코드가 틀렸으면 CODE_WRONG(남은 횟수가 있을 때),
     *                         틀린 횟수가 다 찼으면 INVALID
     */
    public LoginResult verify(String loginId, String enteredCode) {
        synchronized (lock) {
            Pending pending = livePending(loginId);
            if (pending == null) {
                throw LetterException.notFound("로그인 요청이 없거나 만료되었습니다. 다시 로그인해 주세요.");
            }

            if (!sameCode(enteredCode, pending.code)) {
                pending.wrongAttempts++;
                if (pending.wrongAttempts >= MAX_CODE_ATTEMPTS) {
                    remove(pending);
                    throw LetterException.invalid("인증 코드를 여러 번 틀려 이 로그인을 취소했습니다. 다시 로그인해 주세요.");
                }
                throw LetterException.codeWrong("인증 코드가 맞지 않아요. (남은 횟수 "
                        + (MAX_CODE_ATTEMPTS - pending.wrongAttempts) + "번)");
            }

            remove(pending);                        // 코드는 한 번만 쓴다
            String token = randomToken();
            sessions.put(token, new Session(pending.user, clock.millis() + SESSION_TTL.toMillis()));
            return new LoginResult(token, pending.user);
        }
    }

    @Override
    public DiscordUser authenticate(String token) {
        if (token != null) {
            Session session = sessions.get(token);
            if (session != null) {
                if (clock.millis() < session.expiresAtMillis()) return session.user();
                sessions.remove(token);
            }
        }
        throw LetterException.unauthenticated("로그인이 필요합니다. 프로그램을 다시 시작해 디스코드로 로그인해 주세요.");
    }

    /** 세션을 끝낸다. (모르는 토큰이면 아무 일도 없다) */
    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    /** 만료된 로그인 요청과 세션을 정리한다. (주기적으로 호출) */
    public void evictExpired() {
        synchronized (lock) {
            evictExpiredLocked();
        }
        long now = clock.millis();
        sessions.values().removeIf(s -> now >= s.expiresAtMillis());
    }

    /** 현재 유효한 세션 수. (테스트와 점검용) */
    public int sessionCount() {
        return sessions.size();
    }

    // ---------------------------------------------------------------- 내부

    private Pending livePending(String loginId) {
        if (loginId == null) return null;
        Pending pending = byLoginId.get(loginId);
        if (pending == null) return null;
        if (clock.millis() >= pending.expiresAtMillis) {
            remove(pending);
            return null;
        }
        return pending;
    }

    private void remove(Pending pending) {
        byLoginId.remove(pending.loginId);
        byUserId.remove(pending.user.id().value(), pending);
    }

    private void evictExpiredLocked() {
        long now = clock.millis();
        byLoginId.values().removeIf(p -> {
            if (now < p.expiresAtMillis) return false;
            byUserId.remove(p.user.id().value(), p);
            return true;
        });
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 0으로 시작해도 6자리가 되도록 앞을 0으로 채운다. */
    private String randomCode() {
        return String.format(Locale.ROOT, "%0" + CODE_DIGITS + "d", random.nextInt((int) Math.pow(10, CODE_DIGITS)));
    }

    /** 숫자 외의 글자(공백·하이픈)는 무시하고, 시간 차이로 값을 짐작할 수 없게 비교한다. */
    private static boolean sameCode(String entered, String expected) {
        String digits = entered == null ? "" : entered.replaceAll("[^0-9]", "");
        return MessageDigest.isEqual(digits.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static LetterException failure(DiscordGatewayException e) {
        return switch (e.reason()) {
            case DM_NOT_ALLOWED -> LetterException.invalid("인증 코드를 DM으로 보내지 못했어요. "
                    + "편지 봇이 있는 디스코드 서버에 함께 있는지, 그 서버 멤버의 DM 수신을 허용했는지 확인해 주세요.");
            case NOT_CONFIGURED -> {
                LOG.warning("디스코드 로그인 실패 (NOT_CONFIGURED): " + e.getMessage());
                yield LetterException.unavailable("이 서버에서는 디스코드 로그인을 사용할 수 없습니다.", e);
            }
            case UNAVAILABLE -> {
                LOG.warning("디스코드 로그인 실패 (UNAVAILABLE): " + e.getMessage());
                yield LetterException.unavailable("디스코드와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
            }
        };
    }
}
