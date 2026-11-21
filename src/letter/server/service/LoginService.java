package letter.server.service;

import letter.domain.DiscordUser;
import letter.domain.LetterCode;
import letter.domain.LetterException;
import letter.domain.LoginPoll;
import letter.domain.LoginTicket;
import letter.server.discord.DiscordGatewayException;
import letter.server.discord.DiscordOAuth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 디스코드 OAuth 로그인과 세션 관리. HTTP를 모른다.
 *
 * <p>프로그램(콘솔)은 브라우저가 아니라서 디스코드 승인 화면을 직접 띄울 수 없으므로, 서버가 중간에서 이어 준다.
 * <ol>
 *   <li>{@link #start()}: 프로그램이 로그인을 시작한다. 승인 주소와, 프로그램만 아는 {@code loginId}, 확인 코드를 받는다.</li>
 *   <li>사용자가 브라우저에서 승인 주소를 열고 디스코드에서 승인하면, 디스코드가 {@code state}와 {@code code}를 붙여
 *       우리 서버로 돌려보낸다. ({@link #receiveApproval})</li>
 *   <li>브라우저 화면에서 프로그램에 표시된 확인 코드를 입력한다. ({@link #confirm}) 남이 만든 로그인 링크로
 *       내 계정이 그 사람의 프로그램에 연결되는 것을 막는다.</li>
 *   <li>프로그램이 {@link #poll}로 기다리다가 세션 토큰을 받는다. 이후 모든 요청에 이 토큰을 싣는다.</li>
 * </ol>
 * {@code state}는 브라우저 주소에 보이지만 {@code loginId}는 프로그램만 아는 값이라, 주소를 본 사람이 세션을 가로챌 수 없다.
 * 세션과 진행 중인 로그인은 메모리에만 있으므로 서버를 재시작하면 모두 다시 로그인해야 한다.
 */
public class LoginService implements Authenticator {
    private static final Logger LOG = Logger.getLogger(LoginService.class.getName());

    /** 로그인을 시작하고 끝내기까지 주어지는 시간 */
    public static final Duration LOGIN_TTL = Duration.ofMinutes(10);
    /** 한 번 로그인한 세션이 유지되는 시간 (프로그램을 다시 시작하면 어차피 다시 로그인한다) */
    public static final Duration SESSION_TTL = Duration.ofHours(12);
    /** 동시에 진행 중일 수 있는 로그인 수. 로그인 시작은 누구나 할 수 있어서 메모리가 무한히 늘지 않게 한다. */
    static final int MAX_PENDING_LOGINS = 2000;
    /** 확인 코드를 틀릴 수 있는 횟수. 넘으면 그 로그인은 취소된다. */
    static final int MAX_CONFIRM_ATTEMPTS = 5;

    private static final int CONFIRM_CODE_LENGTH = 6;
    private static final int TOKEN_BYTES = 32;

    private enum Stage { AWAITING_DISCORD, EXCHANGING, AWAITING_CONFIRM, APPROVED }

    /** 진행 중인 로그인 하나. 모든 필드는 {@link #lock}을 잡고 읽고 쓴다. */
    private static final class Pending {
        final String loginId;
        final String state;
        final String confirmCode;
        final long expiresAtMillis;
        Stage stage = Stage.AWAITING_DISCORD;
        DiscordUser user;
        String sessionToken;
        int wrongAttempts;

        Pending(String loginId, String state, String confirmCode, long expiresAtMillis) {
            this.loginId = loginId;
            this.state = state;
            this.confirmCode = confirmCode;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private record Session(DiscordUser user, long expiresAtMillis) {
    }

    private final DiscordOAuth oauth;
    private final Clock clock;
    private final Random random;

    private final Object lock = new Object();
    private final Map<String, Pending> byLoginId = new HashMap<>();
    private final Map<String, Pending> byState = new HashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** @param random 토큰과 확인 코드를 만드는 난수. 운영에서는 SecureRandom을 넣는다. */
    public LoginService(DiscordOAuth oauth, Clock clock, Random random) {
        this.oauth = Objects.requireNonNull(oauth);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    /**
     * 로그인을 시작한다.
     *
     * @throws LetterException 로그인 설정이 없으면 UNAVAILABLE, 진행 중인 로그인이 너무 많으면 RATE_LIMITED
     */
    public LoginTicket start() {
        String state = randomToken();
        String loginId = randomToken();
        String confirmCode = randomConfirmCode();

        String url;
        try {
            url = oauth.authorizeUrl(state);
        } catch (DiscordGatewayException e) {
            throw unavailable(e);
        }

        synchronized (lock) {
            evictExpiredLocked();
            if (byLoginId.size() >= MAX_PENDING_LOGINS) {
                throw LetterException.rateLimited("지금 로그인하려는 사람이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
            }
            Pending pending = new Pending(loginId, state, confirmCode, clock.millis() + LOGIN_TTL.toMillis());
            byLoginId.put(loginId, pending);
            byState.put(state, pending);
        }
        return new LoginTicket(loginId, url, confirmCode, (int) LOGIN_TTL.toSeconds());
    }

    /**
     * 디스코드에서 승인을 마치고 돌아온 사용자를 확인한다. 이 시점에는 아직 로그인이 끝난 것이 아니고,
     * 확인 코드({@link #confirm})까지 맞아야 끝난다.
     *
     * @return 승인한 디스코드 계정 (확인 화면에 "이 계정으로 로그인합니다"라고 보여 주기 위한 값)
     * @throws LetterException 모르거나 만료되었거나 이미 쓴 state면 INVALID, 디스코드가 코드를 거부하면 INVALID,
     *                         디스코드와 통신하지 못하면 UNAVAILABLE
     */
    public DiscordUser receiveApproval(String state, String code) {
        if (code == null || code.isBlank()) {
            throw LetterException.invalid("디스코드가 승인 정보를 보내지 않았습니다. 프로그램에서 다시 로그인해 주세요.");
        }
        Pending pending;
        synchronized (lock) {
            pending = livePending(byState, state);
            if (pending == null || pending.stage != Stage.AWAITING_DISCORD) throw expiredLogin();
            pending.stage = Stage.EXCHANGING;      // 같은 state로 두 번 들어오는 것을 막는다 (한 번만 쓸 수 있다)
        }

        DiscordUser user;
        try {
            user = oauth.identify(code);           // 디스코드와 통신하는 동안에는 잠그지 않는다
        } catch (DiscordGatewayException e) {
            synchronized (lock) {
                remove(pending);
            }
            if (e.reason() == DiscordGatewayException.Reason.INVALID_CODE) {
                throw LetterException.invalid("디스코드 로그인 승인을 확인하지 못했습니다. 프로그램에서 다시 로그인해 주세요.");
            }
            throw unavailable(e);
        }

        synchronized (lock) {
            if (livePending(byLoginId, pending.loginId) != pending) throw expiredLogin();   // 그 사이에 만료됨
            pending.user = user;
            pending.stage = Stage.AWAITING_CONFIRM;
        }
        return user;
    }

    /** 사용자가 디스코드 승인 화면에서 "취소"를 눌러 돌아온 경우. 그 로그인 요청을 지운다. (모르는 state면 아무 일도 없다) */
    public void deny(String state) {
        synchronized (lock) {
            Pending pending = livePending(byState, state);
            if (pending != null && pending.stage == Stage.AWAITING_DISCORD) remove(pending);
        }
    }

    /**
     * 브라우저 화면에서 입력한 확인 코드가 프로그램에 표시된 코드와 같은지 보고, 맞으면 로그인을 끝낸다.
     * 틀린 횟수가 {@value #MAX_CONFIRM_ATTEMPTS}번을 넘으면 그 로그인은 취소된다.
     *
     * @return 로그인한 디스코드 계정
     * @throws LetterException 요청이 만료되었거나 이미 끝났으면 INVALID, 확인 코드가 틀려도 INVALID
     */
    public DiscordUser confirm(String state, String enteredCode) {
        synchronized (lock) {
            Pending pending = livePending(byState, state);
            if (pending == null || pending.stage != Stage.AWAITING_CONFIRM) throw expiredLogin();

            if (!sameCode(enteredCode, pending.confirmCode)) {
                pending.wrongAttempts++;
                if (pending.wrongAttempts >= MAX_CONFIRM_ATTEMPTS) {
                    remove(pending);
                    throw LetterException.invalid("확인 코드를 여러 번 틀려 이 로그인을 취소했습니다. 프로그램에서 다시 로그인해 주세요.");
                }
                throw LetterException.invalid("확인 코드가 맞지 않습니다. 편지 프로그램 화면에 표시된 코드를 다시 확인해 주세요.");
            }

            String token = randomToken();
            sessions.put(token, new Session(pending.user, clock.millis() + SESSION_TTL.toMillis()));
            pending.sessionToken = token;
            pending.stage = Stage.APPROVED;
            byState.remove(pending.state);          // state는 한 번만 쓴다. loginId는 프로그램이 결과를 받아 갈 때까지 남긴다.
            return pending.user;
        }
    }

    /**
     * 확인 코드 입력을 기다리는 로그인이면 그 계정을 돌려준다. (코드를 틀렸을 때 같은 화면을 다시 보여 주기 위한 조회)
     * 이미 끝났거나 만료되었거나 취소된 로그인이면 빈 값이다.
     */
    public Optional<DiscordUser> awaitingConfirmation(String state) {
        synchronized (lock) {
            Pending pending = livePending(byState, state);
            return pending != null && pending.stage == Stage.AWAITING_CONFIRM
                    ? Optional.ofNullable(pending.user) : Optional.empty();
        }
    }

    /**
     * 프로그램이 로그인이 끝났는지 묻는다. 끝났으면 세션 토큰을 한 번만 돌려준다.
     *
     * @throws LetterException 로그인 요청이 없거나 만료되었으면 NOT_FOUND
     */
    public LoginPoll poll(String loginId) {
        synchronized (lock) {
            Pending pending = livePending(byLoginId, loginId);
            if (pending == null) {
                throw LetterException.notFound("로그인 요청이 없거나 만료되었습니다. 다시 로그인해 주세요.");
            }
            if (pending.stage != Stage.APPROVED) return LoginPoll.pending();
            remove(pending);                        // 토큰은 한 번만 건네준다
            return new LoginPoll(pending.sessionToken, pending.user);
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

    private Pending livePending(Map<String, Pending> index, String key) {
        if (key == null) return null;
        Pending pending = index.get(key);
        if (pending == null) return null;
        if (clock.millis() >= pending.expiresAtMillis) {
            remove(pending);
            return null;
        }
        return pending;
    }

    private void remove(Pending pending) {
        byLoginId.remove(pending.loginId);
        byState.remove(pending.state);
    }

    private void evictExpiredLocked() {
        long now = clock.millis();
        byLoginId.values().removeIf(p -> {
            if (now < p.expiresAtMillis) return false;
            byState.remove(p.state);
            return true;
        });
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 사람이 보고 입력하는 코드라 헷갈리는 글자를 뺀 문자만 쓰고, 읽기 쉽게 "ABC-DEF"로 끊는다. */
    private String randomConfirmCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CONFIRM_CODE_LENGTH; i++) {
            if (i == CONFIRM_CODE_LENGTH / 2) sb.append('-');
            sb.append(LetterCode.ALPHABET.charAt(random.nextInt(LetterCode.ALPHABET.length())));
        }
        return sb.toString();
    }

    /** 대소문자·하이픈·공백 차이는 무시하고, 시간 차이로 값을 짐작할 수 없게 비교한다. */
    private static boolean sameCode(String entered, String expected) {
        byte[] a = normalize(entered).getBytes(StandardCharsets.UTF_8);
        byte[] b = normalize(expected).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static String normalize(String code) {
        return code == null ? "" : code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static LetterException expiredLogin() {
        return LetterException.invalid("이미 사용했거나 만료된 로그인 요청입니다. 편지 프로그램에서 다시 로그인해 주세요.");
    }

    private static LetterException unavailable(DiscordGatewayException e) {
        // 원인(설정 오류 포함)을 관리자가 알 수 있게 남긴다. 메시지에는 비밀 값이 들어 있지 않다.
        LOG.warning("디스코드 로그인 실패 (" + e.reason() + "): " + e.getMessage());
        String message = e.reason() == DiscordGatewayException.Reason.NOT_CONFIGURED
                ? "이 서버에서는 디스코드 로그인을 사용할 수 없습니다."
                : "디스코드와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        return LetterException.unavailable(message, e);
    }
}
