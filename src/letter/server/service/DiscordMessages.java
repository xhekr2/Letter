package letter.server.service;

import letter.domain.LetterCode;
import letter.domain.Password;

/** 봇이 보내는 DM 문구. 편지 내용은 절대 담지 않고 고유 코드만 전달한다. */
public final class DiscordMessages {

    /** 링크 미리보기가 붙지 않도록 주소를 <>로 감싼다. */
    private static final String DOWNLOAD_LINK = "[편지 프로그램 다운로드 하기](<https://github.com/xhekr2/Letter>)";

    private DiscordMessages() {
    }

    public static String letterArrived(String sender, LetterCode code) {
        return letterArrived(sender, code, null);
    }

    /**
     * 비밀번호가 있으면 `|| 비밀번호 ||`(가림 표시)로 함께 알린다. 눌러서 펼쳐 봐야 보인다.
     *
     * @param sender 보낸 사람 표기. 디스코드 로그인으로 확인된 계정이다. ({이름}({@핸들}))
     */
    public static String letterArrived(String sender, LetterCode code, Password password) {
        return "💌 편지가 도착했어요!\n"
                + "보낸 사람: " + codeSpan(sender) + "\n"
                + "고유 코드: " + codeSpan(code.value()) + "\n"
                + (password == null ? "" : "비밀번호: || " + codeSpan(password.value()) + " ||\n")
                + "\n"
                + DOWNLOAD_LINK;
    }

    /**
     * 답장 도착 안내. 누가 답장했는지와, 답장을 읽을 새 코드를 전달한다. 답장 내용은 담지 않는다.
     *
     * @param replier 답장한 사람 표기. 디스코드 로그인으로 확인된 계정이다. ({이름}({@핸들}))
     */
    public static String replyArrived(String replier, LetterCode replyCode) {
        return "💌" + codeSpan(replier) + "님이 답장했어요!\n"
                + "고유 코드: " + codeSpan(replyCode.value()) + "\n"
                + "\n"
                + DOWNLOAD_LINK;
    }

    /**
     * 로그인 인증 코드 안내. 프로그램에 이 코드를 입력하면 로그인된다.
     *
     * @param minutes 코드가 유효한 시간(분)
     */
    public static String loginCode(String code, int minutes) {
        return "🔐 편지 프로그램 로그인 인증 코드: " + codeSpan(code) + "\n"
                + "편지 프로그램에 이 코드를 입력하면 로그인돼요. (" + minutes + "분 동안만 유효해요)\n"
                + "\n"
                + "본인이 요청한 게 아니라면 코드를 알려 주지 말고 이 메시지를 무시해 주세요.";
    }

    /**
     * 사용자가 입력한 문자열을 디스코드 서식(링크, 멘션, 굵게 등)이 적용되지 않는 코드 조각(`...`)으로 감싼다.
     * 이름에 URL을 넣어 봇의 메시지로 링크를 퍼뜨리는 일을 막기 위해서다.
     */
    static String codeSpan(String text) {
        return "`" + text.replace('`', '\'') + "`";
    }
}
