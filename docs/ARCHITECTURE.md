# 창밖라디오 구조

차량 위치에 맞춰 지역 이야기를 한국어로 들려주는 Pleos Connect용 앱입니다. 앱은 대본을 받아 **Pleos TTS가 직접 읽고**, 생성 서버는 대본과 근거만 만듭니다. 음성 파일은 서버에서 만들지 않습니다.

## 한눈에 보기

```
┌─────────────── Pleos Connect 기기 (앱) ───────────────┐        ┌──── 생성 서버 (Node 24) ────┐
│ MainActivity / RadioScreen (Compose UI)               │        │ GET  /health                 │
│        │                                              │        │ GET  /nearby   ── TourAPI    │
│  RadioEngine ── 위치·주행 제한·오디오 포커스·세션     │ HTTPS  │ POST /stories ─┐             │
│        │  ├─ LiveStories ───────────────────────────────┼──────▶│ POST /journey ─┴─ OpenAI    │
│        │  ├─ DemoRoute (가상 경로·저장 시연)            │ 대본만 │      (Responses, 구조화 출력)│
│        │  └─ History (들은 이야기 24건)                 │        │  근거 ID 검증 · 금지어 검사  │
│        ▼                                              │        └──────────────────────────────┘
│  재생 Player 둘 (Media3)                              │
│   ├─ ExoPlayer      저장 음원(WAV) ─ 파형은 음원에서 계산
│   └─ SpokenPlayer   대본 ─ Voice ─ Pleos TTS(한국어, ko-KR)
│                                  └ 실패 시 기기 TTS
└───────────────────────────────────────────────────────┘
```

## 앱 (`app/src`)

| 파일 | 역할 |
| --- | --- |
| `RadioEngine.kt` | 세션 상태 기계. 위치 신호, 후보 선정(450m 접근), 24시간 중복 방지, 주행 제한과 오디오 포커스 확인, 재생 선택(`activePlayer`) |
| `SpokenPlayer.kt` | 대본을 읽는 Media3 `Player`(`SimpleBasePlayer`). `Voice` 인터페이스와 기기 TTS 구현(`SystemVoice`) 포함 |
| `pleos/PleosVoice.kt` | Pleos TTS SDK 어댑터(`withPleos` 빌드에만 포함). 오류 시 기기 TTS로 전환 |
| `LiveStories.kt` | 서버 호출(`/nearby`, `/stories`, `/journey`)과 응답 검증, 최근 응답 10분 캐시 |
| `DemoRoute.kt` | 경주·샌프란시스코 가상 경로, 저장 시연의 구간·파형 정보(`journey_audio.json`) |
| `RadioService.kt` | `MediaLibraryService`. 차량 미디어 UI의 재생/정지 요청을 엔진에 전달 |
| `Vehicle.kt`, `pleos/PleosNavigation.kt` | `CarUxRestrictions` 주행 제한, NaviHelper 경유지 추가(리플렉션으로 연결해 일반 Android 빌드에서도 컴파일) |
| `DrivingLocationService.kt` | 실제 위치 세션 동안 앱이 화면을 벗어나도 위치를 받는 포그라운드 서비스 |
| `RadioDashboard.kt`, `RadioComponents.kt`, `Waveform.kt` | 홈·여정 노트·플레이어 UI, 음원 파형 계산 |

### 두 가지 재생 경로

| | 저장 시연 | AI 해설(실시간 생성) |
| --- | --- | --- |
| 음성 | Supertonic 3 WAV 파일을 앱에 포함 | 서버가 보낸 대본을 Pleos TTS가 읽음 |
| 재생기 | ExoPlayer | `SpokenPlayer` |
| 인터넷·키 | 필요 없음 | 생성 서버(OpenAI·TourAPI 키는 서버에만) |
| 진행 위치 | 실제 재생 위치 | 덩이별 길이 추정, 덩이가 끝날 때 실제 위치로 보정 |
| 파형 | 음원에서 계산 | Pleos TTS가 알려 주는 실제 음량을 읽은 만큼 기록(아직 읽지 않은 구간은 기본 높이) |

`Story.spoken`(음원 파일 경로가 비어 있음)으로 구분합니다. 엔진은 `Player` 인터페이스만 쓰므로 일시정지·이어 듣기·종료·오류 처리는 두 경로가 같습니다.

### SpokenPlayer 동작

1. `narration()`이 읽을 덩이를 만듭니다. 장면마다 쉼 180ms, 퀴즈 질문 앞 360ms, 정답 앞 **1,600ms**입니다.
2. `play`를 받으면 덩이를 하나씩 `Voice.speak`로 읽고, 끝날 때마다 다음 덩이로 넘어갑니다.
3. 일시정지는 읽던 덩이를 멈춥니다. 이어 듣기는 그 덩이를 처음부터 다시 읽습니다. Pleos TTS는 일시정지를 지원하지 않습니다.
4. 엔진 오류나 미지원 언어면 `PleosVoice`가 기기 TTS로 넘어갑니다. 둘 다 안 되면 오디오 오류로 알려 세션을 정리합니다.
5. 한국어 로케일은 `ko-KR`입니다. `ko`만 주면 Pleos 엔진이 `unsupported locale`로 거부합니다.

## 생성 서버 (`server/src`)

| 파일 | 역할 |
| --- | --- |
| `server.ts` | HTTP/HTTPS 서버, 시연 접근 코드(만료 시각 포함) 검사, 속도 제한, 동시 생성 3건 제한, 본문 2KB 제한 |
| `providers.ts` | TourAPI 조회, 캘리포니아 로컬 자료, OpenAI Responses 대본 생성, 검증 |

- 대본은 구조화 출력(JSON 스키마)으로 받고, 문장마다 **근거 ID**를 붙이게 합니다. 서버는 근거 ID가 입력 자료에 있는지, 금지 표현(운전 지시·가격·순위·링크 등)이 없는지 확인한 뒤에만 보냅니다.
- 응답은 `sentences`, `facts`, `sources`, 경로 해설의 `chapters`(경유지 ID)와 `quiz`입니다. 오디오 필드는 없습니다.
- 음성 합성(OpenAI TTS)은 서버에서 제거했습니다. OpenAI가 `gpt-4o-mini-tts` 종료를 예고했기 때문입니다.

### HTTPS

기본은 `127.0.0.1` HTTP입니다(`adb reverse`로 디버그 에뮬레이터 연결). 외부 기기에서 쓰려면 서버가 직접 HTTPS를 엽니다.

```powershell
$env:HOST='0.0.0.0'; $env:PORT='8443'
$env:TLS_CERT='C:\certs\fullchain.pem'; $env:TLS_KEY='C:\certs\privkey.pem'
$env:DEMO_TOKEN='16자 이상 코드'; $env:DEMO_TOKEN_EXPIRES_AT='2026-12-31T00:00:00Z'
npm start
```

`HOST`를 루프백 밖으로 열면 `TLS_CERT`, `TLS_KEY`, `DEMO_TOKEN`이 모두 있어야 시작합니다. 릴리스 앱은 `https` 주소만 받으므로 Android가 신뢰하는 인증서(예: Let's Encrypt)가 필요합니다. 자체 서명 인증서는 앱이 거부합니다. 이미 HTTPS 프록시(Caddy, nginx 등)를 쓴다면 서버는 루프백 HTTP 그대로 두고 프록시만 앞에 둡니다.

## 알려진 한계

- Pleos TTS는 일시정지, 속도, 소리 파일 출력을 제공하지 않습니다. 이어 듣기는 덩이 처음부터이고, AI 해설의 시간 표시는 추정입니다.
- 이 저장소의 에뮬레이터 검증은 테스트 데이터 서버로 했습니다. 실제 OpenAI·TourAPI 호출과 실차 주행은 검증하지 않았습니다.
