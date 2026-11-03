package letter.server.service;

import letter.domain.LetterCode;

/** 봇이 보내는 DM 문구. 편지 내용은 절대 담지 않고 고유 코드만 전달한다. */
public final class DiscordMessages {

    private DiscordMessages() {
    }

    public static String letterArrived(String author, LetterCode code) {
        return "💌 편지가 도착했어요!\n"
                + "보낸 사람: " + codeSpan(author) + "\n"
                + "고유 코드: " + codeSpan(code.value()) + "\n"
                + "\n"
                + "편지 프로그램에서 '편지 확인'을 고르고 위 코드를 입력하면 읽을 수 있어요.\n"
                + "※ 보낸 사람 이름은 작성자가 직접 정한 이름이라 실제 보낸 사람과 다를 수 있어요."
                + "\n \n"
                + "편지 프로그램이 없다면?" + "\n"
                + "https://github.com/xhekr2/Letter";
    }

    /**
     * 사용자가 입력한 문자열을 디스코드 서식(링크, 멘션, 굵게 등)이 적용되지 않는 코드 조각(`...`)으로 감싼다.
     * 이름에 URL을 넣어 봇의 메시지로 링크를 퍼뜨리는 일을 막기 위해서다.
     */
    static String codeSpan(String text) {
        return "`" + text.replace('`', '\'') + "`";
    }
}
