# Lyrics Mobile

Android 음악 검색·가사·번역 앱. 제품 요구와 변경 기록은 [PROJECT_NOTES.md](PROJECT_NOTES.md)에 누적합니다.

## 현재 화면

- 찾기: 현재 재생 정보로 카탈로그 후보 조회, 제목/아티스트 검색, 클릭형 곡 목록.
- 곡 상세: 원본/번역/발음 탭, 보관함 저장, YouTube·Apple Music·Spotify 링크.
- 보관함: 최근 200곡과 별도로 유지하는 저장한 곡.
- 주간 추천: 최근 아티스트의 실제 카탈로그 후보 중 최근 기록/이전 추천을 제외. 첫 성공 생성부터 7일 재사용하며 서버 예약 작업은 없습니다. 다음 화면 진입 때 갱신합니다.
- 설정: Gemini/OpenAI (GPT) 제공자 선택·키·모델·연결 테스트·표시 언어·탭·선택적 AI 제목 정리·알림 접근·ACRCloud 준비·데이터 관리.

카라오케/음정 분석/채점은 이번 범위에서 제외했습니다.

## 실행

- `compileSdk` / `targetSdk`: 36. `minSdk`: 21.
- JDK: 이 작업 환경의 JDK 20. 로컬 `gradle.properties`의 JDK 경로는 다른 컴퓨터에 맞게 변경해야 합니다.
- Android Studio는 프로젝트의 AGP/Gradle 버전을 지원하는 버전을 사용합니다.

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-20.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# 실행 중인 에뮬레이터/테스트 기기에서:
./gradlew :app:connectedDebugAndroidTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## OpenAI (GPT) 사용

1. 설정 → AI 제공자 → OpenAI (GPT)를 선택합니다.
2. OpenAI API 키를 입력합니다. Gemini 키와 별도로 암호화 저장됩니다.
3. 기본 모델 `gpt-4.1-mini`를 사용하거나 모델 목록에서 선택한 뒤 API 연결 테스트를 실행합니다. 테스트도 API 사용량을 사용합니다. 목록에 있어도 Responses API를 지원하지 않거나 프로젝트에서 사용이 제한될 수 있습니다.
4. 제공자 선택은 즉시 적용되고, 키·모델은 다른 메뉴로 이동하면 자동 저장됩니다. 표시 언어/표시 항목 등 나머지 옵션은 설정 저장 버튼을 사용합니다.
5. 번역/발음 탭을 선택하면 GPT를 먼저 사용하고, 요청 실패 시 등록된 Gemini 키로 한 번 대체 시도합니다. Gemini를 선택하면 Gemini만 사용합니다. 연결 테스트는 선택한 제공자만 확인합니다.

실제 결과 제공자와 대체 여부를 상세창에 표시합니다. 두 제공자가 모두 실패하면 각각의 원인을 안내합니다. 제공자별 캐시는 구분하며 Gemini 대체 캐시 때문에 GPT 우선순위가 바뀌지 않습니다. 취소와 명시적인 생성 거절/콘텐츠 제한에는 대체 요청하지 않습니다. 대체 요청도 별도 사용량을 소모할 수 있으며, GPT의 응답 대기 실패가 GPT 서버의 처리·과금 취소를 보장하지는 않습니다.

OpenAI Responses API를 사용하며 기존 Gemini 번역·발음 프롬프트와 줄 수 검증을 공유합니다. 잔액/사용 한도, 요청 속도, 인증, 모델 접근, 생성 거절과 잘린 출력을 구분해 안내합니다. 실제 개인 계정의 유료 호출은 개발 검증에 사용하지 않았습니다.

## ACRCloud를 나중에 켜는 방법

현재 `app/build.gradle`의 `ACR_RECOGNITION_ENABLED=false`입니다. 주변 음악 버튼은 준비 안내만 표시합니다. 자격정보가 저장되어도 녹음·요청·과금 호출은 하지 않습니다.

1. ACRCloud에서 Audio & Video Recognition 프로젝트와 음악 카탈로그를 준비합니다. Recorded Audio용 Host, Access Key, Access Secret을 확인합니다.
2. 앱 설정에서 세 값을 입력하고 저장합니다. 형식 확인 버튼은 로컬 검사만 하며 인증 성공을 의미하지 않습니다.
3. `app/build.gradle`에서 다음 한 값을 `true`로 바꾸고 다시 빌드합니다.

```groovy
buildConfigField 'boolean', 'ACR_RECOGNITION_ENABLED', 'true'
```

4. 주변 음악 버튼을 누르면 런타임 마이크 권한을 요청하고 최대 10초 PCM을 실제 WAV 헤더와 함께 전송합니다. 취소/백그라운드 이동 시 작업을 취소합니다. 원음은 파일로 저장하지 않습니다.
5. 결과는 현재곡과 같은 카탈로그 후보 확인 경로로 연결됩니다. 음악 인식이 원곡 판별이나 전체 곡 가사 확보를 보장하지는 않습니다.

직접 연결은 개인/개발 테스트용 준비입니다. 배포 운영자의 공용 Access Secret은 APK에 넣지 말고 서버 중계 구현으로 교체해야 합니다. 실제 ACR 계정의 인증·카탈로그·쿼터·요금 및 실음 인식은 검증하지 않았습니다.

## 데이터와 보안

- `MusicStore`: 앱 내부 `files/ivlyrics_data`. AtomicFile 백업 복구, 스키마 검증, 손상 시 미스로 처리, SHA-256 키, 초기화 세대 번호로 늦은 쓰기 차단.
- 검색 캐시 7일, 가사/AI/제목 정리 30일. 캐시는 최대 20 MiB. 최근 기록/즐겨찾기는 캐시 용량 정리에서 제외합니다.
- AI 키: 곡/녹음 ID, 원문 해시, 언어, 제공자/모델, 프롬프트 버전, 기능 구분. 실패·줄 수 불일치·잘린 출력은 저장하지 않습니다.
- 필요한 번역/발음 탭을 선택할 때만 AI를 요청합니다. 파일 누락 시 필요한 데이터만 재요청합니다.
- 기존 외부 폴더 JSON은 유효한 원본 가사/기록을 가져옵니다. 이전 번역은 원문/프롬프트 검증 정보가 없어 가져오지 않습니다. 이전 파일은 임의 삭제하지 않습니다.
- Gemini/OpenAI/ACR 자격정보는 Android Keystore AES-GCM으로 암호화합니다. Android 5에서는 키의 영구 저장이 지원되지 않으며 평문으로 대체하지 않습니다.
- 자동 클라우드 백업/기기 이전에서 키와 앱 데이터를 제외합니다. Gemini/OpenAI 키는 URL이 아닌 인증 헤더로 보냅니다.
- 데이터 폴더는 앱 안에서 탐색합니다. Android의 앱 전용 폴더 접근 제한을 우회하지 않습니다. ZIP 내보내기는 사용자가 선택한 저장 위치로 곡 데이터만 기록하며 키를 포함하지 않습니다.
- 캐시 초기화는 기록/보관함을 보존합니다. 전체 데이터 초기화도 API 설정은 유지합니다.

## 구조

- `MainActivity`: 화면·권한·기기 링크·파일 내보내기.
- `MusicViewModel`: 화면 재생성 시 상태/작업 유지, 작업 취소와 사용자 상태.
- `MusicRepository`: 카탈로그·가사·AI·추천 및 캐시 조합.
- `MusicStore` / `SecureVault`: 데이터와 자격정보의 별도 저장.
- `OperationScope`: 실제 OkHttp 호출/응답 읽기 취소.
- `InternalPlaybackListenerService`: 공개 MediaSession과 미디어 토큰만 사용. 일반 알림 title/body를 곡으로 해석하지 않음.
- `AiFailure`: API 코드·네트워크·콘텐츠 제한을 사용자가 해결 가능한 안내로 변환.
- `RecognitionController` / `AudioSampleRecorder` / `AcrCloudClient`: 비활성 상태의 주변 음악 연결 준비.

## 외부 서비스와 한계

현재곡 메타데이터를 주지 않는 플레이어는 수동 검색을 사용해야 합니다. 제목 정리 AI는 선택 기능이며, 출력은 카탈로그 검색어로만 씁니다. 커버/라이브/리믹스 표기가 확인되면 원곡 후보를 다시 찾고 사용자가 확인합니다. 표기되지 않은 커버를 자동으로 확정할 수는 없습니다. 카탈로그의 정확한 Apple Music URL만 직접 곡 링크로 사용하며, 나머지는 검색 링크임을 표시합니다. 앱 실행 실패 시 웹으로 엽니다.

원문 가사는 LRCLIB에서 조회하며, AI로 없는 가사를 만들어내지 않습니다. 실제 계정별 번역 및 가사 제공 여부는 공급자·곡·네트워크에 따라 다릅니다. 제공처 데이터의 저장·표시·내보내기 조건과 배포 권리는 별도 확인이 필요합니다.
