package letter.client;

import letter.domain.Delivery;
import letter.domain.DiscordId;
import letter.domain.DiscordUser;
import letter.domain.Letter;
import letter.domain.LetterCode;
import letter.domain.LetterDraft;
import letter.domain.LetterException;
import letter.domain.LoginResult;
import letter.domain.LoginTicket;
import letter.domain.Password;
import letter.domain.OpenedLetter;
import letter.domain.Receipt;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 화면(ConsoleView)과 서버(LetterApi)를 이어 주는 프로그램의 흐름 담당.
 * 두 부품은 생성자로 주입받으므로, 테스트에서는 가짜 구현을 넣을 수 있다.
 * 프로그램을 시작할 때마다 로그인해야 메뉴가 열린다. 내 디스코드 ID를 입력하면 봇이 DM으로 인증 코드를 보내 주고,
 * 그 코드를 이 프로그램에 입력하면 로그인된다. 로그인 정보는 저장하지 않는다.
 */
public class LetterApp {
    private final LetterApi api;
    private final ConsoleView view;
    private DiscordUser me;     // 로그인한 사용자. 로그인 전에는 null

    public LetterApp(LetterApi api, ConsoleView view) {
        this.api = Objects.requireNonNull(api);
        this.view = Objects.requireNonNull(view);
    }

    /** 서버로 보내는 한 번의 동작. 실패하면 ApiException. */
    private interface Sending<T> {
        T run() throws ApiException;
    }

    /** 로그인하지 못했거나 사용자가 그만두어 더 진행할 수 없을 때. 프로그램을 마친다. */
    @SuppressWarnings("serial")
    private static class LoginFailedException extends RuntimeException {
    }

    public void run() {
        try {
            if (!login()) {
                view.println("로그인하지 못해 프로그램을 종료합니다.");
                return;
            }
            menuLoop();
        } catch (LoginFailedException e) {
            view.println("로그인하지 못해 프로그램을 종료합니다.");
        } catch (ConsoleView.InputClosedException e) {
            view.println("\n입력이 종료되어 프로그램을 마칩니다.");
        } finally {
            api.logout();   // 서버의 로그인 정보도 지운다
        }
    }

    private void menuLoop() {
        while (true) {
            view.printMenu();
            String choice = view.prompt("선택> ").trim();
            try {
                switch (choice) {
                    case "1" -> write();
                    case "2" -> read();
                    case "3" -> writeToDiscord();
                    case "4" -> showInbox();
                    case "5" -> {
                        view.println("프로그램을 종료합니다.");
                        return;
                    }
                    default -> view.println("잘못된 입력입니다.");
                }
            } catch (ConsoleView.InputClosedException | LoginFailedException e) {
                throw e;
            } catch (RuntimeException e) {
                view.println("예상치 못한 오류가 발생했습니다: " + e);
            }
        }
    }

    // ---------------------------------------------------------------- 로그인

    /**
     * 디스코드 로그인: 내 디스코드 ID를 입력하면 봇이 DM으로 인증 코드를 보낸다. 그 코드를 입력하면 로그인된다.
     * 실패하거나 시간이 지나면 다시 할지 묻는다.
     *
     * @return 로그인했으면 true, 사용자가 그만두었으면 false
     */
    private boolean login() {
        view.println("\n===== 디스코드 로그인 =====");
        view.println("편지를 쓰고 받으려면 로그인해야 해요. 내 디스코드 ID를 입력하면 편지 봇이 DM으로 인증 코드를 보내 줘요.");
        view.println("(프로그램을 시작할 때마다 로그인합니다. 편지 봇이 있는 디스코드 서버에 함께 있어야 해요.)");
        while (true) {
            DiscordId id = askMyId();
            if (id == null) return false;

            LoginTicket ticket;
            try {
                ticket = api.startLogin(id);
            } catch (ApiException e) {
                view.println("인증 코드를 보내지 못했어요: " + e.getMessage());
                if (!view.confirm("다시 시도할까요?")) return false;
                continue;
            }

            view.println("\n디스코드 DM으로 인증 코드를 보냈어요. DM에서 숫자 6자리를 확인해 아래에 입력해 주세요.");
            view.println("(" + (ticket.expiresInSeconds() / 60) + "분 안에 입력해 주세요. 코드를 못 받았다면 비워 두고 Enter를 누르세요.)");
            LoginResult result = askCode(ticket);
            if (result != null) {
                me = result.user();
                view.println("\n" + ConsoleView.sanitize(me.name()) + " 님으로 로그인했어요.");
                return true;
            }
            if (!view.confirm("다시 로그인할까요?")) return false;
        }
    }

    /**
     * 로그인할 내 디스코드 ID를 입력받는다. 이미 로그인한 적이 있으면(로그인이 만료된 경우) 같은 계정으로 다시 로그인한다.
     * 비워 두면 그만둔다(null).
     */
    private DiscordId askMyId() {
        if (me != null) {
            view.println("\n" + ConsoleView.sanitize(me.name()) + " 님 계정으로 다시 로그인해요.");
            return me.id();
        }
        view.println("\n  ID 확인 방법: 디스코드 설정 → 고급 → 개발자 모드 켜기 → 내 프로필 우클릭 → '사용자 ID 복사'");
        while (true) {
            String raw = view.prompt("내 디스코드 ID(숫자, 그만두려면 비워 두세요)> ");
            if (raw.isBlank()) return null;
            try {
                return DiscordId.of(raw);   // 규칙은 도메인이 판단한다
            } catch (LetterException e) {
                view.println(e.getMessage());
            }
        }
    }

    /**
     * DM으로 받은 인증 코드를 입력받아 확인한다. 틀리면 서버가 허용하는 횟수 안에서 다시 입력받는다.
     * 비워 두거나 만료·오류로 끝나면 이유를 알리고 null.
     */
    private LoginResult askCode(LoginTicket ticket) {
        while (true) {
            String code = view.prompt("인증 코드> ").trim();
            if (code.isEmpty()) {
                view.println("인증을 취소했어요.");
                return null;
            }
            try {
                return api.verifyLogin(ticket, code);
            } catch (CodeWrongException e) {
                view.println(e.getMessage());                 // 틀렸어요. (남은 횟수 N번)
            } catch (ApiException e) {
                view.println("로그인에 실패했어요: " + e.getMessage());
                if (!e.isRetryable()) return null;           // 만료되었거나 취소됨: 같은 코드로는 소용없다
            }
        }
    }

    /**
     * 서버로 보내는 동작을 실행한다. 로그인이 만료되어 거절되면 다시 로그인한 뒤 같은 동작을 한 번 더 한다.
     * (작성하던 편지 등은 그대로 유지된다) 다시 로그인하지 않으면 프로그램을 마친다.
     */
    private <T> T authed(Sending<T> sending) throws ApiException {
        try {
            return sending.run();
        } catch (LoginRequiredException e) {
            view.println("\n" + e.getMessage());
            if (!login()) throw new LoginFailedException();
            return sending.run();
        }
    }

    // ---------------------------------------------------------------- 받은 편지 목록

    /** 내 디스코드로 전달된 편지의 코드를 최신순으로 한 줄씩 보여 준다. */
    private void showInbox() {
        List<LetterCode> codes;
        try {
            codes = authed(api::inbox);
        } catch (ApiException e) {
            view.println("목록 조회 실패: " + e.getMessage());
            return;
        }
        view.showInbox(codes);
    }

    /** 편지 쓰기: 작성 → 발송 확인 → 발송 */
    private void write() {
        LetterDraft draft = compose();
        if (draft == null) return;

        if (!view.confirm("발송할까요?")) {
            view.println("발송을 취소했습니다.");
            return;
        }

        sendWithRetry(() -> authed(() -> api.send(draft))).ifPresent(code -> {
            view.println("\n편지를 발송했습니다!");
            view.println("고유 코드: " + code + "  (편지를 확인할 때 필요하니 꼭 기억해 두세요)");
            if (draft.password() != null) {
                view.println("비밀번호가 걸린 편지예요. 읽을 사람에게 고유 코드와 비밀번호를 함께 알려 주세요.");
            }
        });
    }

    /**
     * 디스코드로 편지 전송하기: 작성(편지 쓰기와 동일) → 발송 확인 → 받는 사람의 디스코드 ID 입력
     * → 그 사람이 맞는지 확인 → 발송. 답장은 로그인한 내 계정으로 받는다. 디스코드 봇이 받는 사람에게 편지 코드만 전달한다.
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

        // 답장은 로그인한 내 계정으로 받는다. 따로 묻지 않는다.
        DiscordUser replyTo = me;
        sendWithRetry(() -> authed(() -> api.sendToDiscord(draft, recipient.id(), replyTo.id())))
                .ifPresent(receipt -> reportDelivery(recipient, replyTo, draft.password() != null, receipt));
    }

    /**
     * 내용을 입력받아 편지 초안을 만든다. 규칙에 맞지 않으면 안내하고 null.
     * 보낸 사람은 입력받지 않는다. 로그인한 디스코드 계정이 자동으로 보낸 사람이 된다.
     */
    private LetterDraft compose() {
        view.println("\n보내는 사람: " + ConsoleView.sanitize(me.name()) + "  (로그인한 디스코드 계정으로 표시됩니다)");
        view.println("편지 내용을 입력하세요. (끝내려면 새 줄에 /end 만 입력)");
        String content = readContent();

        try {
            new LetterDraft(content);   // 내용이 비어 있으면 비밀번호를 묻기 전에 알린다
        } catch (LetterException e) {
            view.println(e.getMessage() + " 취소했습니다.");
            return null;
        }

        return new LetterDraft(content, askPassword());
    }

    /** 편지에 걸 비밀번호를 입력받는다. 비우면 비밀번호 없음(null). 규칙에 맞지 않으면 다시 입력받는다. */
    private Password askPassword() {
        view.println("\n비밀번호를 걸면 읽을 때 입력해야 해요. (비우면 비밀번호 없음)");
        while (true) {
            String raw = view.prompt("비밀번호> ");
            try {
                return Password.ofOptional(raw);   // 규칙은 도메인이 판단한다
            } catch (LetterException e) {
                view.println(e.getMessage());
            }
        }
    }

    /** "/end"가 나올 때까지 여러 줄을 읽는다. */
    private String readContent() {
        StringBuilder content = new StringBuilder();
        while (true) {
            String line = view.readLine();
            if (line.equals("/end")) break;
            if (content.length() > 0) content.append('\n');
            content.append(line);
        }
        return content.toString();
    }

    /**
     * 받는 사람의 디스코드 ID를 입력받고, 서버에서 조회한 이름을 보여 주며 맞는지 확인받는다.
     * 비워 두면 취소(null).
     */
    private DiscordUser askRecipient() {
        view.println("\n받는 사람의 디스코드 ID(숫자)를 입력하세요. (취소하려면 비워 두세요)");
        view.println("  ID 확인 방법: 디스코드 설정 → 고급 → 개발자 모드 켜기 → 사용자 우클릭 → '사용자 ID 복사'");
        return askDiscordUser("디스코드 ID> ", "받는 사람", "이 사람에게 보내는 게 맞나요?");
    }

    /** 디스코드 ID 입력 → 서버 조회 → 이름 확인의 공통 절차. 비워 두면 null. */
    private DiscordUser askDiscordUser(String promptText, String roleLabel, String confirmQuestion) {
        while (true) {
            String raw = view.prompt(promptText);
            if (raw.isBlank()) return null;

            DiscordUser user;
            try {
                user = authed(() -> api.findDiscordUser(DiscordId.of(raw)));
            } catch (LetterException e) {
                view.println(e.getMessage());           // ID 형식 오류 (도메인이 판단)
                continue;
            } catch (ApiException e) {
                view.println("조회 실패: " + e.getMessage());
                continue;
            }

            view.println("\n" + roleLabel + ": " + ConsoleView.sanitize(user.name()) + "  (ID " + user.id() + ")");
            if (view.confirm(confirmQuestion)) return user;
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

    private void reportDelivery(DiscordUser recipient, DiscordUser replyTo, boolean hasPassword, Receipt receipt) {
        view.println("\n편지를 발송했습니다!");
        if (receipt.delivery() == Delivery.SENT) {
            view.println(ConsoleView.sanitize(recipient.shortLabel()) + " 님에게 디스코드로 고유 코드를 전달했어요.");
        } else {
            view.println("다만 디스코드로 코드를 전달하지 못했어요.");
            view.println("(상대가 편지 봇과 같은 서버에 있는지, 서버 멤버의 DM 수신을 허용했는지 확인해 주세요)");
            view.println(hasPassword ? "아래 고유 코드와 설정한 비밀번호를 상대에게 직접 전달해 주세요."
                    : "아래 고유 코드를 상대에게 직접 전달해 주세요.");
        }
        view.println("답장을 받을 사용자: " + ConsoleView.sanitize(replyTo.shortLabel()));
        view.println("고유 코드: " + receipt.code());
    }

    private static final int MAX_PASSWORD_TRIES = 3;

    private void read() {
        String raw = view.prompt("고유 코드> ");
        if (raw.isBlank()) {
            view.println("코드를 입력해 주세요.");
            return;
        }
        LetterCode code;
        try {
            code = LetterCode.of(raw);
        } catch (LetterException e) {
            view.println(e.getMessage());
            return;
        }

        String password = null;      // 비밀번호가 걸린 편지면 입력받아 두었다가 답장에도 쓴다
        OpenedLetter opened;
        for (int tries = 0; ; tries++) {
            String passwordToTry = password;      // 람다에서 쓰려면 바뀌지 않는 값이어야 한다
            try {
                opened = authed(() -> api.open(code, passwordToTry));
                break;
            } catch (PasswordException e) {
                view.println(e.isWrong() ? e.getMessage() : "비밀번호가 걸린 편지예요.");
                if (tries >= MAX_PASSWORD_TRIES) {
                    view.println("비밀번호를 여러 번 틀려 취소했습니다.");
                    return;
                }
                password = view.prompt("비밀번호 (취소하려면 비워 두세요)> ");
                if (password.isBlank()) {
                    view.println("취소했습니다.");
                    return;
                }
            } catch (ApiException e) {
                view.println("조회 실패: " + e.getMessage());
                return;
            }
        }

        view.showLetter(opened.letter());
        if (opened.replyable() && view.confirm("답장하시겠어요?")) {
            replyTo(code, password);
        }
    }

    /** 답장 쓰기: 작성자는 따로 적지 않는다. 작성 시각은 서버가 기록하고, 읽는 쪽 화면에 보인다. */
    private void replyTo(LetterCode original, String password) {
        view.println("답장 내용을 입력하세요. (끝내려면 새 줄에 /end 만 입력)");
        String content = readContent();
        if (content.isBlank()) {
            view.println("답장 내용이 비어 있어 취소했습니다.");
            return;
        }

        sendWithRetry(() -> authed(() -> api.reply(original, content, password))).ifPresent(receipt -> {
            if (receipt.delivery() == Delivery.SENT) {
                view.println("\n답장을 보냈습니다! 디스코드로 답장이 왔다고 알려 드렸어요.");
            } else {
                view.println("\n답장은 저장했지만 디스코드로 전달하지 못했어요.");
                view.println("아래 고유 코드를 원래 편지를 보낸 분께 직접 전달해 주세요.");
                view.println("고유 코드: " + receipt.code());
            }
        });
    }
}
