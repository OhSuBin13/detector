# 함께 배포하는 제3자 소프트웨어

| 구성 요소 | 용도 | 라이선스 |
|---|---|---|
| Playwright for Java (driver 포함) | 브라우저 제어 | Apache License 2.0 |
| Node.js (`driver\node.exe`) | Playwright 드라이버 실행 | MIT 등 (`driver\LICENSE`) |
| Chromium headless shell (`browsers\`) | 페이지 렌더링 | BSD 3-Clause 등 |
| picocli | 명령줄 옵션 처리 | Apache License 2.0 |
| Jackson (core, databind, annotations) | JSON 읽기·쓰기 | Apache License 2.0 |
| Gson | Playwright가 사용 | Apache License 2.0 |
| SLF4J (api, simple) | 로그 | MIT |
| OpenJDK 런타임 (`runtime\`) | Java 실행 환경 | GPL v2 + Classpath Exception (`runtime\legal\`) |

각 구성 요소의 라이선스 전문은 해당 폴더 또는 jar 안의 `META-INF`에 들어 있습니다.
