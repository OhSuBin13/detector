# ad-detector 실행·빌드 매뉴얼

## 1. 필요한 것

| 구분 | 내용 |
|---|---|
| 배포본을 쓰는 PC | Windows 11. 그 밖에 설치할 것 없음(Java 런타임·Playwright 드라이버·브라우저가 배포 폴더에 들어 있음) |
| 빌드하는 PC | Windows 11, JDK 25, 인터넷(의존 라이브러리와 브라우저를 처음 한 번 내려받음) |
| 상용 AI API | 사용하지 않음(API KEY·`.env` 불필요) |

의존 라이브러리: Playwright for Java 1.63.0, picocli 4.7.7, Jackson 2.22.3, slf4j-simple 2.0.18.
테스트: JUnit 6.1.3, networknt json-schema-validator 1.5.9. 빌드 도구: Gradle 9.7.0(래퍼 포함).

## 2. 빌드

```bat
cd ad-detector

rem 컴파일 + 단위 테스트
gradlew build

rem 배포본에 넣을 브라우저(Chromium headless shell)를 내려받는다. 개발 PC에서 한 번만.
gradlew installBrowsers

rem 배포 폴더: build\dist\ad-detector\
gradlew bundle

rem 배포 zip: build\dist\ad-detector-<버전>-windows.zip
gradlew bundleZip
```

`bundle`이 만드는 폴더:

```
ad-detector\
  ad-detector.bat        명령줄 실행 파일
  ad-detector-ui.bat     결과 화면 실행 파일
  lib\                   프로그램과 라이브러리(jar)
  runtime\               Java 런타임(jlink로 만든 최소 구성, 약 48MB)
  driver\                Playwright 드라이버(node.exe + package)
  browsers\              Chromium headless shell (약 270MB)
  conf\                  키워드 사전(고칠 수 있음)
  docs\                  사용설명서, 이 문서
```

- 브라우저를 빼고 작게 만들려면 `gradlew bundle -PwithoutBrowser`. 이때는 PC에 설치된 Edge → Chrome을 씁니다(Windows 11에는 Edge가 있습니다).
- Playwright가 브라우저를 내려받은 폴더가 기본 위치(`%LOCALAPPDATA%\ms-playwright`)가 아니면 `-PbrowsersDir=경로`로 알려 줍니다.
- 버전은 `build.gradle.kts`의 `version` 한 곳에서 정하고 `result.json`의 `meta.tool_version`에 들어갑니다.

## 3. 실행

배포 폴더를 원하는 곳에 복사한 뒤(경로에 한글·공백이 있어도 됩니다):

```bat
rem 결과 화면
ad-detector-ui.bat

rem 명령줄
ad-detector.bat https://www.example.go.kr
```

결과 파일(`result.json`, `result_extra.json`, `report.html` 등)은 실행 파일이 있는 폴더에 생깁니다.
옵션과 화면 사용법은 [USER_GUIDE.md](USER_GUIDE.md)를 보세요.

개발 중에는 배포본을 만들지 않고 바로 실행할 수 있습니다.

```bat
gradlew run --args="https://www.example.go.kr --out build\run"
gradlew run --args="--ui --out build\run"
```

## 4. 시험

```bat
rem 단위 테스트: 텍스트 처리, 분석기·의미 게이트, URL 규칙, 결과 파일 스키마, 종료 경로
gradlew test

rem 브라우저 통합 테스트: 모의 사이트 전체 점검(정밀도·재현율 1.0), 멈춘 페이지·예산 초과, 선택자, 결과 화면
gradlew browserTest

rem 모의 사이트 품질 측정. 기법별 precision/recall을 출력하고 eval\history.csv 에 한 줄 추가
gradlew evaluate
```

모의 사이트(`mock-site\`)는 `python tools\build_mock_site.py`로 만듭니다. 정답은 요소의 `data-expect` 속성(기법 코드)이고,
속성이 없는 숨김·특수문자 글은 오탐 확인용 대조군입니다. 손으로 열어 보려면:

```bat
gradlew copyRuntimeLibs
java -cp "build\libs-all\*" addetector.eval.StaticSiteServer mock-site 8765
```

### 실사이트 품질 측정

```bat
rem 1) 점검 (운영 사이트는 한 번에 한 곳, 워커 1~2개)
ad-detector.bat https://www.example.go.kr --out docs\runs\example --workers 2

rem 2) 판정표 만들기 → docs\runs\example\review.csv
java -cp "build\libs-all\*" addetector.eval.Evaluate --result docs\runs\example\result.json

rem 3) review.csv 의 verdict 열에 TP / FP 를 적고(놓친 것은 FN 행을 추가), 채점
java -cp "build\libs-all\*" addetector.eval.Evaluate --result docs\runs\example\result.json --truth docs\runs\example\review.csv
```

실사이트 점검 기록(`docs\runs\`)은 외부 사이트의 데이터이므로 저장소에 올리지 않습니다(`.gitignore`).
오탐이 나오면 그 사례를 모의 사이트 대조군과 `AnalyzerTest`의 대조군에 옮겨 회귀를 막습니다.

## 5. 클린 PC 리허설 절차

평가 환경(GPU 없는 Windows 11, 오프라인)과 같은 조건에서 배포본만으로 동작하는지 확인합니다.

1. Java·Node·Playwright가 설치되지 않은 Windows 11 PC(또는 가상 머신)에 `ad-detector` 폴더만 복사합니다.
2. 인터넷을 끊고, 같은 PC나 내부망에 모의 사이트를 띄웁니다.
3. `ad-detector.bat http://<모의 사이트>/`를 실행해 `result.json`이 실행 파일 폴더에 생기는지, 74건이 나오는지 확인합니다.
4. `ad-detector-ui.bat`을 실행해 Edge에서 화면이 열리고 점검·상태 변경·CSV가 되는지 확인합니다.
5. 작업 관리자로 메모리를 봅니다(브라우저 하나 약 650MB + Java 최대 1GB). 부족하면 `--workers 1`.
