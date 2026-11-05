# 편지 (Letter) — 원하면 익명으로 쓰는 편지

CLI로 편지를 쓰고, 발급된 고유 코드로 편지를 확인하는 자바 프로그램입니다.
편지를 쓴 뒤 **디스코드 봇이 받는 사람에게 고유 코드를 DM으로 대신 전달**하게 할 수도 있습니다.
DM에는 편지 내용이 담기지 않고 코드만 갑니다. 편지는 이 프로그램으로 코드를 입력해야 읽을 수 있습니다.

※디스코드로 편지를 보낼 때 조회할 수 있는 사용자 ID는 Letter 봇과 같은 서버에 있는 사용자입니다. 상대가 다이렉트 메시지 권한을 off로 설정했을 경우 프로그램에서는 성공하지만 직접적으로 메시지 전송이 되지 않습니다.

서버(HTTP)와 클라이언트(CLI)로 나뉘며, 외부 라이브러리 없이 JDK만 사용합니다. (JDK 17 이상)

## 사용 방법

### 1. 준비
JDK 또는 JRE **17 이상**이 필요합니다. 터미널에서 `java -version`으로 확인하세요.

### 2. 클라이언트 받기
[Releases](https://github.com/xhekr2/Letter/releases)에서 최신 `letter-client.jar`를 내려받습니다.
(디스코드 전송 메뉴는 V1.1 부터 있습니다. 이전 버전의 jar도 편지 쓰기/확인은 그대로 동작합니다.)

### 3. 실행
터미널(PowerShell, 맥/리눅스 터미널 등)에서 JAR가 있는 폴더로 이동해 실행합니다.

```
java -jar letter-client.jar
```

더블클릭으로는 실행되지 않습니다. (콘솔 프로그램이라 창이 바로 닫힙니다). V1.2 릴리즈를 참고하세요.

### 4. 메뉴

```
===== 편지 =====
1. 편지 쓰기
2. 편지 확인
3. 디스코드로 편지 전송하기
4. 종료
```

- `1. 편지 쓰기`: 이름(비우면 익명)과 내용을 쓰고, 새 줄에 `/end`만 입력하면 끝납니다. 발송하면 **고유 코드**가 나옵니다.
- `2. 편지 확인`: 받은 고유 코드를 입력하면 편지를 볼 수 있습니다. 코드를 잃어버리면 편지를 다시 볼 수 없으니 꼭 기억해 두세요. 답장을 받는 편지라면 읽은 뒤 `답장하시겠어요?`가 나옵니다.
- `3. 디스코드로 편지 전송하기`: 편지를 쓰고, 디스코드 봇이 받는 사람에게 고유 코드를 DM으로 보내 줍니다. (아래 참고)

### 5. 디스코드로 편지 보내기

1. 이름과 내용을 입력합니다. (`1. 편지 쓰기`와 같습니다)
2. `발송할까요? (y/n)`에서 `y`를 누릅니다.
3. 받는 사람의 **디스코드 ID**(숫자 17~20자리)를 입력합니다. 비워 두면 취소됩니다.
4. 서버가 디스코드에서 그 사용자의 이름을 조회해 `받는 사람: ○○ (@아이디)`를 보여 줍니다.
   `이 사람에게 보내는 게 맞나요? (y/n)`에서 `n`을 누르면 ID를 다시 입력합니다. 이 단계에서는 편지가 저장되지 않습니다.
5. `답장 수신 여부` 입력란이 나옵니다. **답장을 받을 디스코드 ID**를 입력하면 같은 방식으로 이름을 보여 주고
   `이 사용자가 맞나요? (y/n)`로 확인합니다. 답장을 받지 않으려면 비워 두세요.
   (받는 사람의 ID와 달라도 됩니다. 답장이 꼭 오는 것은 아니에요.)
6. 편지가 저장되고, 봇이 받는 사람에게 고유 코드를 DM으로 보냅니다.
   받는 사람은 이 프로그램의 `2. 편지 확인`에 그 코드를 입력해 편지를 읽습니다.

### 6. 답장하기

답장은 **디스코드로 보내면서 `답장 수신 여부`에 ID를 적은 편지**에만 할 수 있습니다.

1. 받는 사람이 `2. 편지 확인`으로 편지를 읽으면, 내용 아래에 `답장하시겠어요? (y/n)`가 나옵니다.
2. `n`이면 바로 메뉴로 돌아갑니다. `y`면 답장 내용을 쓰고 새 줄에 `/end`를 입력합니다. (작성자 이름은 묻지 않습니다)
3. 답장은 **새 편지(새 고유 코드)**로 저장되고, 봇이 원래 편지에 저장해 둔 `답장 수신` ID로 DM을 보냅니다.
   `` `원래코드` 편지에 대한 답장이 도착했어요! `` 와 새 고유 코드가 들어 있습니다.
4. 답장을 확인하면 `보낸 사람: 원래코드에 대한 답장입니다.`와 작성 시각이 보입니다.

- 답장하는 사람은 답장이 누구에게 가는지 알 수 없고, 답장에는 다시 답장할 수 없습니다.
- 한 편지에 여러 번 답장할 수 있습니다. (다만 같은 사람에게는 10분에 3번까지입니다.)
- 답장 DM 전달에 실패하면 새 고유 코드가 화면에 나오니 원래 편지를 보낸 분께 직접 전달해 주세요.
- 일반 편지의 작성자 이름으로 `코드에 대한 답장입니다.` 형식은 쓸 수 없습니다. (답장인 척하는 것을 막기 위해서입니다)

**디스코드 ID 확인 방법**: 디스코드 설정 → 고급 → 개발자 모드 켜기 → 사용자 우클릭 → `사용자 ID 복사`

**받는 사람 조건**
- 받는 사람이 **편지 봇과 같은 디스코드 서버**에 있어야 합니다.
- 받는 사람이 서버 멤버의 DM 수신을 허용해야 합니다.
- 같은 사람에게는 **10분에 3번까지** 보낼 수 있습니다. (스팸 방지)

**전달에 실패하면**: 편지는 이미 저장된 상태입니다. 화면에 나온 고유 코드를 받는 사람에게 직접 전달해 주세요.

**보낸 사람 이름에 대해**: DM에는 보낸 사람 이름이 들어가지만, 작성자가 직접 정한 이름이라 인증된 정보가 아닙니다. DM에도 그 안내가 함께 들어갑니다.

### 다른 서버를 쓰고 싶다면
기본 서버 주소는 코드(`LetterClient.DEFAULT_URL`)에 들어 있습니다. 직접 서버를 띄운 경우 주소를 인자로 줍니다.

```
java -jar letter-client.jar http://내서버주소:포트
```

환경변수 `LETTER_SERVER_URL`에 주소를 넣어 두어도 됩니다. (우선순위: 인자 > 환경변수 > 기본값)

> 기본 서버는 학교 VM에서 운영 중이라 사정에 따라 접속이 안 될 수 있습니다.
> 디스코드 전송은 서버에 봇 토큰이 설정되어 있어야 동작합니다. 설정이 없는 서버에서는 메뉴 3이 "사용할 수 없습니다"라고 안내하고, 1·2번은 정상 동작합니다.

## 프로젝트 구조

```
src/letter/
├── domain/            편지의 개념과 규칙 (HTTP, 파일, 디스코드를 전혀 모름)
│   ├── LetterDraft      발송 전 편지: 내용 필수, 이름 50자 제한, 익명 처리
│   ├── Letter           저장된 편지: 작성자, 내용, 작성 시각
│   ├── LetterCode       고유 코드: 형식이 검증된 값 객체
│   ├── DiscordId        디스코드 사용자 ID: 형식이 검증된 값 객체
│   ├── DiscordUser      받는 사람 확인용 (ID + 이름)
│   ├── Delivery         디스코드 전달 결과 (SENT / FAILED)
│   ├── Receipt          발송 결과 (고유 코드 + 전달 결과)
│   └── LetterException  업무 오류 (종류: Kind)
├── protocol/          서버와 클라이언트가 함께 쓰는 통신 형식
│   ├── FormCodec        key=value&... 변환
│   ├── LetterForm       Letter <-> 전송/저장 문자열 (저장에는 답장받을 ID 포함, 전송에는 제외)
│   └── DiscordForm      DiscordUser, Receipt <-> 전송 문자열
├── server/
│   ├── LetterServer     시작점이자 조립 담당
│   ├── ServerConfig     환경변수 -> 불변 설정 객체
│   ├── service/         업무 규칙
│   │   ├── LetterService          발송/조회 (저장소, 코드 생성기, 시계를 주입받음)
│   │   ├── LetterDeliveryService  편지 발송 + 디스코드로 코드 전달
│   │   ├── DiscordMessages        봇이 보내는 DM 문구
│   │   ├── CodeGenerator          (인터페이스) 코드 만드는 방법
│   │   └── RandomCodeGenerator
│   ├── storage/         저장소
│   │   ├── LetterRepository       (인터페이스)
│   │   ├── FileLetterRepository      파일로 저장
│   │   └── InMemoryLetterRepository  메모리에 저장 (테스트용)
│   ├── discord/         디스코드 연동
│   │   ├── DiscordGateway           (인터페이스) 사용자 조회, DM 보내기
│   │   ├── DiscordRestGateway       디스코드 REST API로 구현
│   │   ├── DisabledDiscordGateway   봇 토큰이 없을 때 쓰는 구현
│   │   ├── DiscordGatewayException  통신 오류 (원인: Reason)
│   │   └── MiniJson                 필요한 만큼만 만든 JSON 도구
│   └── http/            HTTP 계층
│       ├── LetterHttpHandler       요청 -> 서비스 호출
│       ├── DiscordUserHttpHandler  디스코드 사용자 이름 조회
│       ├── RateLimitedHandler      (데코레이터) 요청 횟수 제한을 덧붙임
│       ├── RateLimiter
│       └── HttpResponses
└── client/
    ├── LetterClient     시작점이자 조립 담당
    ├── LetterApp        프로그램 흐름 (메뉴, 작성, 확인, 디스코드 전송)
    ├── ConsoleView      콘솔 입출력만 담당
    ├── LetterApi        (인터페이스) 서버와 주고받는 기능
    ├── HttpLetterApi    HTTP로 구현
    └── ApiException
test/letter/LetterTests.java   단위 테스트 + 서버·클라이언트 통합 테스트 (44개)
deploy/letter-server.service   systemd 서비스 파일
```

## 객체지향 설계 포인트

| 원칙/개념 | 적용한 곳 |
|---|---|
| **캡슐화** | 편지 규칙(내용 필수, 이름 제한, 제어문자 제거)은 `LetterDraft` 한 곳에만 있다. 다른 클래스는 규칙을 다시 검사하지 않는다. |
| **불변 객체 / 값 객체** | `Letter`, `LetterDraft`, `LetterCode`, `DiscordId`, `ServerConfig`는 record. 만들어진 뒤에는 바뀌지 않고, 만들 때 항상 검증된다. 잘못된 상태의 객체가 존재할 수 없다. (`DiscordId`는 숫자만 허용하므로 URL이나 JSON에 넣어도 안전하다.) |
| **단일 책임 원칙(SRP)** | 업무 규칙(`LetterService`), 저장(`FileLetterRepository`), HTTP 변환(`LetterHttpHandler`), 횟수 제한(`RateLimiter`), 설정(`ServerConfig`), 디스코드 통신(`DiscordRestGateway`)을 각각 다른 클래스가 맡는다. 클라이언트도 화면(`ConsoleView`) / 흐름(`LetterApp`) / 통신(`HttpLetterApi`)으로 나뉜다. |
| **추상화와 다형성** | `LetterRepository`, `CodeGenerator`, `DiscordGateway`, `LetterApi` 인터페이스. 파일 저장과 메모리 저장은 같은 인터페이스의 서로 다른 구현이다. |
| **의존성 역전 / 의존성 주입** | `LetterService`와 `LetterDeliveryService`는 구체 클래스가 아닌 인터페이스에 의존하고, 필요한 부품과 `Clock`을 생성자로 받는다. 어떤 구현을 쓸지는 `LetterServer.start()`(조립 담당)에서만 정한다. |
| **개방-폐쇄 원칙(OCP)** | DB 저장이 필요해지면 `LetterRepository`를 구현한 클래스를 하나 추가하고, 디스코드 말고 다른 메신저로 알리려면 `DiscordGateway`처럼 새 구현을 추가하고, 조립 부분만 바꾸면 된다. 서비스 코드는 바뀌지 않는다. |
| **Null Object 패턴** | 봇 토큰이 없으면 `DisabledDiscordGateway`가 들어간다. 호출하면 "설정되지 않음"으로 실패하므로 나머지 코드에 `null` 검사나 "기능이 켜져 있나" 분기가 필요 없다. |
| **데코레이터 패턴** | `RateLimitedHandler`가 `LetterHttpHandler`를 감싸서 횟수 제한만 덧붙인다. 감싸인 핸들러는 이 기능을 모른다. `RateLimiter`는 창 길이를 받을 수 있어서 같은 클래스를 "IP당 분당 N회"와 "받는 사람당 10분 3회" 두 곳에 재사용한다. |
| **계층 분리** | 도메인은 HTTP를 모른다. 오류 종류(`LetterException.Kind`)를 HTTP 상태 코드로 바꾸는 일(`HttpResponses.statusOf`)은 HTTP 계층이 맡는다. |
| **중복 제거(DRY)** | 서버와 클라이언트가 `protocol` 패키지의 `FormCodec`/`LetterForm`/`DiscordForm`을 공유한다. 오류를 HTTP 응답으로 바꾸는 공통 처리(`HttpResponses.guard`)도 모든 핸들러가 함께 쓴다. |
| **예외 설계** | 업무 오류는 `LetterException`(종류별 정적 팩토리), 통신 오류는 `ApiException`(재시도 가능 여부 포함), 디스코드 통신 오류는 `DiscordGatewayException`(원인: 설정 없음 / DM 불가 / 일시 장애). 디스코드 전달이 실패해도 편지 저장 결과와 분리해 `Delivery.FAILED`로 알린다. 예상하지 못한 오류의 자세한 내용은 로그에만 남긴다. |
| **테스트 용이성** | 의존성 주입 덕분에 파일 없이(`InMemoryLetterRepository`), 정해진 코드(`SequenceCodeGenerator`), 정해진 시간(`Clock.fixed`), 가짜 디스코드(`FakeDiscord`)로 서비스를 검증한다. 디스코드 REST 구현은 로컬에 띄운 가짜 디스코드 서버로, 전체 흐름은 진짜 `LetterServer`를 비어 있는 포트에 띄워 콘솔 입력까지 흘려 넣어 검증한다. |

## 실행 (IntelliJ)

1. `src`를 **Sources Root**, `test`를 **Test Sources Root**로 지정한다 (폴더 우클릭 → Mark Directory as).
2. 서버: `letter.server.LetterServer`의 `main` 실행.
3. 클라이언트: `letter.client.LetterClient`의 `main` 실행. 내 PC의 서버에 접속하려면 Program arguments에 `http://localhost:8080`을 쓴다. (비우면 기본 서버 `http://service.gsmsv.site:26129`에 접속한다)
4. 테스트: `test/letter/LetterTests.java`의 `main` 실행.

## 실행 (터미널)

```bash
javac -encoding UTF-8 -d out $(find src test -name '*.java')   # Windows PowerShell은 아래 참고
java -cp out letter.server.LetterServer          # 서버 (환경변수 PORT, DATA_DIR 등)
java -cp out letter.client.LetterClient http://localhost:8080
java -cp out letter.LetterTests                  # 마지막 줄: "결과: 44개 통과, 0개 실패"
```

PowerShell에서는 `javac -encoding UTF-8 -d out (Get-ChildItem -Recurse src,test -Filter *.java).FullName`

클라이언트 jar 만들기 (위에서 컴파일한 `out` 기준, 서버와 테스트 코드는 넣지 않는다):

```bash
jar cfe letter-client.jar letter.client.LetterClient -C out letter/client -C out letter/domain -C out letter/protocol
```

## 서버 설정 (환경변수, 모두 선택)

| 이름 | 기본값 | 설명 |
|---|---|---|
| `PORT` | 8080 | 포트 (첫 실행 인자로도 지정 가능) |
| `DATA_DIR` | data | 편지 저장 폴더 (편지 하나당 `{코드}.txt` 파일 하나) |
| `MAX_BODY_BYTES` | 2000000 | 요청 크기 제한 (약 2MB) |
| `RATE_LIMIT_PER_MIN` | 60 | IP당 분당 요청 제한 |
| `TIME_ZONE` | Asia/Seoul | 작성 시각의 시간대 |
| `DISCORD_BOT_TOKEN` | (없음) | 디스코드 봇 토큰. 없으면 디스코드 전송 기능만 꺼지고 나머지는 정상 동작한다. |

## API

모든 요청/응답 본문은 `key=value&key=value` 형식(URL 인코딩, UTF-8)입니다.

| 요청 | 설명 | 성공 응답 (200) |
|---|---|---|
| `POST /letters` | 본문 `author=..&content=..` | `code=XXXXXXXX` |
| `POST /letters` | 본문에 `discordId=..`를 추가하면 봇이 그 사람에게 코드를 DM으로 전달 | `code=XXXXXXXX&delivery=SENT` 또는 `FAILED` |
| `POST /letters` | `discordId`와 함께 `replyTo=..`를 추가하면 그 사람이 답장을 받음 (`discordId` 없이 `replyTo`만 보내면 400) | 위와 같음 |
| `GET /letters/{code}` | 편지 조회. 답장할 수 있으면 `replyable=true`가 붙음 (답장받을 ID는 내려주지 않음) | `author=..&content=..&createdAt=2026-11-03 15:20` |
| `POST /letters/{code}/replies` | 본문 `content=..` — 답장을 새 편지로 저장하고 원본에 저장된 ID로 DM | `code=새코드&delivery=SENT` 또는 `FAILED` |
| `GET /discord/users/{discordId}` | 받는 사람 확인용 이름 조회 | `id=..&name=..` |
| `GET /health` | 상태 확인 | `ok` |

실패하면 `error=메시지`와 함께 다음 상태 코드를 돌려줍니다.

| 코드 | 의미 |
|---|---|
| 400 | 잘못된 요청 (내용 없음, 코드/디스코드 ID 형식 오류, 답장을 받지 않는 편지에 답장 등) |
| 404 | 없는 편지, 없는 디스코드 사용자, 없는 주소 |
| 405 | 지원하지 않는 메서드 |
| 413 | 요청이 너무 큼 |
| 429 | 요청이 너무 많음 (IP당 분당 제한, 또는 같은 사람에게 10분에 3번 초과) |
| 500 | 서버 오류 (저장 실패, 손상된 데이터 등) |
| 503 | 디스코드 사용자 조회를 할 수 없음 (봇 토큰 미설정, 디스코드 통신 장애) |

`delivery=FAILED`는 **오류가 아닙니다.** 편지는 저장되었고 코드를 디스코드로 전달하지 못했다는 뜻입니다. (HTTP 상태는 200. 봇 토큰이 없는 서버에 `discordId`를 붙여 보내도 이렇게 응답합니다. 앞서 `GET /discord/users/{id}`로 먼저 조회하면 503으로 미리 알 수 있습니다.)

예시:

```bash
curl -X POST localhost:8080/letters --data-urlencode "author=hong" --data-urlencode "content=hello"
# code=AB23CD45
curl localhost:8080/letters/AB23CD45
# author=hong&content=hello&createdAt=2026-11-03+15%3A20
```

## 디스코드 봇 만들기 (서버를 직접 운영할 때)

1. [Discord Developer Portal](https://discord.com/developers/applications)에서 **New Application**으로 애플리케이션을 만들고, **Bot** 메뉴에서 봇을 추가합니다.
2. **Bot** 메뉴의 **Reset Token**으로 토큰을 발급받습니다. 토큰은 한 번만 보이니 바로 복사해 둡니다. (화면 구성은 디스코드 사정에 따라 바뀔 수 있습니다)
3. **OAuth2 → URL Generator**에서 `bot` 범위를 선택해 초대 링크를 만들고, 편지를 쓰는 사람들이 모인 디스코드 서버에 봇을 초대합니다. 받는 사람이 이 서버에 있어야 DM을 받을 수 있습니다.
4. 서버에 `DISCORD_BOT_TOKEN`을 설정하고 재시작합니다. 로그에 `디스코드 전송 켜짐`이 나오면 됩니다.
5. 확인: `curl localhost:포트/discord/users/내_디스코드_ID` 에서 `name=...`이 나오면 토큰이 유효하고 서버가 디스코드와 통신되는 것입니다. (`503`이면 토큰이나 네트워크 문제)

이 봇은 메시지를 **보내기만** 합니다. 디스코드에 계속 접속해 있지 않고, 별도 권한(Intents)도 필요 없습니다.

> **토큰 관리**: 토큰은 코드나 git, 채팅에 절대 올리지 마세요. 노출되었다면 Developer Portal에서 바로 **Reset Token**으로 다시 발급하세요. 이전 토큰은 즉시 무효가 됩니다.
> 토큰을 바꿀 때는 아래 `discord.env` 파일의 값만 고치고 서비스를 재시작하면 됩니다. 코드 수정이나 재배포는 필요 없습니다.

## 배포 (Ubuntu VM)

GSMSV를 사용했습니다,
`deploy/letter-server.service` 를 참고하세요. (`Dockerfile`은 현재 구조에 맞지 않아 사용하지 않습니다)

### 처음 설치

```bash
sudo apt update && sudo apt install -y openjdk-17-jdk-headless git
git clone https://github.com/xhekr2/Letter.git && cd Letter

sudo useradd --system --no-create-home letter
sudo mkdir -p /opt/letter
sudo javac -encoding UTF-8 -d /opt/letter $(find src -name '*.java')   # 테스트는 제외하고 컴파일

sudo cp deploy/letter-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now letter-server
```

서비스 파일의 포트(`PORT=10000`)와 데이터 폴더(`/var/lib/letter`)는 필요에 맞게 바꾸세요.
확인: `curl localhost:10000/health` → `ok`

### 디스코드 봇 토큰 넣기

토큰은 서비스 파일이 아니라 별도 파일에 둡니다. (서비스 파일에는 `EnvironmentFile=-/etc/letter/discord.env`가 이미 들어 있고, 파일이 없으면 디스코드 전송만 꺼집니다)

```bash
sudo mkdir -p /etc/letter
sudo install -m 600 -o root -g root /dev/null /etc/letter/discord.env
sudo nano /etc/letter/discord.env        # 파일 안에 한 줄: DISCORD_BOT_TOKEN=발급받은토큰
sudo systemctl restart letter-server
sudo journalctl -u letter-server -n 3 --no-pager   # "디스코드 전송 켜짐" 확인
```

`nano`로 파일 안에 입력하는 이유는 토큰이 터미널 기록(history)에 남지 않게 하기 위해서입니다. `=` 앞뒤에 공백이나 따옴표를 넣지 마세요.

> 이미 설치된 서버의 서비스 파일에 `EnvironmentFile`이 없다면, 서비스 파일을 덮어쓰지 말고 드롭인으로 한 줄만 추가하세요.
> `sudo systemctl edit letter-server` 를 실행해 `[Service]` 아래에 `EnvironmentFile=-/etc/letter/discord.env` 를 적습니다. (편집기의 안내 주석 **위쪽**에 적어야 저장됩니다)

### 코드 업데이트

```bash
cd ~/Letter && git pull
rm -rf /tmp/letter-test && mkdir /tmp/letter-test
javac -encoding UTF-8 -d /tmp/letter-test $(find src test -name '*.java')
java -Dfile.encoding=UTF-8 -cp /tmp/letter-test letter.LetterTests | tail -1   # "0개 실패"인지 확인

sudo cp -a /opt/letter /opt/letter.bak-$(date +%Y%m%d-%H%M)   # 롤백용 백업 (날짜가 붙은 이름)
rm -rf /tmp/letter-classes && mkdir /tmp/letter-classes
javac -encoding UTF-8 -d /tmp/letter-classes $(find src -name '*.java')
sudo systemctl stop letter-server
sudo rm -rf /opt/letter/* && sudo cp -r /tmp/letter-classes/. /opt/letter/
sudo systemctl start letter-server
```

편지 데이터(`/var/lib/letter`)는 업데이트 과정에서 건드리지 않습니다. 문제가 생기면 서비스를 멈추고 백업(`/opt/letter.bak-날짜시간`)의 내용을 `/opt/letter`로 되돌린 뒤 다시 시작하세요.
서비스를 멈출 때 `status=143`이 로그에 남는 것은 정상 종료 신호(SIGTERM)이므로 오류가 아닙니다.

## 알아둘 점

- **고유 코드가 곧 열쇠입니다.** 코드를 아는 사람은 누구나 편지를 읽을 수 있습니다. 코드는 8자리(영문 대문자 + 숫자 2~9)이고, 디스코드 DM으로 전달할 때도 받는 사람 한 명에게만 가도록 ID를 확인한 뒤 보냅니다.
- **편지는 서버에 평문으로 저장**되고 자동으로 삭제되지 않습니다.
- 기본 서버 주소가 `http://`라서 통신이 암호화되지 않습니다. 중요한 내용은 보내지 마세요. (서버 앞에 HTTPS 리버스 프록시를 두는 것이 권장됩니다)
- **답장받을 ID는 본인 확인을 하지 않습니다.** 받는 사람 ID와 마찬가지로 "디스코드에 있는 사용자인지"만 확인하므로, 남의 ID를 적으면 그 사람이 답장 DM을 받을 수 있습니다. (같은 사람에게는 10분에 3번까지로 제한됩니다)
- 답장받을 ID는 서버 파일에만 저장되고 편지를 읽는 사람에게는 전달되지 않습니다.
- 작성자 이름은 인증되지 않은 별칭입니다. 다른 사람의 이름을 쓸 수 있습니다.
- 편지 내용은 DM에 들어가지 않습니다. 그래도 `curl` 등으로 API를 직접 호출하면 코드만으로 읽을 수 있습니다. 이 프로그램으로 읽어야 편지다운 경험이 되도록 한 것이며, 강제 장치는 아닙니다.
