package letter.server.service;

import letter.domain.LetterCode;

import java.util.Objects;
import java.util.Random;

/** 난수로 코드를 만든다. 운영에서는 SecureRandom, 테스트에서는 고정된 Random을 넣을 수 있다. */
public class RandomCodeGenerator implements CodeGenerator {
    private final Random random;

    public RandomCodeGenerator(Random random) {
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public LetterCode next() {
        StringBuilder sb = new StringBuilder(LetterCode.LENGTH);
        for (int i = 0; i < LetterCode.LENGTH; i++) {
            sb.append(LetterCode.ALPHABET.charAt(random.nextInt(LetterCode.ALPHABET.length())));
        }
        return new LetterCode(sb.toString());
    }
}
