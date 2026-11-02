package letter.server.service;

import letter.domain.LetterCode;

/** 고유 코드를 만들어 내는 방법의 추상화. */
public interface CodeGenerator {
    LetterCode next();
}
