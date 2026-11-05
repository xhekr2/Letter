package letter.client;

import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.OpenedLetter;
import letter.domain.Receipt;

/** 편지 서버와 주고받는 기능의 추상화. 화면(LetterApp)은 통신 방식(HTTP 등)을 모른다. */
public interface LetterApi {

    LetterCode send(LetterDraft draft) throws ApiException;

    /** 편지를 읽는다. 답장 가능 여부는 {@link #open}으로 확인한다. */
    default Letter read(LetterCode code) throws ApiException {
        return open(code).letter();
    }

    /** 편지를 읽고, 이 편지에 답장할 수 있는지도 함께 알려 준다. */
    OpenedLetter open(LetterCode code) throws ApiException;

    /** 보내기 전에 "이 사람이 맞나요?"를 확인하기 위해 디스코드 사용자의 이름을 조회한다. */
    DiscordUser findDiscordUser(DiscordId id) throws ApiException;

    /** 편지를 발송하고, 디스코드 봇이 받는 사람에게 편지 코드를 전달하게 한다. */
    default Receipt sendToDiscord(LetterDraft draft, DiscordId to) throws ApiException {
        return sendToDiscord(draft, to, null);
    }

    /** 위와 같고, replyTo가 있으면 그 사람이 이 편지의 답장을 받게 한다. (null이면 답장을 받지 않음) */
    Receipt sendToDiscord(LetterDraft draft, DiscordId to, DiscordId replyTo) throws ApiException;

    /** 답장을 보낸다. 답장 코드는 서버가 원본 편지에 저장된 디스코드 ID로 전달한다. */
    Receipt reply(LetterCode original, String content) throws ApiException;
}
