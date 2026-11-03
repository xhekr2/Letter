package letter.client;

import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.Receipt;

/** 편지 서버와 주고받는 기능의 추상화. 화면(LetterApp)은 통신 방식(HTTP 등)을 모른다. */
public interface LetterApi {

    LetterCode send(LetterDraft draft) throws ApiException;

    Letter read(LetterCode code) throws ApiException;

    /** 보내기 전에 "이 사람이 맞나요?"를 확인하기 위해 디스코드 사용자의 이름을 조회한다. */
    DiscordUser findDiscordUser(DiscordId id) throws ApiException;

    /** 편지를 발송하고, 디스코드 봇이 받는 사람에게 편지 코드를 전달하게 한다. */
    Receipt sendToDiscord(LetterDraft draft, DiscordId to) throws ApiException;
}
