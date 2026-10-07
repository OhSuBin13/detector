# 크롤링 단계 예외 케이스 조사

조사일 2026-10-06. 대상은 `crawl/Crawler.java`의 페이지 방문·frame 점검 루프다. 크롤링과 탐지가 한 루프에서 돌므로 탐지 중에 난 예외도 포함한다.

## 1. 요약

| 순위 | 케이스 | 결과 | 확인 |
|---|---|---|---|
| **P0** | `onbeforeunload` 페이지 하나에 워커가 갇힘 | 그 뒤 모든 이동이 `net::ERR_ABORTED`. 워커 1개면 40회 연속 실패로 **BLOCKED("사이트가 접속을 막는다")로 점검 종료** | 재현 · **수정됨(6절)** |
| **P1** | 이전 페이지 대화상자 핸들러(`Dialog::dismiss`)의 예외 | **다음 페이지**의 `navigate`에서 터져 멀쩡한 페이지를 잃음 | 재현 · **수정됨(6절)** |
| **P1** | `inspect()`가 `PlaywrightException`만 잡음 | `UncheckedIOException`·`ClassCastException`·`IllegalArgumentException`이 페이지 전체 실패가 됨. iframe 하나만 깨져도 페이지 전체를 잃음 | 재현 · **수정됨(6절)** |
| **P1** | 본문 frame의 스크립트 오류를 기록 없이 무시 | 방문 수에는 들어가고 실패로 세지 않지만 **광고는 놓침** | 재현 · **수정됨(6절)** |
| **P1** | 진입 페이지의 결정적 오류도 3번 시도 | 같은 오류로 두 번 더 다시 엶. 진입 페이지는 방문 수에서 빠지고, 다른 링크가 없으면 ERROR | 재현 · **수정됨(6절)** |
| P2 | `settle()`이 페이지의 `Promise`·`setTimeout`에 의존 | Promise를 덮어쓰면 페이지 실패, setTimeout을 덮어쓰면 38초 멈춤 뒤 브라우저 재시작 | 재현 |
| P2 | 첨부파일 응답·연결 끊김·리다이렉트 반복을 "실패"로 셈 | `pages_failed`가 부풀고 연속 실패 수에 들어감 | 재현 |
| P2 | `--max-pages` 경쟁 | 상한 5쪽에 7쪽 방문(워커 3). 실사이트 3곳 모두 60쪽 상한에 61쪽 | 재현·실사이트 |
| P2 | 재큐된 페이지(HTTP 400 등)가 대기열 맨 뒤로 감 | 예산 안에 다시 방문하지 못해도 실패로 세지 않아 리포트에서 보이지 않음 | 실사이트(mois 4건) |
| P2 | 글자 요소 상한(2만 개) 초과 | 문서 뒤쪽 광고를 **기록 없이** 놓침(`truncated`를 아무도 보지 않음) | 재현 |
| P2 | 점검 뒤 넘어가는 주소(meta refresh, 지연 이동) | 링크가 아니라 방문 대상에 들어가지 않음 | 재현 |
| P3 | 워커의 `Error`(OOM 등), `MAX_RESTARTS` 소진, 드라이버를 못 찾은 경우 | 정적 분석만. 아래 4절 | 정적 |

문제없음으로 확인한 것: 문서 로드 전의 7단계 스크립트 이동, `alert` 반복, 팝업 폭주, about:blank·srcdoc·늦게 뜨는·다른 출처·점검 중 사라지는 iframe, Content-Type 없는 응답, 일시적 연결 끊김(Chromium이 스스로 다시 요청), HTTP 500 한 번 뒤 재시도.

## 2. 조사 방법

- **진단 기록 `--debug-errors`.**
  - 예외가 나는 모든 catch 지점에서 `Crawler.Listener.onError(site, url, throwable)`를 부른다. 그냥 넘기던 지점도 포함한다.
  - 플래그를 켜면 `ErrorLog`가 결과 폴더의 `crawl-errors.jsonl`에 스택과 원인 체인을 한 줄씩 남긴다.
  - 플래그를 끄면 동작과 출력은 그대로다.
  - `site`는 예외가 난 곳이다: `entry-retry`, `requeued`, `page-failed`, `worker-died`, `worker-error`, `launch-failed`, `redirect-followed`, `frame-timeout`, `frame-skipped`, `wait-timeout-*`, `cookie-*`, `popup-close`, `content-type`, `runner`.
- **재현 테스트.**
  - `src/test/java/addetector/crawl/CrawlExceptionBrowserTest.java`(`gradlew browserTest`)에 있다.
  - 고친 동작이 아니라 **지금의 동작**을 단언한다. 고치면 해당 단언을 바꿔야 한다.
  - 실행할 때마다 관찰 기록을 `build/investigate/repro/<테스트>.txt|.jsonl`에 남긴다.
- **실사이트 재점검.** 3절에 정리했다.

## 3. 재현 결과 상세

### 3.1 [P0] beforeunload 페이지에 갇힘
- **재현:** `pageAfterBeforeUnloadOnly`, `beforeUnloadPageEndsScanAsBlocked`
- **페이지 조건:** `window.onbeforeunload = e => { e.preventDefault(); e.returnValue = ''; }`. 글쓰기 화면 등에서 흔히 쓴다.
- **원인:**
  - `page.onDialog(Dialog::dismiss)`(Crawler.java `open()`)가 beforeunload 확인창도 dismiss한다. dismiss는 "이 페이지에 머묾"이라서 이동이 취소된다.
  - 서버에는 요청이 하나도 가지 않는다.
  - 브라우저와 페이지는 살아 있어 `alive()`가 true다. 그래서 `reopen()`도 하지 않고, 이 워커는 그 페이지에 영원히 갇힌다.
- **관찰:**
  - 링크 5개가 모두 `page-failed`(`net::ERR_ABORTED at …`)로 실패했다.
  - 워커 1개에 링크 45개로 돌렸을 때: 110초 뒤 `status=BLOCKED`, `pages_failed=40`.
  - 워커가 여럿이면 다른 워커의 성공이 연속 실패 수를 초기화한다. 그래서 BLOCKED까지는 가지 않지만, 갇힌 워커가 꺼내는 페이지는 모두 잃는다.
- **수정 방향:**
  - 대화상자 종류가 `beforeunload`이면 `accept()`한다.
  - 또는 매 navigate 전에 `page.evaluate("window.onbeforeunload = null")`을 한다.
  - 또는 `ERR_ABORTED`가 나면 `page.close()` 후 새 페이지를 연다.

### 3.2 [P1] 대화상자 핸들러 예외가 다음 페이지를 죽임
- **재현:** `pageAfterBeforeUnloadAndDialogs`. alert 반복과 beforeunload가 함께 있는 페이지다.
- **관찰:**
  - 다음 페이지 `/after.html`은 서버가 정상 응답(hits=1)했는데 `page-failed`로 끝났다.
  - 메시지는 `Protocol error (Page.handleJavaScriptDialog): Not attached to an active page`다.
- **스택:**
  - `DialogImpl.dismiss` ← `ListenerCollection.notify` ← `BrowserContextImpl.handleEvent` ← … ← `PageImpl.navigate` ← `Crawler$Worker.visit`
  - Playwright Java는 이벤트 핸들러를 다음 동기 호출 안에서 실행한다. 그래서 핸들러의 예외가 엉뚱한 호출에서 터진다.
- **수정 방향:** 핸들러를 `d -> { try { d.dismiss(); } catch (PlaywrightException ignored) {} }`로 감싼다. `route.abort`도 같은 방식으로 감싼다.

### 3.3 [P1] inspect()가 잡지 않는 예외 → 페이지 전체 실패
`inspect()`는 `PlaywrightException`만 잡는다. 아래 예외는 `visit()` 밖으로 나가 그 페이지를 통째로 "건너뜀"으로 만든다.

| 테스트 | 페이지가 바꾼 것 | 예외 | 터진 곳 |
|---|---|---|---|
| `prototypeStyleArrayToJsonBreaksSnapshot` | `Array.prototype.toJSON`(구버전 Prototype.js와 같은 방식) | `UncheckedIOException` ← Jackson `MismatchedInputException`(holders가 문자열) | `FrameSnapshot.parse` |
| `overriddenJsonStringify` | `JSON.stringify` | `IllegalArgumentException: argument "content" is null` | `FrameSnapshot.collect`의 `(String) json`이 null |
| `overriddenArrayFrom` | `Array.from` | `ClassCastException: String → List` | `LinkExtractor.extract` |

- **iframe 하나만 깨져도 같다(`brokenChildFrameDropsWholePage`).**
  - 본문 frame에서 이미 찾은 광고와 모은 링크는 남는다.
  - 그 페이지는 방문 수(`onPage`)에 들어가지 않고 실패로 센다.
  - 진입 페이지라면 같은 결정적 오류로 **두 번 더** 다시 연다(`entry-retry` 2회, 약 4초 낭비).
  - 진입 페이지에 다른 링크가 없으면 `visited==0`이 되어 "진입 주소에 접속하지 못했습니다"(ERROR)로 끝날 수 있다. 이 경우는 코드로만 확인했다.
- **`overriddenArrayFrom`에서는 링크 추출에서 실패한다.** 그래서 그 페이지에서만 이어지는 페이지(`/after.html`)도 발견하지 못한다.
- **수정 방향:**
  - `inspect()`에서 `RuntimeException`을 frame 단위로 잡아 그 frame만 건너뛰고 `onError`로 남긴다.
  - 수집 스크립트는 페이지 전역 대신 Playwright 쪽 직렬화를 쓴다. 객체를 그대로 반환하거나, 스크립트 시작 시 `JSON.stringify`·`Array.from`·`Map`·`Set`을 깨끗한 iframe 등에서 확보해 둔다.
  - 진입 페이지의 재시도는 네트워크 오류일 때만 한다.

### 3.4 [P1] 본문 frame 오류를 기록 없이 넘김
- **재현:**
  - `overriddenSetConstructor`: `window.Set`을 덮어써 collector에서 `TypeError: SKIP.has is not a function`이 난다.
  - `frozenAdxGlobal`: `window.__adx`를 바꿀 수 없게 해 둔다. 예외는 없고 선택자를 만들지 못한다.
- **관찰:**
  - 페이지는 방문한 것으로 세고, `pages_failed`는 0이다.
  - 광고는 놓친다. 결과만 봐서는 놓쳤다는 사실을 알 수 없다.
- **원인:** `inspect()`의 catch가 `!alive() || main && navigated(e)`가 아니면 본문 frame의 오류도 조용히 넘긴다.
- **수정 방향:**
  - 본문 frame에서 넘긴 오류는 최소한 `report.json`에 "점검하지 못한 frame 수"로 남긴다.
  - 전역 이름은 충돌하기 어려운 이름(Symbol 등)으로 바꾼다.

### 3.5 [P2] settle()이 페이지의 Promise·setTimeout에 의존
- **`brokenPromiseBreaksSettle`:** `settle()`의 `page.evaluate`가 `new Promise`에서 던진다. `PlaywrightException`이 나서 페이지 전체를 실패로 센다(본문 점검 전이다).
- **`brokenSetTimeoutHangsSettle`:**
  - `setTimeout`이 아무것도 하지 않으면 evaluate가 끝나지 않는다.
  - 38초 뒤 감시 스레드가 드라이버를 끊어 `Failed to read message from driver, pipe closed.`가 나고, 브라우저 재시작 1회(`MAX_RESTARTS` 5 중)를 쓴다.
  - 같은 사이트에 이런 페이지가 여럿이면 6번째에서 워커가 멈춘다(H4).
- **수정 방향:**
  - lazy iframe 깨우기는 동기 evaluate로 한다. 50ms 대기는 Java 쪽에서 한다.
  - `settle()`의 실패는 무시하고 본문 점검으로 넘어간다.

### 3.6 [P2] 거부가 아닌 응답 오류의 분류(`transportErrorsAreFailuresNotRefusals`)

| 경로 | 결과 | 비고 |
|---|---|---|
| 응답 없이 연결 끊음 | `page-failed` `net::ERR_EMPTY_RESPONSE` | Chromium이 스스로 1번 더 요청함(hits=2) |
| 302 자기 자신 | `page-failed` `net::ERR_TOO_MANY_REDIRECTS` | 20번 요청 |
| `Content-Disposition: attachment` | `page-failed` `Download is starting` | 첨부파일을 **실패**로 셈. `UrlPriority.file()`이 확장자로만 거르기 때문 |
| HTTP 500 | `requeued` 1번 → `page-failed` | 설계대로 |
| 처음만 연결 끊김 | 성공 | Chromium 재요청으로 회복 |
| Content-Type 없음 | 성공 | |

- **수정 방향:**
  - `Download is starting`은 실패가 아니라 "파일"로 분류한다. `pages_failed`와 연속 실패 수에서 뺀다.
  - `net::ERR_*`는 거부와 따로 한 번 재시도할지 정한다.

### 3.7 [P2] 그 밖의 조용한 손실
- **`maxPagesRace`:**
  - 워커 3개, `--max-pages 5`로 돌렸더니 7쪽을 방문했다.
  - `pageLimitReached()` 확인과 `visited.incrementAndGet()` 사이에 경쟁이 있다. 실사이트 mois에서도 30쪽 지정에 31쪽을 방문했다.
- **`hugeDomAlone`:**
  - 6만 문단짜리 문서에서 앞쪽 광고만 찾고 뒤쪽 광고는 놓쳤다.
  - `FrameSnapshot.MAX_HOLDERS`(20,000)나 6초 시간 상한에 걸려 `truncated=true`가 된다. 그런데 이 값을 읽는 코드가 없어 리포트에도 남지 않는다.
- **`delayedRedirectDuringInspection`:**
  - 1초 meta refresh와 1.6초 지연 이동은 점검이 먼저 끝나 원래 페이지만 본다.
  - 이동할 주소(`/landing*.html`)는 방문 대상에 오르지 않는다.
  - `LinkExtractor`가 `<meta http-equiv=refresh>`와 스크립트 이동을 읽지 않기 때문이다.

## 4. 정적 분석만 한 것

| ID | 내용 | 위치 | 영향 |
|---|---|---|---|
| H3 | 워커의 `Error`(OOM, StackOverflow)를 잡지 않는다. 이번에 `onError("worker-error")`로 기록만 하고 다시 던지게 했다 | `Crawler.Worker.run()` | 워커 스레드가 죽고 stderr에만 남는다. 상태는 COMPLETED/BUDGET |
| H4 | `restarts`는 워커 수명 동안 초기화되지 않는다. 6번째 재시작 요청에서 워커가 멈춘다 | `reopen()` | 모든 워커가 멈추면 대기열이 남아 BUDGET("방문하지 못한 페이지가 N개")으로 끝난다. 실제 원인(브라우저 반복 끊김)이 상태에 드러나지 않는다 |
| H5 | 드라이버 프로세스는 Playwright 생성 전후 자식 프로세스의 차이로 찾는다. 못 찾으면 `driver==null`이라 `kill()`이 아무것도 하지 않는다 | `BrowserLauncher.launch()` | 감시 스레드가 멈춘 페이지를 끊지 못해 hard 마감까지 그 워커가 멈춘다. `CREATE_LOCK`으로 워커끼리는 구분되므로 가능성은 낮다 |
| H6 | `visit()`의 catch 블록 안 `waitFor(DOMCONTENTLOADED)`는 `TimeoutError`만 삼킨다 | `visit()` | 이번 시험에서는 나오지 않았다 |
| H7 | `navigated()`가 메시지 문자열로 판정한다 | `navigated()` | Playwright를 올리면 다시 확인해야 한다 |

## 5. 실사이트 재점검

2026-10-06에 다시 점검했다. 설정은 예산 4분, 워커 2개, `--max-pages 60`, `--debug-errors`다. 기록은 `build/investigate/<사이트>/`(`console.log`, `crawl-errors.jsonl`, `report.*`)에 있다.

| 사이트 | 방문 | 실패 | 시간 | 예외 기록 |
|---|---|---|---|---|
| www.mois.go.kr | 61 | 0 | 85s | `requeued` HTTP 400 ×4, `redirect-followed` ×1(진입 페이지), `frame-timeout` ×1(문서가 없는 iframe, url 빈 값) |
| www.korea.kr | 61 | 0 | 45s | `wait-timeout-networkidle` ×2(영상 페이지) |
| www.jongno.go.kr | 61 | 0 | 87s | `wait-timeout-networkidle` ×3, `wait-timeout-load` ×2, `redirect-followed` ×2(게시판, 로그인이 필요한 신청 목록) |

- **실패로 이어진 예외는 없다.**
  - 기록된 예외는 모두 설계대로 처리되는 종류다: 시간초과 뒤 그대로 점검, 스크립트 이동을 따라감, 문서 없는 iframe 건너뜀.
  - 1~3절의 P0·P1 케이스는 이 세 사이트의 처음 60쪽에서는 나오지 않았다.
- **mois의 HTTP 400은 실패로 세지 않았다.**
  - 이전 기록(`docs/runs/mois`)에서는 400 10건이 `pages_failed=10`이었다. 이번에는 같은 계열 4건이 재큐되어 `pages_failed=0`이다.
  - 다만 재큐된 4건은 대기열 맨 뒤로 가서 60쪽 상한 안에 다시 방문하지 못했다. "회복"이 아니라 "미뤄짐"이다.
  - 큰 사이트에서 재큐된 페이지는 예산 안에 다시 방문하지 못할 가능성이 크다. 이 경우 실패 수에도 들어가지 않아 리포트에서 보이지 않는다(P2).
- **세 곳 모두 `--max-pages 60`에서 61쪽을 방문했다.** 3.7의 경쟁이 실사이트에서도 나온다.
- **이 결과는 사이트 전체가 아니라 처음 60쪽에 한한다.** P0(beforeunload)는 글쓰기·신청 화면에서 나올 가능성이 크므로 25분 전체 점검에서 확인할 필요가 있다.

## 6. 수정 내역 (2026-10-07, P0·P1)

| 케이스 | 수정 | 위치 | 확인 테스트 |
|---|---|---|---|
| 3.1 beforeunload에 갇힘 | 대화상자 종류가 `beforeunload`이면 `accept()`, 나머지는 `dismiss()` | `Crawler.Worker.answer()` | `pageAfterBeforeUnloadOnly`: 5/5 방문. `beforeUnloadPageDoesNotBlockScan`: 전에는 110초 뒤 BLOCKED, 지금은 4.2초에 47쪽 COMPLETED |
| 3.2 핸들러 예외가 다음 호출에서 터짐 | 대화상자 핸들러와 이미지 차단 `route.abort()`를 try로 감싼다. 예외는 `onError("dialog" / "route-abort")`로만 남긴다 | `Crawler.Worker.open()`, `answer()` | `pageAfterBeforeUnloadAndDialogs`: 다음 페이지를 정상 점검 |
| 3.3 inspect()가 잡지 않는 예외 | `inspect()`에서 `RuntimeException`도 frame 단위로 잡아 그 frame만 건너뛴다 | `Crawler.Worker.inspect()`, `skipped()` | `brokenChildFrameSkipsOnlyThatFrame`: 진입 페이지 재시도 0회, 정상 방문 |
| 3.3 페이지 전역 의존 | 직렬화하는 동안만 `toJSON`을 치운다. 링크 추출은 `Array.from` 대신 펼침 문법을 쓴다. 반환 타입을 확인해 알기 쉬운 `IllegalStateException`을 낸다 | `collector.js`, `LinkExtractor`, `FrameSnapshot.collect()` | `prototypeStyleArrayToJsonIsIgnored`, `overriddenArrayFrom`: 광고를 찾음 |
| 3.4 본문 오류를 조용히 넘김 | 본문 frame을 건너뛰면 "본문을 점검하지 못함: url (이유)" 로그를 남긴다. `report.json` `meta.pages_uninspected`로 세고, 결과 화면 하단에 "본문 점검 실패 N쪽"을 표시한다. `__adx`를 쓰지 못하면 오류로 알린다 | `Crawler`, `Runner`, `ReportWriter.RunInfo`, `app.html`, `collector.js` | `overriddenSetConstructor`, `overriddenJsonStringify`, `frozenAdxGlobal`: `pages_uninspected=1` |

- **아직 막지 못한 것:** 페이지가 `JSON.stringify`·`Set`·`Map` 자체를 바꾼 경우다. 이 경우는 그 frame을 점검하지 못한다. 대신 이제 조용히 넘기지 않고 `pages_uninspected`로 드러난다.
- **회귀 확인:** 단위 테스트 76/76, 브라우저 테스트 27/27, mock 사이트 평가 74/74.

## 7. 수정 내역 (2026-10-07, P2)

| 케이스 | 수정 | 위치 | 확인 테스트 |
|---|---|---|---|
| 3.5 settle()이 페이지의 Promise·setTimeout에 의존 | lazy iframe 깨우기를 동기 evaluate로 바꿨다. 50ms 대기는 `page.waitForTimeout`으로 한다. settle 실패는 기록만 하고 점검을 계속한다 | `Crawler.Worker.settle()` | `brokenPromiseDoesNotBreakSettle`, `brokenSetTimeoutDoesNotHangSettle`: 광고를 찾음, 감시 스레드가 끊지 않음 |
| 3.6 응답 오류 분류 | `classify()`가 네 갈래로 나눈다: REFUSED(4xx·5xx, 404·410 제외), TRANSIENT(연결 끊김류 `net::ERR_*`·시간초과), FILE(`Download is starting`), OTHER. REFUSED·TRANSIENT는 한 번 다시 시도한다(간격 늘리기는 REFUSED만). FILE은 실패로도, 연속 실패로도 세지 않는다 | `Crawler.classify()`, `paceAfter()` | `transportErrorsAreClassified` |
| 3.6 미뤄 둔 페이지가 보이지 않음 | 끝날 때 대기열에 남은 재시도 페이지 수를 `pages_deferred`로 남긴다 | `Frontier.requeuedLeft()` | `deferredPagesAreReported` |
| 3.7 max-pages 경쟁 | 방문 전에 자리를 예약한다(`reserve()`/`release()`). 방문으로 세지지 않으면 자리를 돌려준다 | `Crawler.reserve()` | `maxPagesRace`: 워커 3개, 상한 5에 정확히 5쪽 |
| 3.7 truncated를 아무도 보지 않음 | 일부만 점검한 페이지를 `pages_truncated`로 세고 로그를 남긴다(상한 자체는 그대로) | `Crawler.Worker.inspect()`, `visit()` | `hugeDomAlone` |
| 3.7 meta refresh·지연 이동 주소 | 링크 추출에 `<meta http-equiv=refresh>`의 url과 인라인 스크립트의 `location.href=`·`location.replace(`·`window.open(` 주소를 더했다. 범위·파일·파괴적 링크 걸러내기는 그대로다 | `LinkExtractor` | `delayedRedirectDuringInspection`, `pageAfterPopupStorm` |
| (새로 찾음) 이동 중에 뜬 alert로 탭이 굳음 | P1 수정 뒤 반복 실행에서 나왔다. alert 반복과 beforeunload가 함께 있는 페이지를 떠날 때, 이동 도중에 뜬 alert는 닫기에 실패한다("Not attached to an active page"). 그러면 이동이 15초 시간초과로 끝났다. 탭을 새로 여는 사후 복구만으로는 한 번 굳을 때마다 15초를 잃고 일부 페이지는 끝내 실패했다(10쪽 중 1쪽). 그래서 **대화상자가 뜨지 않게 막는다**: context init 스크립트가 페이지 스크립트보다 먼저 `alert`·`confirm`·`prompt`를 무력화하고, `window`의 beforeunload 등록을 막는다. init 스크립트로 막지 못하는 `<body onbeforeunload>`는 `answer()`(beforeunload accept)가 받는다. 그래도 대화상자 처리나 이동이 Playwright 오류로 실패하면 같은 context에서 탭을 새로 연다(`resetPage()`, 쿠키 유지) | `Crawler.NO_DIALOGS`, `Worker.answer()`, `Worker.resetPage()` | `dialogDuringNavigationDoesNotWedgeTab`: 페이지를 옮길 때마다 경합을 일으켜도 11쪽 모두 방문(전에는 76초에 1쪽 실패, 지금은 0.8초). `bodyAttributeBeforeUnloadIsAccepted`: 6쪽 모두 방문 |

- **리포트와 화면:** `report.json` `meta`에 `pages_truncated`, `pages_deferred`, `files_skipped`를 더했다. 0이 아닌 값은 결과 화면 하단과 콘솔 요약에 표시한다.
- **회귀 확인:** 단위 테스트 76/76, 브라우저 테스트 30/30, mock 사이트 평가 74/74. 재현 테스트 클래스를 3번 반복해 모두 통과했다(클래스 전체 약 27초).
- **동작 변화:** 점검하는 페이지에서 `alert`·`confirm`·`prompt`가 아무 일도 하지 않는다(`confirm`은 전과 같이 "취소"). 인라인 스크립트의 `window.open` 주소도 방문 대상이 된다. onclick 속성의 `window.open`은 전부터 수집했다. 공지 팝업 페이지를 점검하게 되는 효과가 있다.
