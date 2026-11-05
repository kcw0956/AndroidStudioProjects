# 검색·원문 가사 조사 (2026-10-30)

> 아래 본문은 최초 조사 시점 기록이다. 당시 '예정/상위 작업 필요' 사항은 후속 통합에서 반영했다. 현재 상태는 아래 후속 통합 및 `IMPROVEMENT_REPORT.md`를 우선한다.

개인 키 없이 공개 iTunes Search API 및 LRCLIB 요청으로 확인했다. 아래 숫자는 이번 조회 표본이며 카탈로그 전체의 존재 여부를 뜻하지 않는다. 가사 본문은 기록하지 않는다.

## 재현한 원인

- 기존 SongSearchClient는 KR 25건만 조회하고 KR 결과가 0건일 때만 US를 조회했다. 다른 가수의 동명곡이 있으면 다른 국가를 조회하지 않았고 JP는 조회하지 않았다.
- 기존 MusicRepository.currentCandidates는 alternateVersion=true이면 originalQuery(title)만 사용했다. 이 분기에서 artist가 누락됐다. 상위 작업에서 findCandidates(title, artist) 연결 및 검색 캐시 v2 변경 예정.
- 기존 TrackRules는 live/cover 같은 단어를 위치와 관계없이 지워 정상 제목도 훼손할 수 있었다.
- 기존 LRCLIB는 /api/get 응답 하나의 plainLyrics를 그대로 원본으로 표시했다. 문자/언어 검증 및 다른 등록본 확인이 없었고 로마자와 영어를 구분할 근거도 없었다.

## 실제 공개 응답

모든 iTunes 표본: GET https://itunes.apple.com/search, entity=song, limit=50. 표본은 lang 미지정(문서 기본 en_us). 구현 JP 조회는 ja_jp를 명시한다.

| term | country | resultCount | 확인 |
| --- | --- | --- | --- |
| tomoshibi | JP | 50 | artistName에 Vaundy 포함 결과 없음 |
| tomoshibi | KR | 0 | 결과 없음 |
| tomoshibi | US | 50 | artistName에 Vaundy 포함 결과 없음 |
| Vaundy tomoshibi | JP | 12 | Vaundy 원곡 없음. Healing Energy/hosjiji 등의 결과 |
| Vaundy tomoshibi | KR | 0 | 결과 없음 |
| Vaundy tomoshibi | US | 2 | Healing Energy, hosjiji. Vaundy 원곡 없음 |
| Vaundy 灯火 | JP | 50 | 첫 결과 灯火 / Vaundy, trackId 1706831934, 178892ms |
| Vaundy しわあわせ | JP | 50 | 첫 결과 しわあわせ / Vaundy, trackId 1706829387, 278250ms |

따라서 “Vaundy의 곡이 카탈로그에 없다”는 결론은 틀리다. 이번 표본에서는 로마자 검색 색인/검색어가 원곡을 반환하지 않는 문제다. `shiawase`, `しわあわせ`, 한국어 음역은 동치라고 임의 처리하지 않는다. 가수+일본어 원제 검색은 성공했지만 이 두 곡명을 하드코딩하여 검색 결과를 만들어 넣지 않았다.

LRCLIB:
- GET /api/get?track_name=灯火&artist_name=Vaundy&duration=179: id=1408288, trackName=灯火, artistName=Vaundy, duration=179.0, plainLyrics 길이 603, 가나 있음/ASCII 영문 없음. 원문 일본어 등록본이 존재함을 확인했다.
- GET /api/search?track_name=Tomoshibi&artist_name=Vaundy: 두 번의 별도 조사 요청 모두 구조화된 statusCode=503, name=ServerOverloaded. 가사 없음이 아니라 서비스 과부하. 로마자 등록본이 실제 존재하는지는 이번 응답으로 확인하지 못했다.
- 사용자가 봤던 특정 영문/로마자 본문과 선택 곡 ID는 확보하지 못했으므로 그 사례의 정확한 등록본 원인은 미확정이다.

## 구현 및 통합 계약

- TrackRules.searchQueries(title, artist): 공식 영상/명시적 라이브 버전/채널 suffix를 정리하고 따옴표 내용과 가수를 보존. 원문 후보도 유지. 일반 제목 `I Want to Live`, `Live and Let Die`, `Live`, `Cover Me`는 보존.
- TrackRules.originalQuery(title, artist) 추가. 기존 단일 인자 API도 유지.
- SongSearchClient.findCandidates(title, artist) 및 기존 find(keyword) 지원. JP/KR/US 순서로 병합, 최대 두 검색어/6 HTTP 요청, 전체 24초 예산, 개별 최대 6초. 첫 검색어에 결과가 있으면 추가 검색어는 생략. 국가별 실패가 있어도 확보한 후보는 표시할 수 있다.
- SongSearchClient.getNotice(): 부분 국가 실패 안내. 성공 결과가 비었으면서 요청 실패가 있으면 성공 빈 목록으로 숨기지 않고 실패 반환. 상위 UI에서 notice를 전달해야 한다.
- catalog ID 또는 동일 제목/가수/2초 이내 길이 중복 제거. 원래 가수가 주어지면 가수 일치 후보 우선. 자동 원곡 확정은 하지 않는다.
- scope.progress: 국가별 검색 및 후보 비교/가사 조회/다른 원문 등록본 확인 메시지.
- LrcLibClient.Lyrics의 public plain, synced 유지, public notice="" 추가. LRCLIB 직접 조회 뒤 라틴 문자 또는 가사 없음일 때 검색 1회. 명시적 서버/속도 제한 실패 뒤에는 즉시 재호출하지 않는다.
- 대체 등록본은 정규화 제목+가수 일치와 알려진 재생 길이 ±2초를 모두 요구한다. 일본 문자/한자 메타데이터에 대해 라틴 문자 대신 일본 문자 등록본을 우선한다. 한자만인 가사 자체는 일본어로 단정하지 않는다.
- 동일 등록본에서 plain이 로마자이고 synced가 일본/한국 문자이면 synced의 타임스탬프를 제거한 본문을 사용한다.
- LyricsLanguage.describe(String): 일본어 문자, 한국어 문자, 한자 언어 미확인, 라틴 문자 언어 미확인/로마자 가능 등을 반환. 언어 모델이나 번역기로 원문을 재구성하지 않는다.
- 상위 작업에서 검색 캐시 v2 / 가사 캐시 native-v2를 연결해 오래된 결과를 우회해야 한다. 본 작업은 Repository/VM/UI/Settings/README/PROJECT_NOTES를 수정하지 않았다.

## 남는 제약과 후속 고려

1. 로마자→일본어 원제 변환은 해결 완료가 아니다. 공개 카탈로그의 별칭 색인에 없는 결과는 JP 추가만으로 보장되지 않는다. 검증 가능한 별칭 공급처 또는 선택적 AI 후보 추출 후 실제 카탈로그 검증이 필요하다. 잘못된 음역을 임의로 교정하거나 무관한 가수 결과를 원곡으로 확정하지 않는다.
2. iTunes는 문서상 약 20회/분 제한이 있다. 국가 병합 3회와 보조 검색까지 최대 6회이므로 상위 캐시/검색 디바운스가 필요하다. 전역 rate limiter는 이번 파일 범위에서 추가하지 않았다.
3. 짧은 요청 시간 제한은 장시간 정체를 줄이지만 느린 네트워크에서는 부분 결과가 될 수 있다. getNotice 표시가 필요하다.
4. LRCLIB 검색은 최대 20건/페이지네이션 없음. 원문 등록본이 없거나 별칭/길이가 다른 경우 보수적 매칭으로 선택하지 않는다. 라틴 문자 가사를 영어라고 단정하지 않으며 원문 미확인 안내를 남긴다.
5. 일본 가수가 영어 노래를 부를 수 있으므로 가수 이름만으로 일본어라고 판정하지 않는다. 라틴 제목·앨범만 주어진 곡은 대체 일본어 등록본을 자동 우선하지 않는다.
6. 원문 등록본의 내용 정확성 자체는 공개 DB를 신뢰하는 한 검증 한계가 있다. 곡/가수/길이 확인은 가사 저작자 검수와 같지 않다.
7. LRCLIB 문서는 User-Agent에 연락처/홈페이지도 권장/요구한다. 현 프로젝트의 공개 홈페이지·연락처가 확인되지 않아 기존 앱명 User-Agent를 유지했다. 배포 전 실제 주소 추가 필요.

## 검증

- 별도 javac + JUnit 실행: SearchCorrectnessTest 10개 통과. 제목 보존/가수 보존/문자 라벨/중복과 순위/가사 곡·가수·길이 확인/동기 가사 원문 선택 검증.
- 전체 Gradle은 병렬 충돌을 피하기 위해 실행하지 않았다. 앱 통합 빌드/상위 캐시와 알림 전달은 부모 작업 검증 범위.

공식 문서: [Apple Search API](https://performance-partners.apple.com/search-api), [LRCLIB API](https://lrclib.net/docs.txt).

## 후속 통합 완료

- Repository의 findCandidates 연결, 가수 보존, 검색/원문 캐시 버전 분리, 부분 검색 실패와 원문 확인 안내 UI 연결 완료.
- MusicBrainz work 검색의 정확한 로마자 별칭에서 원제 후보를 최대 2개 얻어 JP 카탈로그를 다시 조회한다. 작품의 가수/작곡가와 검색할 녹음이 같다고 추정하지 않는다. 예컨대 TOMOSHIBI 별칭을 가진 灯火 작품의 작곡가가 Vaundy가 아니어도 '灯火'라는 검색어 후보로만 사용한다. 실제 결과의 가수·제목은 iTunes 응답에서 가져온다.
- 원래 최대 6회 국가 조회에 별칭 1회/JP 확장 최대 2회가 추가될 수 있다. 전체 약 24초 예산 안에서만 진행한다. 검색 캐시 적용, 부분 실패 결과는 캐시하지 않음. 배포용 전역 서비스별 호출량 제어는 남은 과제다.
- 실제 SongSearchClient로 `tomoshibi` 조회: 성공, 후보 132개 중 灯火 / Vaundy / 1706831934 포함, 약 5.97초. 유튜브 `Vaundy「しわあわせ」(Live at Budokan) [Official Video]`, 가수 `Vaundy - Topic`: 성공, しわあわせ / Vaundy / 1706829387 포함, 약 1.95초. 각각 한 번의 표본이며 저관련 후보도 포함된다.
- SearchCorrectnessTest 12개, 전체 단위 49개, API 36 계측 13개 통과. 호출·계정 조건에 따른 모든 곡의 검색 성공을 보장하지 않는다.
