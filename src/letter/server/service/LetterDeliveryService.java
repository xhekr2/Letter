package letter.server.service;

import letter.domain.Delivery;
import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LetterException;
import letter.domain.Receipt;
import letter.server.discord.DiscordGateway;
import letter.server.discord.DiscordGatewayException;
import letter.server.http.RateLimiter;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * "편지를 보내고, 그 코드를 디스코드 봇이 상대에게 전달"하는 업무 규칙. HTTP를 모른다.
 * 편지 저장은 {@link LetterService}에 맡기고, 디스코드와의 통신은 {@link DiscordGateway}에 맡긴다.
 */
public class LetterDeliveryService {
    private static final Logger LOG = Logger.getLogger(LetterDeliveryService.class.getName());

    private final LetterService letters;
    private final DiscordGateway discord;
    private final RateLimiter targetLimiter;

    /** @param targetLimiter 같은 사람에게 DM이 몰리지 않게 막는다. (키: 받는 사람의 디스코드 ID) */
    public LetterDeliveryService(LetterService letters, DiscordGateway discord, RateLimiter targetLimiter) {
        this.letters = Objects.requireNonNull(letters);
        this.discord = Objects.requireNonNull(discord);
        this.targetLimiter = Objects.requireNonNull(targetLimiter);
    }

    /**
     * 보내기 전에 "이 사람이 맞나요?"를 확인하기 위한 조회.
     *
     * @throws LetterException 없는 사용자면 NOT_FOUND, 디스코드를 쓸 수 없으면 UNAVAILABLE
     */
    public DiscordUser lookup(DiscordId id) {
        try {
            return discord.findUser(id)
                    .orElseThrow(() -> LetterException.notFound("해당 ID의 디스코드 사용자를 찾을 수 없습니다."));
        } catch (DiscordGatewayException e) {
            throw unavailable(e);
        }
    }

    /**
     * 편지를 저장하고 코드를 상대에게 DM으로 보낸다.
     * 디스코드 전달에 실패해도 편지는 이미 저장된 상태이므로 예외 대신 {@link Delivery#FAILED}로 알려 준다.
     *
     * @throws LetterException 같은 사람에게 너무 자주 보내면 RATE_LIMITED (이때는 편지도 저장하지 않는다)
     */
    public Receipt sendTo(LetterDraft draft, DiscordId to) {
        if (!targetLimiter.tryAcquire(to.value())) {
            throw LetterException.rateLimited("해당 사용자에게 너무 자주 보내고 있습니다. 잠시 후 다시 시도해 주세요.");
        }

        LetterCode code = letters.send(draft);
        try {
            discord.sendDirectMessage(to, DiscordMessages.letterArrived(draft.author(), code));
            return new Receipt(code, Delivery.SENT);
        } catch (DiscordGatewayException e) {
            // 받는 사람의 ID와 편지 코드는 로그에 남기지 않는다
            LOG.warning("디스코드 전달 실패 (" + e.reason() + "): " + e.getMessage());
            return new Receipt(code, Delivery.FAILED);
        }
    }

    private static LetterException unavailable(DiscordGatewayException e) {
        if (e.reason() == DiscordGatewayException.Reason.UNAVAILABLE) {
            LOG.warning("디스코드 조회 실패: " + e.getMessage());
        }
        String message = e.reason() == DiscordGatewayException.Reason.NOT_CONFIGURED
                ? "이 서버에서는 디스코드 전송 기능을 사용할 수 없습니다."
                : "디스코드와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        return LetterException.unavailable(message, e);
    }
}
