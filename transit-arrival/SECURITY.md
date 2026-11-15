# 보안 설계와 위협 모델

이 문서는 앱의 **제작 → 변경 → 설치 → 사용** 각 단계에서 생길 수 있는 위협과, 그에 대한 대응이 코드·설정의 어디에 있는지를 적습니다.
"대응 없음/사용자 몫"인 항목도 숨기지 않고 적었습니다.

## 0. 무엇이 기기 밖으로 나가는가

| 데이터 | 기기 밖으로? | 근거 |
|---|---|---|
| 현재 위치(좌표) | **나가지 않음** | 요청 URL을 만드는 유일한 클래스 `core/Endpoints`에 좌표를 받는 메서드가 없음. 단위 테스트 `EndpointsTest.noUrlCarriesCoordinates`, `builderMethodsAcceptOnlyStrings`가 이를 고정. 위치를 읽는 `Locator`는 네트워크 코드를 참조하지 않음 |
| 등록한 정류장의 좌표 | 나가지 않음 | 위와 같음. 기기 안 거리 계산(`core/Geo`)에만 사용 |
| 조회 대상 정류장 ID / 역 이름 | **나감(필수)** | 도착 정보를 받으려면 어느 정류장인지는 알려야 함 |
| 본인 인증키 | 나감(필수) | 공공 API 인증. HTTPS로만 전송 |
| 검색어(정류장 이름) | 나감 | 정류장을 등록할 때만 |
| 기기 모델·OS 빌드 | 나가지 않음 | 기본 User-Agent 대신 고정 문자열 사용(`SecureHttp`) |
| IP 주소 | 나감(불가피) | 인터넷 통신의 본질. 아래 "남는 위험" 참고 |

통신 상대는 `apis.data.go.kr`, `swopenapi.seoul.go.kr`, `ws.bus.go.kr` 세 곳뿐이며 코드에 허용 목록으로 박혀 있습니다.

### 남는 위험 (없앨 수 없는 것)

- **간접 위치 추정**: "가장 가까운 정류장" 모드에서는 내가 지금 어느 등록 정류장 근처인지가 "어떤 정류장을 조회했는가"로
  API 서버(정부 기관)에 드러납니다. 좌표는 아니지만 정류장 단위의 위치 정보입니다. 이것이 싫다면 "내가 선택한 정류장" 모드를 쓰세요.
- **IP 주소와 조회 시각**은 API 서버 로그에 남습니다.
- **OS 수준의 위치 처리**: "GPS만 사용"을 끄면 Android의 네트워크 측위(통신사·OS 제공자)가 동작할 수 있고, 이는 앱이 통제할 수 없습니다. 기본값은 켜짐입니다.
- **잠금화면 표시**는 기능의 목적상 남이 내 화면을 보면 정류장 이름이 보입니다. 옵션으로 내용을 가릴 수 있습니다.

## 1. 제작(빌드) 단계

| 위협 | 대응 | 위치 |
|---|---|---|
| 악성/변조된 라이브러리(공급망) | 앱 실행 코드의 외부 의존성 **0개**. 테스트 전용 2개(JUnit, org.json)는 APK에 미포함 | `app/build.gradle` |
| 빌드 도구·플러그인 변조 | 모든 빌드 의존성을 SHA-256으로 고정, 불일치 시 빌드 실패 | `gradle/verification-metadata.xml` |
| Gradle 자체 변조 | 배포본 SHA-256 고정(공식 릴리스에서 직접 계산해 확인), CI에서 wrapper jar 검증 | `gradle/wrapper/gradle-wrapper.properties`, 워크플로 |
| 의존성 혼동(같은 이름의 가짜 패키지) | 저장소를 3곳으로 고정하고 Google 저장소는 그룹 필터 적용, 모듈의 임의 저장소 추가 금지 | `settings.gradle` |
| CI 액션 태그 바꿔치기 | 모든 액션을 커밋 SHA로 고정, 권한 `contents: read`, 시크릿 미사용, `persist-credentials: false` | `.github/workflows/transit-arrival.yml` |
| 서명 키·비밀번호 유출 | 저장소에 넣지 않음. `keystore.properties`, `*.jks` 등은 `.gitignore` | `.gitignore`, `app/build.gradle` |
| 인증키가 소스/빌드 산출물에 박힘 | 키는 실행 중에 사용자가 입력. 소스·리소스·BuildConfig 어디에도 없음 | — |
| 디버그 기능이 릴리스에 남음 | 릴리스는 `debuggable false`, R8 축소·난독화, 로그 출력 코드 없음 | `app/build.gradle` |

## 2. 변경(유지보수) 단계

| 위협 | 대응 |
|---|---|
| 수정하다가 위치를 요청에 싣게 됨 | 단위 테스트가 실패하도록 불변식을 테스트로 고정(위 0번) |
| 수정하다가 새 통신 대상이 생김 | `Endpoints.requireAllowed`를 URL 생성 시와 전송 직전 두 번 검사. 테스트 `rejectsNonAllowlistedOrInsecureUrls` |
| 의존성 추가·업데이트 시 변조본 유입 | 해시가 등록되지 않은 산출물은 빌드가 거부. 갱신 절차는 아래 |
| 권한이 슬그머니 늘어남 | 매니페스트에 권한 7개와 사유 주석. 리뷰 시 이 파일의 diff만 보면 됨 |

의존성을 바꿔야 할 때:

```bash
./gradlew --write-verification-metadata sha256 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
git diff gradle/verification-metadata.xml   # 늘어난 항목이 의도한 것인지 사람이 확인
```

## 3. 설치 단계

| 위협 | 대응 |
|---|---|
| 전달 중 APK 바꿔치기 | 본인 PC에서 빌드 → `adb install`로 직접 설치 권장. 파일로 옮길 때는 SHA-256 대조 |
| 가짜 업데이트로 덮어쓰기 | Android는 같은 서명 키의 APK만 업데이트로 받음 → **본인 키로 서명한 릴리스 빌드**를 쓰면 타인이 만든 APK로 교체 불가 |
| 출처 불명 앱 설치 허용 상태 방치 | 설치 후 "알 수 없는 앱 설치"와 USB 디버깅을 다시 끄기(사용자 몫) |
| 과도한 권한 | 위치(포그라운드만)·알림·인터넷·포그라운드 서비스뿐. 백그라운드 위치·저장소·연락처·전화 없음 |

## 4. 사용 단계

| 위협 | 대응 | 위치 |
|---|---|---|
| 통신 도청·변조 | HTTPS 전용, 평문 금지(매니페스트 + 네트워크 보안 설정 + 코드 3중), 실패 시 평문으로 내려가지 않음 | `network_security_config.xml`, `SecureHttp` |
| 가로채기 프록시·사용자 설치 인증서 | 시스템 인증서만 신뢰 | `network_security_config.xml` |
| 리다이렉트로 키가 다른 서버에 전달 | 리다이렉트를 따라가지 않고 차단 | `SecureHttp` |
| 악의적/깨진 서버 응답 | 응답 512KB·시간 제한, 방어적 파싱, 표시 문자열 길이 제한·제어문자 제거, 예외는 안전한 문구로 치환 | `SecureHttp`, `Parsers` |
| 입력값을 통한 URL 조작 | 키·코드·ID 형식 검증(정규식) 후 인코딩. 테스트 `rejectsInjectionInInputs` | `Endpoints` |
| 다른 앱이 내 컴포넌트를 호출 | 외부 공개는 런처 화면 1개뿐(전달된 인텐트 데이터 미사용). 서비스·위젯 수신기는 `exported=false` | `AndroidManifest.xml` |
| PendingIntent 가로채기 | 전부 명시적 대상 + `FLAG_IMMUTABLE` | `ArrivalWidget`, `LiveService` |
| 기기 분실·저장 파일 유출 | 인증키·정류장·설정·마지막 화면 전부 AES-256-GCM 암호화, 키는 Android Keystore(추출 불가). 항목 이름을 AAD로 결합 | `Vault` |
| 백업·기기 이전으로 유출 | `allowBackup=false` + 추출 규칙에서 전 영역 제외 | 매니페스트, `data_extraction_rules.xml` |
| 화면 캡처·최근 앱 미리보기·화면 녹화 | 설정 화면과 입력 대화상자에 `FLAG_SECURE` | `MainActivity` |
| 화면 덮어씌우기(탭재킹) | `filterTouchesWhenObscured` | `activity_main.xml` |
| 키보드 앱이 입력을 학습·동기화 | 키 입력은 비밀번호 유형, 개인화 학습 제외 플래그 | `MainActivity.prompt` |
| 로그·오류 메시지로 키/URL 노출 | 앱에 로그 출력 코드 없음. 예외 메시지에 URL을 싣지 않음 | `SecureHttp`, `TransitException` |
| 위치 기록 축적 | 좌표를 저장하지 않음. 남는 것은 "마지막으로 고른 정류장이 몇 번째인지"뿐 | `Engine`, `Config` |
| 배터리·호출 한도 소진 | 실시간 갱신은 시간 제한 후 자동 종료, 최소 30초 간격 | `LiveService` |
| 데이터 완전 삭제 필요 | "모든 데이터 삭제"가 암호문과 Keystore 키를 모두 파기 | `Vault.wipe` |

## 5. 대응하지 않은 것

- **루팅/탈옥된 기기, 악성 접근성 서비스, OS 자체의 취약점**: 앱 수준에서 막을 수 없습니다.
- **인증서 고정(pinning)**: 하지 않았습니다. 공공기관 서버 인증서가 예고 없이 바뀌면 앱이 멈추고, 사이드로드 앱은 즉시 업데이트하기 어렵기 때문입니다. 대신 시스템 인증서만 신뢰합니다.
- **화면을 직접 엿보는 것**: 잠금화면 표시 옵션으로 줄일 수 있을 뿐입니다.
- **실기기 동적 시험·침투 시험**: 수행하지 못했습니다. 정적 검사(lint)와 단위 테스트까지입니다.
