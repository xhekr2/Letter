package letter.client;

import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LetterException;

import java.util.Objects;

/**
 * 화면(ConsoleView)과 서버(LetterApi)를 이어 주는 프로그램의 흐름 담당.
 * 두 부품은 생성자로 주입받으므로, 테스트에서는 가짜 구현을 넣을 수 있다.
 */
public class LetterApp {
    private final LetterApi api;
    private final ConsoleView view;

    public LetterApp(LetterApi api, ConsoleView view) {
        this.api = Objects.requireNonNull(api);
        this.view = Objects.requireNonNull(view);
    }

    public void run() {
        try {
            while (true) {
                view.printMenu();
                String choice = view.prompt("선택> ").trim();
                try {
                    switch (choice) {
                        case "1" -> write();
                        case "2" -> read();
                        case "3" -> {
                            view.println("프로그램을 종료합니다.");
                            return;
                        }
                        default -> view.println("잘못된 입력입니다.");
                    }
                } catch (ConsoleView.InputClosedException e) {
                    throw e;
                } catch (RuntimeException e) {
                    view.println("예상치 못한 오류가 발생했습니다: " + e);
                }
            }
        } catch (ConsoleView.InputClosedException e) {
            view.println("\n입력이 종료되어 프로그램을 마칩니다.");
        }
    }

    private void write() {
        String author = askAuthor();

        view.println("편지 내용을 입력하세요. (끝내려면 새 줄에 /end 만 입력)");
        StringBuilder content = new StringBuilder();
        while (true) {
            String line = view.readLine();
            if (line.equals("/end")) break;
            if (content.length() > 0) content.append('\n');
            content.append(line);
        }

        LetterDraft draft;
        try {
            draft = new LetterDraft(author, content.toString());
        } catch (LetterException e) {
            view.println(e.getMessage() + " 취소했습니다.");
            return;
        }

        if (!view.confirm("발송할까요?")) {
            view.println("발송을 취소했습니다.");
            return;
        }

        // 네트워크/서버 문제면 작성한 내용을 잃지 않도록 같은 내용으로 다시 시도할 수 있게 한다
        while (true) {
            try {
                LetterCode code = api.send(draft);
                view.println("\n편지를 발송했습니다!");
                view.println("고유 코드: " + code + "  (편지를 확인할 때 필요하니 꼭 기억해 두세요)");
                return;
            } catch (ApiException e) {
                view.println("발송 실패: " + e.getMessage());
                if (!e.isRetryable()) return;
                if (!view.confirm("같은 내용으로 다시 시도할까요?")) {
                    view.println("편지가 발송되지 않았습니다.");
                    return;
                }
            }
        }
    }

    private String askAuthor() {
        while (true) {
            String raw = view.prompt("작성자 이름 (비우면 익명)> ");
            try {
                LetterDraft.normalizeAuthor(raw);   // 규칙은 도메인이 판단한다
                return raw;
            } catch (LetterException e) {
                view.println(e.getMessage());
            }
        }
    }

    private void read() {
        String raw = view.prompt("고유 코드> ");
        if (raw.isBlank()) {
            view.println("코드를 입력해 주세요.");
            return;
        }
        try {
            Letter letter = api.read(LetterCode.of(raw));
            view.showLetter(letter);
        } catch (LetterException e) {
            view.println(e.getMessage());
        } catch (ApiException e) {
            view.println("조회 실패: " + e.getMessage());
        }
    }
}
