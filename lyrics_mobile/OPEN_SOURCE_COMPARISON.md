# 재생 앱 가사 접근과 오픈소스 구조 비교

조사일: 2026-11-03 (한국 시간). 공개 저장소를 /tmp/lyrics-comparison에 shallow clone하여 정적 코드 분석. 두 프로젝트 빌드/실재생/개인 인증 토큰 호출은 수행하지 않음. 현재 앱 기능 코드는 변경하지 않음.

## 조사 스냅샷

- ivLis-Studio/ivLyrics-Android: `62d4357b86205181939f263f466944c7a7edfc69`
- MelismaApp/melisma: `18b0c740aaa248c50b82c00bd4c78608a87f11ba`
- README와 최신 코드가 다를 수 있으므로 등록된 제공처와 선택 알고리즘은 코드 기준.

## 공식 API와 내부 경로의 구분

Android MediaSession/MediaMetadata는 곡명, 가수, 앨범, 커버, 재생 상태/위치 같은 정보의 연결 지점이다. 다른 앱이 화면에 표시한 전체 가사를 자동 공유하는 범용 표준 가사 필드는 확인되지 않는다. 커스텀 메타데이터를 제공하는 앱은 별도 협약이 가능하지만 모든 플레이어에서 쓸 수 있는 계약은 아니다.

- Spotify: 공개 Web API는 카탈로그/재생 제어 등의 API이며 공식 참조에서 가사 본문 endpoint는 확인되지 않음. Melisma는 일반 Web API가 아닌 spclient.wg.spotify.com의 color-lyrics/v2와 웹 세션 토큰을 사용하는 선택 제공처를 구현.
- Apple Music: MusicKit의 hasLyrics는 가사 존재 여부 boolean이지 가사 문자열이 아님. Melisma는 amp-api.music.apple.com의 catalog songs 아래 syllable-lyrics/lyrics 관계를 별도로 요청하며 Authorization + Media-User-Token을 사용. 일반 서드파티용 공개 가사 API 지원을 의미하지 않음.
- YouTube: 공식 Captions download는 동영상 편집 권한이 필요. 타인의 모든 음악 영상/YouTube Music 가사를 내려받는 범용 공개 API로 쓸 수 없음. 자막은 노래 원문 가사와도 다름.
- 내부/비공개 경로는 기술적 사용 가능성과 안정성/공식 지원/배포 이용 권한을 별도 판단해야 함. 인증 토큰 만료·접근 거부·응답 형식 변경에 취약. 이번 조사에서 사용자 쿠키/토큰을 요청하거나 읽지 않음.

공식 자료:
- https://developer.android.com/reference/android/media/MediaMetadata
- https://developer.spotify.com/documentation/web-api
- https://developer.apple.com/documentation/musickit/song/haslyrics
- https://developer.apple.com/musickit/
- https://developers.google.com/youtube/data/reference/rest/v3/captions/download

## ivLyrics Android

- app / shared / spotify-module 세 모듈. 독립 앱과 Spotify LSPosed/LSPatch 모듈이 shared 엔진·설정을 공유.
- 독립 앱은 NowPlayingService에서 MediaController의 position, updateTime, speed, state를 TrackSnapshot으로 전달.
- LyricsRepository는 Spotify 카탈로그/ISRC와 ivLyrics OpenDB/community sync-data를 활용. 실제 가사 제공처는 LRCLIB, Lyrically(Paxsenix), LyricsPlus, Unison.
- LyricsProviderSelectionPlan에 따라 제공처 우선 또는 karaoke/synced/plain 품질 우선 순서를 선택. 같은 제공처를 중복 호출하지 않고 오류 후 다음 후보 검토.
- SyncDataApplier로 별도 싱크 데이터 적용. LyricsView는 Java 커스텀 View/Canvas 기반으로 행·단어·글자 타이밍과 렌더링을 담당. 단순히 특별한 외부 UI 라이브러리를 붙여서 생긴 기능이 아님.
- Spotify 모듈은 Xposed hooks와 native metadata bridge를 사용하는 별도 제품. 일반 Android 공식 API로 Spotify 내부 화면을 바꾸는 방식이 아님.
- 커뮤니티 데이터·제공처 정책·모듈 호환성에 의존하므로 모든 곡에서 정밀 싱크를 보장하지 않음.

코드:
- ARCHITECTURE.md
- shared/src/main/java/kr/ivlis/ivlyricsandroid/NowPlayingService.java
- shared/src/main/java/kr/ivlis/ivlyricsandroid/LyricsRepository.java
- shared/src/main/java/kr/ivlis/ivlyricsandroid/LyricsProviderSettings.java
- shared/src/main/java/kr/ivlis/ivlyricsandroid/LyricsProviderSelectionPlan.java
- shared/src/main/java/kr/ivlis/ivlyricsandroid/SyncDataApplier.java
- shared/src/main/java/kr/ivlis/ivlyricsandroid/LyricsView.java

## Melisma

- 범용 MediaSessionRepository가 여러 앱의 세션을 선택하고 곡 식별·재생 위치·속도·상태를 전달. 재생 앱 화면의 가사를 읽어오는 구조가 아님.
- MelismaApp.kt에 로컬 파일, 캐시 서버, Apple Music, lrc.red, AMLL TTML, Spotify, NetEase, Musixmatch, LRCLIB 제공처 등록. 설정 및 인증 여부에 따라 실제 호출 대상이 달라짐.
- LyricsRepository.query는 coroutine async/awaitAll + 제공처별 timeout으로 활성 제공처 병렬 조회. 가사 없음과 서버/네트워크 실패를 구분하여 캐시 판단.
- TimingSanity로 가짜 단어 타이밍/곡 길이를 넘는 시간표 등의 품질을 검사. 이후 시간 정밀도·분량·사용자 순서 등을 기준으로 선택하며, 로컬 파일/곡별 수동 선택 경로도 존재.
- 공통 LyricsDocument/LyricLine 형태로 정규화. 원문 취득(fetchBase)과 번역·발음 파생(present)을 분리하므로 표시 옵션 변경으로 가사 서버를 매번 조회하지 않음.
- Compose + Canvas 및 withFrameNanos 기반의 재생 프레임 처리. 수동 읽기/자동 추적 복귀 등 제스처 상태를 별도로 관리.
- SpotifyLyricsProvider: color-lyrics/v2, Spotify track ID 및 웹 토큰 필요. 인증 실패와 곡 가사 없음 구분, 선택적 토큰 갱신 경로 존재. 코드의 토큰 관련 주석을 실시간 서버 동작 보증으로 해석하지 않음.
- AppleMusicProvider: 웹 서비스 토큰 2종과 구독 접근을 전제로 syllable-lyrics 우선, lyrics 대체. TtmlParser로 원문/단어 시각/부가 정보를 처리.
- 로그인 없이 쓰는 제공처와 사용자의 별도 인증이 필요한 제공처를 혼동하면 안 됨. Musixmatch/NetEase 등도 어댑터 구현이 있다는 것과 공식 계약형 API 사용권은 다른 문제.

코드:
- app/src/main/java/com/melisma/app/MelismaApp.kt
- app/src/main/java/com/melisma/app/media/MediaSessionRepository.kt
- app/src/main/java/com/melisma/app/lyrics/LyricsRepository.kt
- app/src/main/java/com/melisma/app/lyrics/provider/SpotifyLyricsProvider.kt
- app/src/main/java/com/melisma/app/lyrics/provider/AppleMusicProvider.kt
- app/src/main/java/com/melisma/app/ui/lyrics/LyricsView.kt

## 현재 앱과의 차이

| 항목 | 현재 앱 | ivLyrics Android | Melisma |
|---|---|---|---|
| 원문 확보 | LRCLIB 중심, 보조 제공처 미연결 | 4개 제공처와 ivSync 데이터 | 여러 제공처 병렬 조회·품질 선별 |
| 곡 식별 | 재생 텍스트 → iTunes/별칭 검색 | Spotify 식별자·ISRC 활용 | 플레이어 식별자/ISRC·메타데이터 |
| 재생 위치 | 상태를 읽지만 위치/속도 미전달 | 재생 snapshot | 재생 snapshot/세션 선택 |
| 시간표 | plain/synced 문자열 저장, 재생 렌더링 없음 | 시간표 + 커뮤니티 정밀 싱크 | LRC/TTML 등 공통 모델 |
| 가사 UI | TextView/ScrollView 중심 | 전용 Canvas View | Compose/Canvas 전용 렌더러 |
| AI 역할 | 번역·발음 + 동의형 미검증 가사 요청 | 확보된 가사의 번역/발음 등 | 원문 확보와 파생 처리 분리 |
| 503 복원력 | 캐시 없으면 LRCLIB 실패 영향 큼 | 다른 제공처 후보 | 다른 제공처 응답 선별 |

현재 InternalPlaybackListenerService.java:57 publish()는 제목/가수/앨범/길이를 SongInfo로 만들고 곡 identity 변경 시에만 알림. getPosition/getPlaybackSpeed/getLastPositionUpdateTime을 화면에 전달하지 않는다. MusicRepository.java는 recoveryProvider 기본값 UNAVAILABLE, 원문은 LrcLibClient에 의존한다. 이것이 현재의 구조적 병목이며, UI 색상 수정이나 AI 추가만으로 해소되지 않는다.

## 권장 방향 (이번에는 미구현)

1. MediaSession 재생 snapshot과 세션/곡 식별자를 보존. 원문 시간표가 확보되면 카탈로그 재검색을 항상 거치지 않도록 곡 식별 경로 정리.
2. 공통 LyricsDocument를 원문/행 시각/단어 시각/출처/언어/검증 상태로 설계. AI 미검증 후보는 독립 상태 유지.
3. 제공처 어댑터와 제한된 병렬/순차 대체, 시간초과 예산, 가사 없음과 서버 장애 구분, 사용자 제공처 선택 추가.
4. 줄 단위 현재 가사 강조·자동 스크롤, 수동 스크롤 우선 및 현재 줄 복귀, 곡/기기별 오프셋. 단어 타이밍이 있는 곡에만 정밀 채워짐 효과.
5. 공식/공개 제공처를 기본으로 하고 Spotify/Apple 웹 세션 연결은 위험·유지보수 비용을 설명한 별도 선택 기능으로 검토. 계정 토큰을 공개 로그나 진단 파일에 남기지 않음.
6. 코드를 직접 이식하기 전 라이선스 확인. Melisma의 루트 LICENSE는 AGPL-3.0. ivLyrics Android 스냅샷에서는 루트 프로젝트 LICENSE를 확인하지 못했으므로 공개 저장소라는 이유만으로 자유로운 복사·배포를 가정하지 않음. 가사 데이터의 이용 조건도 코드 라이선스와 별개로 확인 필요.
