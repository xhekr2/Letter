package letter.client;

/**
 * 클라이언트 시작점(조립 담당).
 * 서버 주소는 실행 인자 > 환경변수 LETTER_SERVER_URL > 기본값 순으로 사용한다.
 */
public class LetterClient {
    private static final String DEFAULT_URL = "http://service.gsmsv.site:26129";

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : System.getenv("LETTER_SERVER_URL");
        if (url == null || url.isBlank()) url = DEFAULT_URL;

        HttpLetterApi api;
        try {
            api = new HttpLetterApi(url);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage() + "  예: http://localhost:8080 또는 https://example.com");
            System.exit(1);
            return;
        }
        new LetterApp(api, ConsoleView.system()).run();
    }
}
