package letter.client;

import letter.domain.Letter;
import letter.domain.LetterCode;

import java.io.Console;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;

/** 콘솔 입출력만 담당한다. 서버 통신이나 편지 규칙은 모른다. */
public class ConsoleView {

    /** 입력이 끝났을 때(Ctrl+D 등) 던진다. */
    @SuppressWarnings("serial")
    public static class InputClosedException extends RuntimeException {
    }

    private final Scanner in;
    private final PrintStream out;

    public ConsoleView(InputStream input, Charset charset, PrintStream out) {
        this.in = new Scanner(input, charset);
        this.out = out;
    }

    /** 실제 콘솔(System.in/out)에 연결된 화면. 윈도우 콘솔의 문자 인코딩에 맞춰 읽는다. */
    public static ConsoleView system() {
        Console console = System.console();
        Charset charset = console != null ? console.charset() : StandardCharsets.UTF_8;
        return new ConsoleView(System.in, charset, System.out);
    }

    public void println(String text) {
        out.println(text);
    }

    public String prompt(String text) {
        out.print(text);
        out.flush();
        return readLine();
    }

    public String readLine() {
        if (!in.hasNextLine()) throw new InputClosedException();
        return in.nextLine();
    }

    public boolean confirm(String question) {
        return prompt(question + " (y/n)> ").trim().equalsIgnoreCase("y");
    }

    public void printMenu() {
        println("\n===== 편지 =====");
        println("1. 편지 쓰기");
        println("2. 편지 확인");
        println("3. 디스코드로 편지 전송하기");
        println("4. 받은 편지 목록");
        println("5. 종료");
    }

    public void showLetter(Letter letter) {
        println("\n--------------------------------");
        println("from: " + sanitize(letter.fromLabel()));   // 로그인으로 확인된 보낸 사람이 있으면 {이름}(@핸들, ID)
        if (letter.to() != null) println("to: " + sanitize(letter.to().label()));   // 디스코드로 전달된 편지만
        println("작성 시각: " + letter.createdAt().toString().replace('T', ' '));
        println("--------------------------------");
        println(sanitize(letter.content()));
        println("--------------------------------");
    }

    /** 받은 편지의 코드를 (이미 최신순으로 정렬되어 온 그대로) 한 줄에 하나씩 보여 준다. 코드 외의 정보는 보여 주지 않는다. */
    public void showInbox(List<LetterCode> codes) {
        if (codes.isEmpty()) {
            println("\n받은 편지가 없습니다.");
            return;
        }
        println("\n받은 편지 " + codes.size() + "통 (최신순)");
        println("--------------------------------");
        for (LetterCode code : codes) println(code.value());
        println("--------------------------------");
        println("편지 내용은 메뉴의 '편지 확인'에서 코드를 입력해 읽을 수 있어요.");
    }

    /** 다른 사람이 쓴 편지에 터미널 제어 문자(이스케이프 시퀀스 등)가 있어도 화면이 조작되지 않게 제거한다. */
    static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\t') sb.append((char) cp);
            else if (cp != '\r' && !Character.isISOControl(cp)) sb.appendCodePoint(cp);
        });
        return sb.toString();
    }
}
