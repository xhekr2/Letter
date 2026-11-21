package letter.server.discord;

/** 디스코드가 알려 주는 username/global_name을 편지 프로그램에서 쓰는 이름 형식으로 바꾼다. */
final class DiscordNames {

    private DiscordNames() {
    }

    /** "표시 이름 (@핸들)". 표시 이름이 없으면 "@핸들", 핸들도 없으면 null. */
    static String display(String username, String globalName) {
        if (globalName != null && !globalName.isBlank()) {
            return username == null ? globalName : globalName + " (@" + username + ")";
        }
        return username == null ? null : "@" + username;
    }
}
