package letter.client;

import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;

/** 편지 서버와 주고받는 기능의 추상화. 화면(LetterApp)은 통신 방식(HTTP 등)을 모른다. */
public interface LetterApi {

    LetterCode send(LetterDraft draft) throws ApiException;

    Letter read(LetterCode code) throws ApiException;
}
