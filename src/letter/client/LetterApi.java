package letter.client;

import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LoginResult;
import letter.domain.LoginTicket;
import letter.domain.OpenedLetter;
import letter.domain.Receipt;

import java.util.List;

/**
 * 편지 서버와 주고받는 기능의 추상화. 화면(LetterApp)은 통신 방식(HTTP 등)을 모른다.
 * 로그인({@link #startLogin}, {@link #verifyLogin})을 마치면 이후 모든 요청에 로그인 정보가 함께 실린다.
 */
public interface LetterApi {

    /**
     * 디스코드 로그인을 시작한다. 서버가 해당 디스코드 계정으로 인증 코드를 DM으로 보낸다.
     *
     * @param id 로그인하려는 내 디스코드 ID
     */
    LoginTicket startLogin(DiscordId id) throws ApiException;

    /**
     * DM으로 받은 인증 코드를 보낸다. 맞으면 이 API 객체가 로그인 상태가 되어 이후 요청이 모두 그 계정으로 간다.
     *
     * @return 로그인 결과(세션 토큰과 로그인한 디스코드 사용자)
     * @throws CodeWrongException 코드가 틀렸을 때 (남은 횟수 안에서는 다시 입력할 수 있다)
     * @throws ApiException       로그인 요청이 만료되었거나 취소되었으면 (다시 로그인해야 한다)
     */
    LoginResult verifyLogin(LoginTicket ticket, String code) throws ApiException;

    /** 서버의 로그인 세션을 끝내고 이 API 객체를 로그아웃 상태로 만든다. 서버에 닿지 않아도 이쪽은 로그아웃된다. */
    void logout();

    /** 내게 전달된 편지의 코드 목록. 보낸 날짜 기준 최신순이다. */
    List<LetterCode> inbox() throws ApiException;

    LetterCode send(LetterDraft draft) throws ApiException;

    /** 편지를 읽는다. 답장 가능 여부는 {@link #open}으로 확인한다. */
    default Letter read(LetterCode code) throws ApiException {
        return open(code).letter();
    }

    /**
     * 편지를 읽고, 이 편지에 답장할 수 있는지도 함께 알려 준다.
     *
     * @throws PasswordException 비밀번호가 걸린 편지에 비밀번호가 없거나 틀렸을 때
     */
    default OpenedLetter open(LetterCode code) throws ApiException {
        return open(code, null);
    }

    /** 비밀번호를 보내서 편지를 읽는다. (비밀번호가 없는 편지는 password를 null로 둔다) */
    OpenedLetter open(LetterCode code, String password) throws ApiException;

    /** 보내기 전에 "이 사람이 맞나요?"를 확인하기 위해 디스코드 사용자의 이름을 조회한다. */
    DiscordUser findDiscordUser(DiscordId id) throws ApiException;

    /** 편지를 발송하고, 디스코드 봇이 받는 사람에게 편지 코드를 전달하게 한다. */
    default Receipt sendToDiscord(LetterDraft draft, DiscordId to) throws ApiException {
        return sendToDiscord(draft, to, null);
    }

    /** 위와 같고, replyTo가 있으면 그 사람이 이 편지의 답장을 받게 한다. (null이면 답장을 받지 않음) */
    Receipt sendToDiscord(LetterDraft draft, DiscordId to, DiscordId replyTo) throws ApiException;

    /** 답장을 보낸다. 답장 코드는 서버가 원본 편지에 저장된 디스코드 ID로 전달한다. */
    default Receipt reply(LetterCode original, String content) throws ApiException {
        return reply(original, content, null);
    }

    /** 비밀번호가 걸린 편지에 답장할 때는 그 편지의 비밀번호도 함께 보낸다. */
    Receipt reply(LetterCode original, String content, String password) throws ApiException;
}
