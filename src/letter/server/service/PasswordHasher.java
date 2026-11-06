package letter.server.service;

import letter.domain.Password;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 비밀번호를 소금(salt)을 섞어 PBKDF2로 해시한다. (JDK 기본 기능만 사용)
 * 저장 형식: {@code pbkdf2$반복횟수$소금$해시} — 서버 파일에는 이 문자열만 남고 비밀번호 원문은 남지 않는다.
 */
public final class PasswordHasher {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 100_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(Password password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return PREFIX + "$" + ITERATIONS + "$" + encode(salt) + "$" + encode(derive(password, salt, ITERATIONS));
    }

    /** 저장된 해시와 맞는지 검사한다. 저장 형식이 이상하면 false. */
    public static boolean matches(Password password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals(PREFIX)) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));   // 시간 차이로 알아내지 못하게 비교
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(Password password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.value().toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("비밀번호 해시를 계산할 수 없습니다.", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
