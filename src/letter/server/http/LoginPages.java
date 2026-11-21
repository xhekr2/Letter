package letter.server.http;

import letter.domain.DiscordUser;

/**
 * 로그인 중 브라우저에 보여 주는 안내 화면. 화면에 들어가는 값은 모두 HTML 이스케이프한다.
 * (디스코드 이름은 사용자가 정한 값이라 신뢰할 수 없다)
 */
final class LoginPages {

    private LoginPages() {
    }

    /** 디스코드 승인 뒤 돌아온 사용자에게: 이 계정으로 로그인하려면 프로그램에 표시된 확인 코드를 입력하세요. */
    static String confirm(DiscordUser user, String state) {
        return confirm(user, state, null);
    }

    /** @param error 직전에 입력한 코드가 틀렸을 때 보여 줄 안내. 없으면 null. */
    static String confirm(DiscordUser user, String state, String error) {
        return page("편지 로그인",
                (error == null ? "" : "<p class=\"error\">" + escape(error) + "</p>")
                        + "<p><strong>" + escape(user.label()) + "</strong> 디스코드 계정으로 로그인합니다.</p>"
                        + "<p>편지 프로그램 화면에 표시된 <strong>확인 코드</strong>를 입력해 주세요.</p>"
                        + "<form method=\"post\" action=\"confirm\" autocomplete=\"off\">"
                        + "<input type=\"hidden\" name=\"state\" value=\"" + escape(state) + "\">"
                        + "<input type=\"text\" name=\"code\" placeholder=\"ABC-DEF\" maxlength=\"16\" "
                        + "autocapitalize=\"characters\" spellcheck=\"false\" autofocus required>"
                        + "<button type=\"submit\">로그인</button>"
                        + "</form>"
                        + "<p class=\"note\">직접 시작한 로그인이 아니라면 코드를 입력하지 말고 이 창을 닫아 주세요.</p>");
    }

    static String success(DiscordUser user) {
        return page("로그인 완료",
                "<p><strong>" + escape(user.name()) + "</strong> 님, 로그인되었어요.</p>"
                        + "<p>이 창을 닫고 편지 프로그램으로 돌아가세요.</p>");
    }

    static String cancelled() {
        return page("로그인 취소", "<p>로그인을 취소했어요. 편지 프로그램에서 다시 시도할 수 있어요.</p>");
    }

    static String failure(String message) {
        return page("로그인 실패", "<p>" + escape(message) + "</p>");
    }

    private static String page(String title, String body) {
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + escape(title) + "</title>"
                + "<style>"
                + "body{font-family:system-ui,sans-serif;margin:0;background:#f4f4f6;color:#222}"
                + "main{max-width:26rem;margin:12vh auto;padding:2rem;background:#fff;border-radius:.75rem;"
                + "box-shadow:0 2px 12px rgba(0,0,0,.08)}"
                + "h1{font-size:1.25rem;margin-top:0}"
                + "input{font-size:1.1rem;padding:.5rem;width:9rem;letter-spacing:.1em;text-transform:uppercase}"
                + "button{font-size:1rem;padding:.5rem 1rem;margin-left:.5rem}"
                + ".note{font-size:.85rem;color:#666}"
                + ".error{color:#b00020;font-weight:600}"
                + "</style></head><body><main><h1>💌 " + escape(title) + "</h1>" + body + "</main></body></html>";
    }

    static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
