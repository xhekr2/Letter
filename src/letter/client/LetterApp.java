package letter.client;

import letter.domain.Delivery;
import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LetterException;
import letter.domain.Receipt;

import java.util.Objects;
import java.util.Optional;

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

    /** 서버로 보내는 한 번의 동작. 실패하면 ApiException. */
    private interface Sending<T> {
        T run() throws ApiException;
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
                        case "3" -> writeToDiscord();
                        case "4" -> {
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

    /** 편지 쓰기: 작성 → 발송 확인 → 발송 */
    private void write() {
        LetterDraft draft = compose();
        if (draft == null) return;

        if (!view.confirm("발송할까요?")) {
            view.println("발송을 취소했습니다.");
            return;
        }

        sendWithRetry(() -> api.send(draft)).ifPresent(code -> {
            view.println("\n편지를 발송했습니다!");
            view.println("고유 코드: " + code + "  (편지를 확인할 때 필요하니 꼭 기억해 두세요)");
        });
    }

    /**
     * 디스코드로 편지 전송하기: 작성(편지 쓰기와 동일) → 발송 확인 → 받는 사람의 디스코드 ID 입력
     * → 그 사람이 맞는지 최종 확인 → 발송. 디스코드 봇이 받는 사람에게 편지 코드만 전달한다.
     */
    private void writeToDiscord() {
        LetterDraft draft = compose();
        if (draft == null) return;

        if (!view.confirm("발송할까요?")) {
            view.println("발송을 취소했습니다.");
            return;
        }

        DiscordUser recipient = askRecipient();
        if (recipient == null) {
            view.println("발송을 취소했습니다.");
            return;
        }

        sendWithRetry(() -> api.sendToDiscord(draft, recipient.id()))
                .ifPresent(receipt -> reportDelivery(recipient, receipt));
    }

    /** 작성자와 내용을 입력받아 편지 초안을 만든다. 규칙에 맞지 않으면 안내하고 null. */
    private LetterDraft compose() {
        String author = askAuthor();

        view.println("편지 내용을 입력하세요. (끝내려면 새 줄에 /end 만 입력)");
        StringBuilder content = new StringBuilder();
        while (true) {
            String line = view.readLine();
            if (line.equals("/end")) break;
            if (content.length() > 0) content.append('\n');
            content.append(line);
        }

        try {
            return new LetterDraft(author, content.toString());
        } catch (LetterException e) {
            view.println(e.getMessage() + " 취소했습니다.");
            return null;
        }
    }

    /**
     * 받는 사람의 디스코드 ID를 입력받고, 서버에서 조회한 이름을 보여 주며 맞는지 확인받는다.
     * 비워 두면 취소(null).
     */
    private DiscordUser askRecipient() {
        view.println("\n받는 사람의 디스코드 ID(숫자)를 입력하세요. (취소하려면 비워 두세요)");
        view.println("  ID 확인 방법: 디스코드 설정 → 고급 → 개발자 모드 켜기 → 사용자 우클릭 → '사용자 ID 복사'");
        while (true) {
            String raw = view.prompt("디스코드 ID> ");
            if (raw.isBlank()) return null;

            DiscordUser user;
            try {
                user = api.findDiscordUser(DiscordId.of(raw));
            } catch (LetterException e) {
                view.println(e.getMessage());           // ID 형식 오류 (도메인이 판단)
                continue;
            } catch (ApiException e) {
                view.println("조회 실패: " + e.getMessage());
                continue;
            }

            view.println("\n받는 사람: " + ConsoleView.sanitize(user.name()) + "  (ID " + user.id() + ")");
            if (view.confirm("이 사람에게 보내는 게 맞나요?")) return user;
            view.println("다시 입력해 주세요.");
        }
    }

    /** 네트워크/서버 문제면 작성한 내용을 잃지 않도록 같은 내용으로 다시 시도할 수 있게 한다. */
    private <T> Optional<T> sendWithRetry(Sending<T> sending) {
        while (true) {
            try {
                return Optional.of(sending.run());
            } catch (ApiException e) {
                view.println("발송 실패: " + e.getMessage());
                if (!e.isRetryable()) return Optional.empty();
                if (!view.confirm("같은 내용으로 다시 시도할까요?")) {
                    view.println("편지가 발송되지 않았습니다.");
                    return Optional.empty();
                }
            }
        }
    }

    private void reportDelivery(DiscordUser recipient, Receipt receipt) {
        view.println("\n편지를 발송했습니다!");
        if (receipt.delivery() == Delivery.SENT) {
            view.println(ConsoleView.sanitize(recipient.name()) + " 님에게 디스코드로 고유 코드를 전달했어요.");
        } else {
            view.println("다만 디스코드로 코드를 전달하지 못했어요.");
            view.println("(상대가 편지 봇과 같은 서버에 있는지, 서버 멤버의 DM 수신을 허용했는지 확인해 주세요)");
            view.println("아래 고유 코드를 상대에게 직접 전달해 주세요.");
        }
        view.println("고유 코드: " + receipt.code());
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
