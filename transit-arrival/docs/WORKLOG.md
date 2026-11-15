# 작업 로그 (A–Z)

이 앱을 만드는 동안 한 일, 내린 결정, 실패와 우회, 검증 결과를 시간순으로 빠짐없이 적은 기록입니다.
시각은 UTC, 작업 환경은 클라우드의 일회용 리눅스 컨테이너입니다(실제 Android 기기·에뮬레이터 없음).
실패한 시도와 확인하지 못한 것도 그대로 적었습니다.

## 요약

| 항목 | 결과 |
|---|---|
| 단위 테스트 | 17개 전부 통과 (`EndpointsTest` 6, `ParsersTest` 9, `GeoStopTest` 2) |
| 정적 검사(lint) | 오류 0, 경고 8(표시 문자열·버튼 스타일 등 외형 관련, 보안 무관) |
| 빌드 | 디버그 APK, 릴리스 APK(미서명, R8 축소) 생성 성공 |
| 의존성 해시 검증 | 켠 상태로 빌드 성공, 해시를 일부러 바꾸면 빌드 거부 확인 |
| 실제 서버 시험 | TAGO: HTTPS·오류 응답 처리 확인. 정상 데이터 응답은 인증키가 없어 미확인 |
| 서울 지하철·서울 버스 | 개발 환경에서 서버 연결 불가 → **미검증** |
| 실기기/에뮬레이터 실행 | **수행하지 못함** |

## 1일차 — 2026-11-11

**11:42 요구사항 접수.** 잠금화면/홈 화면에서 지정한 버스·지하철의 도착 시각을 실시간으로 보는 앱.
조건: 전 과정의 보안 위협 대비, 기기 밖으로 데이터가 새지 않는 폐쇄형, 전 과정 로그.

**11:43 환경 조사.** 저장소 `seohaeseon/utils`는 작은 JS 유틸 패키지(파일 4개)이고 Android 코드는 없음.
작업 브랜치 `claude/android-transit-arrival-app-seje7c`. JDK 21, Gradle 8.14.3은 있으나 Android SDK 없음.

**11:43 네트워크 접근 점검.** Android SDK 서버(`dl.google.com`)와 공공 API 호스트 전부 컨테이너 네트워크 정책으로 차단(403).
`maven.google.com`은 200이었으나 나중에 확인하니 차단된 `dl.google.com`으로 리다이렉트되는 것이었음.

**11:44~ API 명세 조사.**
- 웹 검색 4건: 공식 명세 본문은 얻지 못함.
- 공공데이터포털/서울 열린데이터광장 페이지 직접 열람 시도 2건: 호스트 차단으로 실패.
- 대안: PyPI의 공개 래퍼 라이브러리 2종(`UnOffical-TAGO-API` 0.14.0, `seoul-metro-realtime` 0.2.0)을
  격리 폴더에 내려받아 **실행하지 않고 소스만 읽어** 엔드포인트·파라미터·응답 필드명을 확인.
  - TAGO: `/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList`(cityCode, nodeId),
    `/BusSttnInfoInqireService/getSttnNoList`(cityCode, nodeNm), `/BusRouteInfoInqireService/getCtyCodeList`.
    응답 필드 `routeno, arrtime, arrprevstationcnt, nodeid, nodenm, nodeno, gpslati, gpslong, citycode, cityname`. 오류 코드표(30=미등록 키 등).
  - 서울 지하철: `/api/subway/{키}/json/realtimeStationArrival/{시작}/{끝}/{역명}`, 응답 `realtimeArrivalList`,
    필드 `subwayId, trainLineNm, barvlDt, recptnDt, arvlMsg2`, 호선 코드표, `INFO-200`=데이터 없음.
- 서울 버스(`ws.bus.go.kr`)는 교차 확인할 자료를 얻지 못해 기억에 의존(→ 미검증으로 표시).
- 실수 기록: PyPI 접근 확인 중 관계없는 패키지 `xx`의 다운로드 명령을 한 번 실행함. 저장 경로가 `/dev/null`이라 저장·실행된 것은 없음.

**설계 결정(이 시점).**
1. **위치 비유출을 구조로 보장.** "주변 정류장 검색" API는 내 좌표를 서버로 보내므로 쓰지 않는다.
   대신 사용자가 정류장을 이름으로 검색해 등록해 두고, 등록된 정류장 중 가장 가까운 것을 **기기 안에서** 계산한다.
2. **외부 라이브러리 0개.** 공급망 위험을 없애기 위해 AndroidX·Google Play 서비스·HTTP/JSON 라이브러리를 쓰지 않고
   Android 프레임워크만 사용한다(Java). 위치도 Play 서비스가 아닌 OS `LocationManager`.
3. **잠금화면 표시 방식.** 휴대폰의 잠금화면 위젯은 기기·버전에 따라 지원이 갈리므로,
   모든 기기에서 되는 "진행 중 알림"을 주 수단으로 하고 위젯에는 잠금화면 카테고리도 선언해 둔다.
4. **실시간 갱신은 사용자 시작 + 시간 제한.** 상시 백그라운드 동작과 백그라운드 위치 권한을 피한다.
5. **인증키는 사용자 본인 것을 실행 중 입력.** 소스·APK에 넣지 않는다.

**환경 변경 요청.** 빌드가 불가능하므로 이 저장소 전용 클라우드 환경 `utils-android-build`를 제안.
허용 호스트 4개(`dl.google.com`, `apis.data.go.kr`, `swopenapi.seoul.go.kr`, `ws.bus.go.kr`)만 추가. 사용자 승인 후 적용됨.
(조사용 하위 에이전트가 문서 호스트 추가도 권했으나, 꼭 필요한 최소 범위가 아니어서 넣지 않음.)

## 2일차 — 2026-11-15

**10:29 재개, 접근 재확인.**
- `dl.google.com` 접근 가능.
- `apis.data.go.kr`: HTTPS(TLS 1.3, 인증서 `*.data.go.kr`) 연결 성공. 가짜 키로 호출 시 HTTP 403 + JSON
  `{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"returnReasonCode":"30"…}}}` → 오류 형식 실측.
- `swopenapi.seoul.go.kr`, `ws.bus.go.kr`: 443 포트에서 TLS 핸드셰이크 중 연결 리셋. HTTP(80)는 프록시 경유 불가로 시험 못 함.
  HTTPS 미지원인지 해외 IP 차단인지 **판정 불가**. → 결정: 두 호스트도 HTTPS 전용으로만 구현하고(실패 시 평문 전환 없음) 문서에 미검증 명시.

**10:31 Android SDK 설치.** 명령줄 도구 `commandlinetools-linux-16111833`을 받아 저장소 목록의 SHA-1과 대조(일치) 후 설치.
`platforms;android-36`, `build-tools;36.1.0`, `build-tools;37.0.0`.

**10:33 Gradle 구성.**
- 처음엔 최신(AGP 9.5.1 + Gradle 9.9.1)을 시도했으나 Gradle 배포 서버(`downloads.gradle.org`)가 차단되어 실패.
  → 컨테이너에 있는 Gradle 8.14.3 + AGP 8.13.2(8.x 최신 안정판)로 결정.
- Gradle 8.14.3 배포본을 공식 GitHub 릴리스(`gradle/gradle-distributions`)에서 받아 SHA-256 계산:
  `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531` → wrapper에 고정.
- 안전장치 발동 기록: 임시 폴더를 비우려던 `rm -rf *`가 도구의 안전 검사에 막힘(정상 동작). 삭제 없이 새 폴더를 쓰도록 변경.
- `settings.gradle`: 저장소 3곳 고정, Google 저장소 그룹 필터, 모듈의 저장소 추가 금지.

**10:35~10:40 코드 작성.**
- 코어(순수 Java, `core/`): `Stop`, `Arrival`, `Geo`(거리), `Endpoints`(URL 생성·허용 목록·입력 검증),
  `SecureHttp`(HTTPS GET, 리다이렉트 차단, 크기·시간 제한, 고정 User-Agent), `Parsers`(방어적 파싱), `Transit`(필터·정렬).
- Android: `Vault`(Keystore AES-256-GCM 저장소), `Config`, `Snapshot`, `Locator`(위치), `Engine`(갱신),
  `ArrivalWidget`(위젯), `LiveService`(실시간 포그라운드 서비스 + 잠금화면 알림), `MainActivity`(설정 화면).
- 리소스·매니페스트: 평문 통신 금지, 시스템 인증서만 신뢰, 백업·기기 이전 제외, 서비스·수신기 비공개.
- 단위 테스트 17개 작성(허용 목록, 좌표 미포함 불변식, 입력 주입 거부, 파서 정상/오류/악의적 입력, 거리·필터).

**10:41~10:55 첫 빌드 — 의존성 다운로드 문제.**
- Maven Central이 이 컨테이너의 공유 IP에 요청 제한(HTTP 429, 간헐적 404)을 걸어 플러그인 의존성 41개가 누락.
- Gradle Plugin Portal은 jar를 차단된 호스트로 넘겨 실패.
- 우회: **컨테이너 전용** 초기화 스크립트(`~/.gradle/init.d/mirror.gradle`, 저장소에 커밋하지 않음)로 Maven Central의
  공식 미러 `repo1.maven.org`를 우선 사용하고, 간격을 두고 재시도. 여러 차례 재시도 끝에 전부 내려받음.
  (어느 경로로 받았든 이후 SHA-256 고정 파일로 내용이 검증됨.)
- 그 사이 `javac`로 코어 코드만 먼저 컴파일 점검(통과).

**10:55 단위 테스트 통과(17/17).**

**10:56 lint 오류 2건.** `Locator`의 측위 호출에 권한 확인/`SecurityException` 처리가 같은 메서드 안에 없다는 지적.
→ 권한 재확인과 예외 처리 추가. 함께 정리: 데이터 삭제의 `commit()` 사용 사유 명시, 런처 아이콘 단색 레이어 추가,
아이콘 폴더 이름 정리, 테스트용 org.json 최신판(20260814)으로 갱신.

**10:58 전체 빌드 성공.** 디버그·릴리스 APK 생성. APK 점검 결과:
- 권한 7개(인터넷, 위치 2, 알림, 포그라운드 서비스 3)뿐. 라이브러리가 끼워 넣은 권한 없음.
- 외부 공개 컴포넌트는 `MainActivity` 하나. `LiveService`, `ArrivalWidget`은 `exported=false`.
- `allowBackup=false`, `usesCleartextTraffic=false`, 릴리스는 debuggable 아님.
- 릴리스 APK 약 35KB, 파일 15개(dex 1개 + 리소스).

**11:00 실제 서버 대상 시험**(컴파일된 코어 클래스를 PC의 JVM에서 실행):
- TAGO 3개 엔드포인트 + 가짜 키 → "등록되지 않은 인증키입니다. (코드 30)"으로 정상 변환.
- 허용 목록 밖 주소(`example.com`)와 `http://` 주소 → 전송 전에 차단.
- 서울 지하철/버스 → "네트워크에 연결할 수 없습니다"(위의 연결 리셋 때문. 평문 전환 없음 확인).

**11:02 릴리스 APK에서 git 커밋 정보 제거**(`vcsInfo` 비활성화).

**11:02~11:05 의존성 해시 고정.** `--write-verification-metadata sha256`으로 `gradle/verification-metadata.xml` 생성(구성요소 286개).
- 중간 실패: 아이콘 폴더 이름 변경 뒤 증분 빌드가 리소스를 못 찾음 → 클린 빌드로 해결.
- Windows/macOS에서 빌드할 때 필요한 `aapt2` 두 파일은 리눅스 빌드에서 받지 않으므로 직접 내려받아 해시를 계산해 추가.
- 검증을 켠 채 오프라인 클린 빌드 성공.
- 변조 탐지 시험: 해시 한 글자를 바꾸자 "Dependency verification failed"로 빌드 거부됨을 확인하고 원복.

**11:07 문서 작성.** `README.md`(기능·키 발급·빌드·설치·사용·한계), `SECURITY.md`(단계별 위협 모델), 이 로그.

**11:06 CI 구성.** `.github/workflows/transit-arrival.yml`: 액션 4개를 커밋 SHA로 고정(SHA는 각 저장소의 태그를 직접 조회해 확인),
권한 `contents: read`, wrapper 검증 → 테스트 → lint → 디버그/릴리스 빌드 → 체크섬 → 디버그 APK 업로드.

**11:08 커밋.** 작업 브랜치에 커밋 1건(파일 49개: 앱 48개 + CI 워크플로, 루트 README에 안내 한 단락 추가).

**11:09 푸시 실패(미해결).** `git push`가 HTTP 403으로 거부됨: "Claude doesn't have GitHub access to seohaeseon/utils".
원격 읽기(fetch, ls-remote)는 되지만 쓰기 권한이 없음. 4회 재시도 모두 동일. 쓰기 권한으로 저장소를 다시 연결하는 시도는
세션의 권한 정책에 의해 거부됨. 이는 사용자가 GitHub 연결/앱 설치를 해야 풀리는 문제라 우회하지 않음.
- 결과: **원격 저장소에는 아직 아무것도 올라가지 않았고, GitHub Actions(CI)도 실행되지 않았음.** CI 워크플로는 작성만 되고 실행 검증은 못 함.
- 조치: 작업물 보존을 위해 커밋 전체를 담은 git 번들과 소스 zip을 만들어 사용자에게 파일로 전달.

## 산출물 체크섬 (이 컨테이너에서 빌드한 것, 참고용)

```
8ed2a7baebf45436601552f3d39028e2940aeb4b9c6377fb45e1c0629b6e5892  app-release-unsigned.apk
b13158129c0f843193ebb423ba66738b804f4506e471cdeb6a122f1f73c2dde3  app-debug.apk
```

빌드 환경(JDK·OS)이 다르면 값이 달라질 수 있습니다. 이 APK들은 저장소에 올리지 않았습니다.

## 하지 못한 것 / 확인이 필요한 것

1. 실기기·에뮬레이터 실행 시험(위젯 배치, 알림 표시, 권한 대화상자, 포그라운드 서비스 시작 조건, 화면 레이아웃).
2. 유효한 인증키로 받은 정상 응답의 실제 형식 대조(TAGO는 래퍼 소스 기준, 서울 버스는 기억 기준).
3. 서울 지하철·서울 버스 서버의 HTTPS 지원 여부.
4. 본인 서명 키로 서명한 릴리스 APK 생성(키는 사용자만 가져야 하므로 의도적으로 하지 않음).
5. 원격 저장소 푸시와 GitHub Actions 실행(GitHub 쓰기 권한 없음).
