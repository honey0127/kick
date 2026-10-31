# Safe Zone

주차한 전동 킥보드 사진으로 보행 공간을 막고 있는지 확인하는 Android 앱입니다.

> **현재 상태:** 앱의 UI와 화면 이동은 모두 동작합니다. **AI 분석은 아직 연결되지 않았습니다.**
> 분석 결과는 사진을 보지 않는 UI 확인용 임시 결과이며, 결과 화면에 "테스트용 임시 결과"라고 표시됩니다.

## 화면 흐름

```
홈 ─┬─ 사진 촬영하기 ──→ 촬영 ──┐
    │                         ├─→ 사진 확인 ─→ 분석 중 ─→ 결과
    └─ 갤러리에서 선택하기 ─────┘
```

| 결과 | 메인 버튼 | 이동 |
|---|---|---|
| 주차 양호 (SAFE) | 홈으로 돌아가기 | 홈 |
| 주차 위치 확인 필요 (UNSAFE) | 다시 주차하기 (+ 홈으로) | 촬영 (또는 홈) |
| 판단 보류 (UNCERTAIN) | 다시 촬영하기 | 촬영 |

- 사진 확인 화면의 "다시 선택하기"는 사진을 가져온 곳으로 돌아갑니다 (촬영 → 촬영 화면, 갤러리 → 갤러리 다시 열기).
- 분석 중 뒤로 가면 분석이 취소되고 사진 확인 화면으로 돌아갑니다.
- 결과 화면에서 뒤로 가면 홈으로 갑니다.

## 구현된 기능

### 홈
- 로고, 안내 문구, 임시 일러스트(아이콘), "사진 촬영하기" / "갤러리에서 선택하기" 버튼

### 촬영 (`PhotoSelectScreen`)
- CameraX 카메라 미리보기, 원형 촬영 버튼, 촬영 안내 3줄
- 화면에 들어오면 카메라 권한 요청
  - 거부: 권한 안내와 "카메라 권한 허용하기"
  - 두 번 거부해 시스템이 요청을 막은 경우: "설정에서 허용하기" → 앱 설정 화면. 설정에서 허용하고 돌아오면 바로 카메라가 켜짐
- 촬영 사진은 앱 캐시에 저장하고, 새로 찍으면 이전 사진은 지움

### 갤러리 선택
- Android Photo Picker 사용 (저장소 권한 불필요, 사용자가 고른 사진 1장만 앱에 전달)

### 사진 확인 (`PhotoPreviewScreen`)
- 원본 비율을 유지해 사진 표시
- 사진을 불러오지 못하면 안내를 보여 주고 "주차 상태 분석하기" 버튼을 막음
- 화면 폭이 좁거나 글자 크기가 크면 버튼을 세로로 쌓음

### 분석 중 (`AnalyzingScreen`)
- 진행 표시와 안내 문구
- 분석 중 같은 요청이 다시 들어오면 무시 (중복 요청 방지)
- 실패하면 오류 안내와 "다시 시도하기" (같은 사진으로 재시도)

### 결과 (`ResultScreen`)
- SAFE / UNSAFE / UNCERTAIN을 한 화면에서 아이콘, 색, 문구, 버튼으로 구분
- 분석 근거 카드: 분석 결과에 들어 있는 항목만 표시하고, 근거가 없으면 카드를 표시하지 않음
- 임시 결과(Mock)는 화면 맨 위에 "테스트용 임시 결과예요. 실제 AI 분석이 아닙니다." 안내

### 공통
- 디자인 시스템: Primary `#31866B`, 흰 배경, 좌우 여백 24dp, 버튼 높이 56dp
- 라이트 테마 고정 (다크 모드와 배경화면 색상 연동 끔)
- 화면 회전 시 사진, 분석 진행, 결과 상태 유지
- 상태바·내비게이션바 영역 확보 (edge-to-edge)

## AI 분석 연결 방법

화면은 `PhotoAnalyzer` 인터페이스만 사용합니다. 실제 분석기를 만들면 한 곳만 바꾸면 됩니다.

1. `PhotoAnalyzer`를 구현합니다. 분석 근거(`findings`)에는 실제로 확인한 항목만 넣고 `isMock = false`로 둡니다. 실패하면 예외를 던집니다.
2. `navigation/AppNavigation.kt`에서 `MockPhotoAnalyzer()`를 새 구현으로 바꿉니다.

```kotlin
interface PhotoAnalyzer {
    suspend fun analyze(photoUri: String): AnalysisResult
}
```

지금 쓰는 `MockPhotoAnalyzer`는 약 2초 기다린 뒤 SAFE → UNSAFE → UNCERTAIN 순서로 정해진 결과를 돌려줍니다.

## 프로젝트 구조

```
app/src/main/java/com/example/safezone/
├── MainActivity.kt
├── navigation/AppNavigation.kt      화면 경로, 화면 이동, 사용할 분석기 지정
├── data/
│   ├── model/AnalysisResult.kt      결과 상태(SAFE/UNSAFE/UNCERTAIN), 근거, 임시 결과 여부
│   └── analysis/
│       ├── PhotoAnalyzer.kt         분석기 인터페이스
│       └── MockPhotoAnalyzer.kt     UI 확인용 임시 분석기
└── ui/
    ├── home/HomeScreen.kt
    ├── photo/PhotoSelectScreen.kt   촬영
    ├── photo/PhotoPreviewScreen.kt  사진 확인
    ├── analysis/AnalyzingScreen.kt
    ├── analysis/AnalysisViewModel.kt  분석 요청·중복 방지·재시도·취소
    ├── result/ResultScreen.kt
    ├── components/                  PrimaryButton, SecondaryButton, BackTopBar
    └── theme/                       색상, 글자 크기, 테마
```

## 사용 라이브러리

| 용도 | 라이브러리 |
|---|---|
| UI | Jetpack Compose (BOM 2026.09.00), Material3, Material Icons Extended |
| 화면 이동 | Navigation Compose 2.10.2 |
| 카메라 | CameraX 1.6.2 |
| 사진 표시 | Coil 3.6.3 |
| 상태 관리 | Lifecycle ViewModel Compose 2.11.0 |
| 테스트 | JUnit4, Robolectric 4.17, Compose UI Test, kotlinx-coroutines-test, coil-test |

## 개발 환경

- Android Studio Quail 4(2026.1.4) 이상. 권장 버전은 최신 안정판인 Rabbit 2(2026.2.2)입니다. AGP 9.4를 지원하는 가장 낮은 버전이 Quail 4입니다.
- Kotlin + Jetpack Compose, AGP 9.4.0, Gradle 9.6.1(wrapper 포함)
- compileSdk/targetSdk 37, minSdk 24
- 권한: 카메라 (카메라가 없는 기기에도 설치 가능, 갤러리 선택은 권한 불필요)

## 실행

Android Studio에서 `File > Open`으로 이 폴더를 열고 ▶ Run 'app'을 누릅니다.

터미널에서 실행하는 경우:

```bash
./gradlew assembleDebug        # APK 빌드
./gradlew testDebugUnitTest    # 단위 테스트 (Robolectric, 기기 불필요)
```

`SDK location not found` 오류가 나면 프로젝트 폴더에 `local.properties`를 만들고 Android SDK 경로를 적습니다. 이 파일은 PC마다 다르므로 git에 올리지 않습니다.

```properties
sdk.dir=C\:\\Users\\<사용자>\\AppData\\Local\\Android\\Sdk
```

## 테스트

`./gradlew testDebugUnitTest`로 기기 없이 실행합니다.

| 파일 | 확인 내용 |
|---|---|
| `AppFlowTest` | 갤러리 선택 → 사진 확인 → 분석 → 결과 → 홈, 분석 중 뒤로 가기(취소), 결과에서 뒤로 가기 |
| `MainActivityTest` | 홈 화면 표시, 갤러리 버튼이 사진 선택기 실행, 촬영 화면 진입 |
| `CameraPermissionRequiredTest` | 카메라 권한 안내 문구와 버튼 (다시 요청 / 설정으로 이동) |
| `AnalysisViewModelTest` | 분석 성공, 중복 요청 무시, 실패 후 재시도, 취소 |
| `MockPhotoAnalyzerTest` | 결과 순서, 모든 결과에 임시 결과 표시 |
| `ResultScreenTest` | 상태별 문구와 버튼, 결과에 든 근거만 표시, 임시 결과 안내 |

갤러리(Photo Picker)와 카메라는 시스템 기능이라 단위 테스트에서는 결과를 직접 넣어 확인합니다. 실제 촬영과 권한 흐름은 에뮬레이터에서 확인했습니다.

## 아직 구현되지 않은 것

- 실제 AI 분석 (현재는 `MockPhotoAnalyzer`)
- 홈 화면의 디자인된 일러스트 (현재는 아이콘으로 만든 임시 그림)
- 출시용 빌드 설정 (코드 축소 미적용, 디버그 APK 약 24MB)

## CI

push할 때마다 GitHub Actions(`.github/workflows/android.yml`)가 빌드, 단위 테스트, lint를 실행합니다.
