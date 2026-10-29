# Safe Zone

주차한 전동 킥보드 사진으로 보행 공간을 막고 있는지 확인하는 Android 앱입니다.

현재 상태: 첫 화면만 있습니다. "사진 촬영하기"와 "갤러리에서 선택하기" 버튼은 아직 미구현 안내 토스트만 띄웁니다.

## 개발 환경

- Android Studio Quail 4(2026.1.4) 이상. 권장 버전은 최신 안정판인 Rabbit 2(2026.2.2)입니다. AGP 9.4를 지원하는 가장 낮은 버전이 Quail 4입니다.
- Kotlin + Jetpack Compose, AGP 9.4.0, Gradle 9.6.1(wrapper 포함)
- compileSdk/targetSdk 37, minSdk 24

## 실행

Android Studio에서 `File > Open`으로 이 폴더를 열고 ▶ Run 'app'을 누릅니다.

터미널에서 실행하는 경우:

```bash
./gradlew assembleDebug        # APK 빌드
./gradlew testDebugUnitTest    # 단위 테스트 (Robolectric, 기기 불필요)
```

## CI

push할 때마다 GitHub Actions(`.github/workflows/android.yml`)가 빌드, 단위 테스트, lint를 실행합니다.
