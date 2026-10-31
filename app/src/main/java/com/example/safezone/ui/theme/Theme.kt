package com.example.safezone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// 명세가 흰 배경 기반이라 다크 모드와 Android 12+의 배경화면 색(dynamic color)은 쓰지 않는다.
// dynamic color를 켜 두면 Primary가 사용자 배경화면 색으로 바뀌어 브랜드 색이 나오지 않는다.
private val SafeZoneColorScheme = lightColorScheme(
    primary = SafeGreen,
    onPrimary = White,
    secondary = SafeGreen,
    onSecondary = White,
    background = White,
    onBackground = TextPrimary,
    surface = White,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceGray,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceGray,
    error = Danger,
    onError = White,
    outline = BorderGray,
    outlineVariant = BorderGray,
)

@Composable
fun SafeZoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SafeZoneColorScheme,
        typography = Typography,
        content = content,
    )
}
