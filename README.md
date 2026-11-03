# (원하면)익명 편지 작성하기

CLI로 편지를 쓰고, 발급된 고유 코드로 편지를 확인하는 자바 프로그램입니다.
서버(HTTP)와 클라이언트(CLI)로 나뉘며, 외부 라이브러리 없이 JDK만 사용합니다. (JDK 17 이상)

## 사용 방법

### 1. 준비
JDK 또는 JRE **17 이상**이 필요합니다. 터미널에서 `java -version`으로 확인하세요.

### 2. 클라이언트 받기
[Releases](https://github.com/xhekr2/Letter/releases)에서 최신 `letter-client.jar`를 내려받습니다.

### 3. 실행
터미널(PowerShell, 맥/리눅스 터미널 등)에서 JAR가 있는 폴더로 이동해 실행합니다.

```
java -jar letter-client.jar
```

더블클릭으로는 실행되지 않습니다. (콘솔 프로그램이라 창이 바로 닫힙니다)

### 4. 편지 쓰고 확인하기
- `1. 편지 쓰기`: 이름(비우면 익명)과 내용을 쓰고, 새 줄에 `/end`만 입력하면 끝납니다. 발송하면 **고유 코드**가 나옵니다.
- `2. 편지 확인`: 받은 고유 코드를 입력하면 편지를 볼 수 있습니다. 코드를 잃어버리면 편지를 다시 볼 수 없으니 꼭 기억해 두세요.

### 다른 서버를 쓰고 싶다면
기본 서버 주소는 코드(`LetterClient.DEFAULT_URL`)에 들어 있습니다. 직접 서버를 띄운 경우 주소를 인자로 줍니다.

```
java -jar letter-client.jar http://내서버주소:포트
```

환경변수 `LETTER_SERVER_URL`에 주소를 넣어 두어도 됩니다. (우선순위: 인자 > 환경변수 > 기본값)

> 기본 서버는 학교 VM에서 운영 중이라 사정에 따라 접속이 안 될 수 있습니다.

## 프로젝트 구조

```
src/letter/
├── domain/            편지의 개념과 규칙 (HTTP, 파일을 전혀 모름)
│   ├── LetterDraft      발송 전 편지: 내용 필수, 이름 50자 제한, 익명 처리
│   ├── Letter           저장된 편지: 작성자, 내용, 작성 시각
│   ├── LetterCode       고유 코드: 형식이 검증된 값 객체
│   └── LetterException  업무 오류 (종류: Kind)
├── protocol/          서버와 클라이언트가 함께 쓰는 통신 형식
│   ├── FormCodec        key=value&... 변환
│   └── LetterForm       Letter <-> 전송/저장 문자열
├── server/
│   ├── LetterServer     시작점이자 조립 담당
│   ├── ServerConfig     환경변수 -> 불변 설정 객체
│   ├── service/         업무 규칙
│   │   ├── LetterService      발송/조회 (저장소, 코드 생성기, 시계를 주입받음)
│   │   ├── CodeGenerator      (인터페이스) 코드 만드는 방법
│   │   └── RandomCodeGenerator
│   ├── storage/         저장소
│   │   ├── LetterRepository   (인터페이스)
│   │   ├── FileLetterRepository      파일로 저장
│   │   └── InMemoryLetterRepository  메모리에 저장 (테스트용)
│   └── http/            HTTP 계층
│       ├── LetterHttpHandler   요청 -> 서비스 호출
│       ├── RateLimitedHandler  (데코레이터) 요청 횟수 제한을 덧붙임
│       ├── RateLimiter
│       └── HttpResponses
└── client/
    ├── LetterClient     시작점이자 조립 담당
    ├── LetterApp        프로그램 흐름 (메뉴, 작성, 확인)
    ├── ConsoleView      콘솔 입출력만 담당
    ├── LetterApi        (인터페이스) 서버와 주고받는 기능
    ├── HttpLetterApi    HTTP로 구현
    └── ApiException
test/letter/LetterTests.java   단위 테스트 (21개)
```

## 객체지향 설계 포인트

| 원칙/개념 | 적용한 곳 |
|---|---|
| **캡슐화** | 편지 규칙(내용 필수, 이름 제한, 제어문자 제거)은 `LetterDraft` 한 곳에만 있다. 다른 클래스는 규칙을 다시 검사하지 않는다. |
| **불변 객체 / 값 객체** | `Letter`, `LetterDraft`, `LetterCode`, `ServerConfig`는 record. 만들어진 뒤에는 바뀌지 않고, 만들 때 항상 검증된다. 잘못된 상태의 객체가 존재할 수 없다. |
| **단일 책임 원칙(SRP)** | 업무 규칙(`LetterService`), 저장(`FileLetterRepository`), HTTP 변환(`LetterHttpHandler`), 횟수 제한(`RateLimiter`), 설정(`ServerConfig`)을 각각 다른 클래스가 맡는다. 클라이언트도 화면(`ConsoleView`) / 흐름(`LetterApp`) / 통신(`HttpLetterApi`)으로 나뉜다. |
| **추상화와 다형성** | `LetterRepository`, `CodeGenerator`, `LetterApi` 인터페이스. 파일 저장과 메모리 저장은 같은 인터페이스의 서로 다른 구현이다. |
| **의존성 역전 / 의존성 주입** | `LetterService`는 구체 클래스가 아닌 인터페이스에 의존하고, 필요한 부품과 `Clock`을 생성자로 받는다. 어떤 구현을 쓸지는 `LetterServer.start()`(조립 담당)에서만 정한다. |
| **개방-폐쇄 원칙(OCP)** | DB 저장이 필요해지면 `LetterRepository`를 구현한 클래스를 하나 추가하고 조립 부분만 바꾸면 된다. `LetterService` 코드는 바뀌지 않는다. |
| **데코레이터 패턴** | `RateLimitedHandler`가 `LetterHttpHandler`를 감싸서 횟수 제한만 덧붙인다. 감싸인 핸들러는 이 기능을 모른다. |
| **계층 분리** | 도메인은 HTTP를 모른다. 오류 종류(`LetterException.Kind`)를 HTTP 상태 코드로 바꾸는 일(`HttpResponses.statusOf`)은 HTTP 계층이 맡는다. |
| **중복 제거(DRY)** | 서버와 클라이언트가 `protocol` 패키지의 `FormCodec`/`LetterForm`을 공유한다. |
| **예외 설계** | 업무 오류는 `LetterException`(종류별 정적 팩토리), 통신 오류는 `ApiException`(재시도 가능 여부 포함). 예상하지 못한 오류의 자세한 내용은 로그에만 남긴다. |
| **테스트 용이성** | 의존성 주입 덕분에 파일 없이(`InMemoryLetterRepository`), 정해진 코드(`SequenceCodeGenerator`), 정해진 시간(`Clock.fixed`)으로 서비스를 검증한다. |

## 실행 (IntelliJ)

1. `src`를 **Sources Root**, `test`를 **Test Sources Root**로 지정한다 (폴더 우클릭 → Mark Directory as).
2. 서버: `letter.server.LetterServer`의 `main` 실행.
3. 클라이언트: `letter.client.LetterClient`의 `main` 실행. 서버 주소는 Program arguments에 쓴다. (기본 `http://localhost:8080`)
4. 테스트: `test/letter/LetterTests.java`의 `main` 실행.

## 실행 (터미널)

```bash
javac -encoding UTF-8 -d out $(find src test -name '*.java')   # Windows PowerShell은 아래 참고
java -cp out letter.server.LetterServer          # 서버 (환경변수 PORT, DATA_DIR 등)
java -cp out letter.client.LetterClient http://localhost:8080
java -cp out letter.LetterTests
```

PowerShell에서는 `javac -encoding UTF-8 -d out (Get-ChildItem -Recurse src,test -Filter *.java).FullName`

## 서버 설정 (환경변수, 모두 선택)

| 이름 | 기본값 | 설명 |
|---|---|---|
| `PORT` | 8080 | 포트 (첫 실행 인자로도 지정 가능) |
| `DATA_DIR` | data | 편지 저장 폴더 |
| `MAX_BODY_BYTES` | 2000000 | 요청 크기 제한 (약 2MB) |
| `RATE_LIMIT_PER_MIN` | 60 | IP당 분당 요청 제한 |
| `TIME_ZONE` | Asia/Seoul | 작성 시각의 시간대 |

## API

- `POST /letters` — `author=..&content=..` (URL 인코딩) → `code=XXXXXXXX`
- `GET /letters/{code}` → `author=..&content=..&createdAt=..`
- `GET /health` → `ok`
- 실패 시 `error=메시지` (400, 404, 405, 413, 429, 500)

## 배포 (Ubuntu VM)

GSMSV를 사용했습니다,
`deploy/letter-server.service` 와 `Dockerfile` 참고.
