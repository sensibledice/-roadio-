# 창밖라디오 0.5.0

창밖라디오는 차량 위치에 맞춰 여행 이야기를 한국어로 들려줍니다. 첫 실행에서 국내 한국 또는 국외 미국 캘리포니아를 선택합니다. `연결 설정 → 여행 지역 변경`에서 다시 고를 수 있습니다. 한국을 고르면 가상 주행으로, 캘리포니아를 고르면 기기 위치로 시작하도록 설정됩니다.

## 설치와 빌드

| APK | 용도 |
| --- | --- |
| `apk/changbak-radio-demo-debug.apk` | 일반 Android용 디버그 APK. Pleos SDK 의존성 없이 빌드합니다. |
| `apk/changbak-radio-pleos-debug.apk` | NaviHelper 3.0.5를 포함한 Pleos Connect 검증용 디버그 APK. |

JDK 21, Android SDK Platform 36, Build-Tools 35.0.0, Platform-Tools가 필요합니다. Gradle 8.14.3, AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2025.10.01, Media3 1.8.0을 사용합니다. 앱은 API 28 이상에 설치할 수 있고 targetSdk는 35입니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11'
$env:ANDROID_HOME = "$env:USERPROFILE\AppData\Local\Android\Sdk"
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'

# 준비된 일반 Android 디버그 APK
& $adb devices -l
& $adb -s emulator-5556 install -r apk\changbak-radio-demo-debug.apk
& $adb -s emulator-5556 shell am start -W -n kr.changbak.radio/.MainActivity

# 일반 Android와 Pleos APK를 각각 빌드
.\gradlew.bat :app:assembleDebug
Copy-Item app\build\outputs\apk\debug\app-debug.apk apk\changbak-radio-demo-debug.apk
.\gradlew.bat '-PwithPleos=true' :app:assembleDebug
Copy-Item app\build\outputs\apk\debug\app-debug.apk apk\changbak-radio-pleos-debug.apk
& $adb -s emulator-5558 install -r apk\changbak-radio-pleos-debug.apk
& $adb -s emulator-5558 shell am start -W -n kr.changbak.radio/.MainActivity
```

`emulator-5556`와 `emulator-5558`을 `adb devices`에서 확인한 시리얼로 바꿉니다. Linux와 macOS에서는 `sh ./gradlew`를 사용합니다. Gradle Wrapper의 배포본 다운로드가 막힌 환경에서는 `gradle/wrapper/gradle-wrapper.properties`에 고정된 배포본과 SHA-256을 확인해 해당 Wrapper 캐시에 공급할 수 있습니다.

공모전용 서명 정보가 준비되면 환경 변수 `RADIO_KEYSTORE`, `RADIO_STORE_PASSWORD`, `RADIO_KEY_ALIAS`, `RADIO_KEY_PASSWORD`를 설정하고 아래와 같이 빌드합니다. 저장소에는 서명 비밀과 키스토어를 넣지 않습니다. 현재 제공하는 APK는 디버그 키로 서명했습니다. [현대자동차그룹 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission)

```powershell
.\gradlew.bat '-PwithPleos=true' :app:assembleRelease
Copy-Item app\build\outputs\apk\release\app-release.apk 'apk\팀명_창밖라디오_release.apk'
```

## Pleos에서 시연하기

현대차 앱 아이디어 공모전의 ‘창밖동화’가 이동 경로와 지역 이야기, 동승자 퀴즈를 연결한 데서 착안했습니다. 창밖라디오는 확인 가능한 지역 자료를 바탕으로 경유지 사이에 이어지는 이야기와 짧은 퀴즈를 구성합니다. [현대자동차그룹 수상작 소개](https://www.hyundaimotorgroup.com/ko/news/hyundai-motor-company-pleos-connect-app-best-customer-idea)

홈은 지도, 검은 음성 플레이어, 여정 노트, 공통 녹색 버튼으로 구성됩니다. 플레이어의 파형은 실제 음원에서 계산하며, 녹색 부분은 재생한 구간입니다. 가상 주행에서도 지도와 플레이어의 위치가 유지됩니다. 저장 시연과 생성한 시연에서 현재 해설하는 경유지를 지도와 장소 목록에서 강조합니다. 마지막에는 질문을 읽고 약 1.6초를 비운 뒤 정답을 읽습니다. 음성 재생은 답변 입력 없이 계속됩니다.

- **저장 음성 시연**을 누르면 선택한 지역의 가상 경로와 음성 해설이 바로 시작됩니다. 경주는 대릉원·첨성대·동궁과 월지, 샌프란시스코는 팰리스 오브 파인 아츠·크리시 필드·포트 포인트 이야기를 들려줍니다. 인터넷과 API 키가 필요 없습니다.
- **AI 여행 라디오 시작**을 가상 주행 설정에서 누르면 서버가 선택한 경로 전체의 해설을 한 편으로 만듭니다. 음원 준비가 끝나면 출발합니다. 지도 진행은 실제 오디오 위치를 따릅니다. 일시정지 중에는 움직임도 멈추고, 해설을 끝까지 들으면 홈으로 돌아옵니다.
- **여정 노트**에서 정차 중 경유지를 선택하면 그 장소의 구간만 재생됩니다. 최근에 들은 해당 지역의 AI 이야기나 기본 저장 해설에서 대본, 근거와 공식 출처를 볼 수 있습니다. 퀴즈의 정답은 눌러서 확인할 수 있습니다.
- **들은 이야기**에서는 대본, 근거, 출처, AI 생성 시각을 확인할 수 있습니다. **연결 설정**에서는 지역, 위치 입력, 생성 서버를 바꿀 수 있습니다. 사용자가 시연을 시작한 경우에만 재생합니다.

가상 경로는 경주 시내 약 3.68km와 샌프란시스코 해안 약 4.05km입니다. 자동차용 OSRM 경로와 OpenStreetMap의 도로·녹지·수역을 사용합니다. 시연 시간을 위해 지도상의 이동은 압축되어 표시됩니다. 지도는 경주 시내와 샌프란시스코 프레시디오 주변을 오프라인으로 포함합니다. 지도 범위를 벗어나도 주변 이야기 검색은 별도로 작동합니다.

지도 및 경로 데이터 © OpenStreetMap contributors, ODbL 1.0. `extract_map.py`로 공개 범위 OSM XML을 지도 JSON으로 변환합니다. 샌프란시스코는 `--north-coast`를 붙여 해안선 북쪽 수역을 채웁니다. [OpenStreetMap 라이선스](https://www.openstreetmap.org/copyright) · [경주 원본 범위](https://api.openstreetmap.org/api/0.6/map?bbox=129.203,35.8305,129.231,35.8450) · [샌프란시스코 원본 범위](https://api.openstreetmap.org/api/0.6/map?bbox=-122.485,37.793,-122.444,37.816)

저장 시연은 Supertonic 3 F2 한국어 합성 음성입니다. 경주 55.38초, 샌프란시스코 59.44초이며, Pleos의 안내 음성 규격에 맞춰 선행 묵음을 붙였습니다. 재생성은 `python -m pip install supertonic==1.3.1` 후 `python synthesize_demo.py --journeys-only`로 합니다. 스크립트는 WAV와 파형 및 경유지 구간 정보(`journey_audio.json`)를 함께 만듭니다. [Supertonic 3](https://huggingface.co/Supertone/supertonic-3) · [Pleos 선행 묵음 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/tts-silence-guide)

## 실제 위치로 여행 라디오 이용하기

`연결 설정`에서 **기기 위치**와 서버 주소를 고릅니다. 정확한 위치 권한을 허용한 뒤 정차 중 **AI 여행 라디오 시작**을 누릅니다. Android 위치 서비스가 시작되면 여행 라디오 세션 동안 앱 화면을 벗어나도 위치를 받을 수 있습니다. 홈의 종료 버튼이나 시스템 위치 알림의 종료 동작으로 구독을 끝냅니다. 실제 주행 중에는 차량 제한 상태에 따라 앱 UI가 가려지고, 진행 중인 세션의 음성은 차량 미디어와 오디오 포커스를 따릅니다. 별도 내비 앱의 오디오를 앱이 생성하지 않습니다.

한국의 실제 위치 검색은 TourAPI를 씁니다. 캘리포니아는 현지 공식 자료로 구성한 초기 8곳을 검색합니다. 샌프란시스코 3곳, 몬터레이, 포인트 로보스, 요세미티, 조슈아트리, 샌디에이고입니다. 등록된 장소 반경 1.2km 이내에서 후보를 확인하고, 접근 거리 450m에서 최대 한 편을 시작합니다. 동일 장소는 최근 24시간 동안 반복 안내하지 않으며 다른 해설은 재생 종료 후 2~4분 간격을 둡니다. 지도 파일의 범위와 이야기 검색 범위는 다릅니다. [캘리포니아 장소 자료](server/data/california_places.json) · [NPS 크리시 필드](https://www.nps.gov/articles/crissy-airfield-presidio-of-san-francisco.htm) · [Golden Gate Bridge District 포트 포인트](https://www.goldengate.org/exhibits/fort-point-arch/)

Pleos의 NaviHelper 좌표에는 정확도와 측정 시각이 없어 현재 실제 위치 안내는 Android 기기 위치를 이용합니다. 차량의 주행 제한은 `CarUxRestrictions` 상태를 읽어 적용합니다. [Pleos Music/Audio 앱 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide) · [Pleos 주행 규제 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)

## 생성 서버

Node.js 24 이상을 사용합니다. `server/.env.example`을 `server/.env`로 복사하고 `OPENAI_API_KEY`를 입력합니다. 국내 실제 위치 검색에는 TourAPI 국문 관광정보 서비스_GW의 Decoding 인증키 `TOUR_API_KEY`도 필요합니다. 캘리포니아와 두 가상 경로는 프로젝트에 포함된 공식 자료 발췌를 이용합니다. 생성 서버는 Responses API에서 장소 순서에 맞춘 이야기와 마지막 장면의 사실로 답할 수 있는 퀴즈를 요청합니다. 문장과 퀴즈의 근거 ID를 확인한 뒤 구간별로 한국어 음성을 합성합니다. `OPENAI_SPEECH_VOICE`로 음색을 바꿀 수 있습니다. [OpenAI 구조화 출력](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses) · [OpenAI 음성 합성](https://developers.openai.com/api/docs/guides/text-to-speech)

현재 구성의 `gpt-4o-mini-tts`는 OpenAI가 2027년 1월 6일 종료를 예고한 모델입니다. 그 이후 음성 합성에는 공급자가 지원하는 방식으로 전환이 필요합니다. [OpenAI 모델 종료 안내](https://developers.openai.com/api/docs/guides/text-to-speech)

```powershell
Set-Location server
Copy-Item .env.example .env
npm ci
npm run check
npm start
```

기본 포트는 `8787`입니다. 디버그 에뮬레이터에선 `adb reverse tcp:8787 tcp:8787` 후 연결 설정에 `http://127.0.0.1:8787`을 넣을 수 있습니다. 심사용 기기에서는 같은 호스트의 HTTPS 역방향 프록시와 만료 설정이 있는 시연 접근 코드를 사용합니다. OpenAI·TourAPI 키는 서버에서만 사용합니다.

| HTTP 요청 | 동작 |
| --- | --- |
| `GET /health` | 서버와 공급자 설정 확인 |
| `GET /nearby?lat=37.80436&lon=-122.45951&region=ca` | 선택한 지역 내 위치 기반 후보 검색. 지역 기본값은 `kr` |
| `POST /stories` | 최근 검색한 장소와 현재 좌표를 받아 짧은 해설 생성 |
| `POST /journey` | `{"region":"kr"}` 또는 `{"region":"ca"}`를 받아 가상 경로 전체의 연속 음성과 출처 반환 |

테스트 서버를 쓰려면 아래를 실행합니다. 공급자 응답과 음성은 테스트 데이터입니다.

```powershell
Set-Location server
$env:PORT='8793'
$env:TOUR_API_KEY='fixture-tour'
$env:OPENAI_API_KEY='fixture-openai'
$env:DEMO_TOKEN='fixture-local-access-2026'
$env:DEMO_TOKEN_EXPIRES_AT='2100-01-01T00:00:00Z'
node --import ./test/provider-fixture.mjs src/server.ts
```

다른 터미널에서 `$env:FIXTURE_ORIGIN='http://127.0.0.1:8793'`을 설정한 뒤 `node test/verify-fixture.mjs`를 실행합니다. 앱에 연결하려면 `adb reverse tcp:8793 tcp:8793` 후 `http://127.0.0.1:8793`과 시연 접근 코드를 입력합니다.

## 0.3.0 검증 기록

| 항목 | 관찰 결과 |
| --- | --- |
| 화면·지역 선택 | Pleos Connect 2.1.0 API 34 x86_64, 2560×1440·160dpi에서 첫 실행 지역 선택, 설정에서 지역 변경, 두 지역의 실제 도로 지도를 확인했습니다. 홈의 세 카드에는 아이콘과 설명을 넣었고 AI 시작과 같은 연두색을 적용했습니다. |
| 연속 해설 | 경주 33.61초와 샌프란시스코 32.03초 저장 시연의 시작·종료 및 Windows Realtek 오디오 출력을 확인했습니다. 50ms 단위로 음원을 분석한 최장 저음량 구간은 각각 0.90초, 0.45초였습니다. 최종 Pleos APK에서 샌프란시스코 해설을 일시정지한 뒤 약 3.95초 지점에서 이어 끝까지 재생했습니다. |
| AI 시연 | 테스트 서버를 연결한 샌프란시스코 시연은 응답을 준비한 뒤 32.21초 연속 재생했습니다. 서버 타입 검사와 두 지역의 경로 해설, 캘리포니아 후보·출처·지역 유효성에 대한 HTTP 테스트도 통과했습니다. |
| 캘리포니아 GPS | Pleos에서 기기 위치 권한으로 포그라운드 위치 서비스를 시작했습니다. 시스템 설정을 전면에 놓고 샌프란시스코 좌표를 넣자 백그라운드 앱이 GPS를 받고 크리시 필드의 테스트 음성을 재생했습니다. Windows 출력과 재생 종료를 확인했습니다. |
| 미확인 환경 | 실제 OpenAI·TourAPI 호출에는 인증키가 필요합니다. 에뮬레이터의 가상 차량 콘솔 DDS 연결을 사용할 수 없어 D 기어 연동은 이번 검증에서 재현하지 못했습니다. 캘리포니아 실차와 주 전역 지도는 추가 검증·확장이 필요합니다. |

## 0.4.0 검증 기록

- Pleos Connect 2.1.0의 2560×1440 화면에서 홈, 지도, 실제 파형을 사용하는 플레이어, 여정 노트의 레이아웃을 확인했습니다.
- 첨성대의 저장 음성 구간만 10,967ms 재생하고 종료되는 것을 확인했습니다. 여정 노트는 첨성대를 지도에서 강조했고 국가유산청 출처와 근거를 표시했습니다. Windows 오디오 출력도 확인했습니다.
- 경주·샌프란시스코 음원의 각 경유지 구간이 실제 WAV 범위 안에 있고 앞에 180ms 묵음이 들어 있음을 확인했습니다. 기존 생성 서버의 지역별 조회, 시연 응답과 WAV 검증도 로컬 테스트 데이터로 통과했습니다.
- 실제 API 공급자와 실차 주행은 0.4.0에서 추가로 검증하지 않았습니다.
- Pleos 에뮬레이터를 다시 시작한 뒤 경주 저장 시연을 33.402초 재생해 정상 종료와 Windows 스피커 출력을 확인했습니다.

## 0.5.0 검증 기록

- 경주 저장 시연 약 55.4초가 Pleos에서 끝까지 재생됐고 Windows 스피커 출력을 확인했습니다. 여정 노트에서 퀴즈 정답을 펼쳤습니다.
- 경주와 샌프란시스코의 음원에서 질문과 답 사이에 1.6초 묵음이 들어 있음을 확인했습니다. 로컬 테스트 서버 응답에서도 근거 ID, 경유지 구간과 1.6초 묵음을 검증했습니다.
- Pleos에서 테스트 서버가 보낸 이야기의 전체 음원과 첨성대 구간을 각각 재생했습니다. 실제 OpenAI 호출에는 서버에 설정한 API 키가 필요하며, 이 환경에서는 실제 공급자 호출을 검증하지 않았습니다.
