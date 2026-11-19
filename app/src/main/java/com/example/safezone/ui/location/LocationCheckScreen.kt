package com.example.safezone.ui.location

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.safezone.data.location.LocationPermission
import com.example.safezone.data.location.locationPermission
import com.example.safezone.ui.components.BackTopBar
import com.example.safezone.ui.components.PrimaryButton
import com.example.safezone.ui.theme.SafeZoneTheme

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/**
 * 반납 순서 ① 위치 확인 화면. 위치 권한을 받은 뒤 현재 위치가 반납 가능한 곳인지 서버에 묻는다.
 * 통과하면 다음 화면으로 넘어가는 일은 호출하는 쪽(AppNavigation)이 맡는다.
 *
 * 주차 구역을 가리려면 '정확한 위치' 권한이 필요하다. Android 12 이상에서 사용자가 '대략적 위치'만
 * 허용하면 오차가 km 단위라 서버가 항상 "GPS 정확도가 낮아요"로 거절하므로, 위치를 묻기 전에 권한부터 안내한다.
 *
 * @param onCheck 권한이 있을 때 위치 확인을 시작한다 (ReturnViewModel.check)
 */
@Composable
fun LocationCheckScreen(
    state: LocationUiState,
    onCheck: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current

    var permission by remember { mutableStateOf(context.locationPermission()) }
    val hasPermission = permission == LocationPermission.PRECISE
    var requestedOnEntry by rememberSaveable { mutableStateOf(false) }
    // 시스템이 더 이상 권한 요청 창을 띄우지 않는 상태 (보통 두 번 거부한 경우) → 설정 화면으로 안내한다.
    var blockedBySystem by rememberSaveable { mutableStateOf(false) }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // '대략적 위치'만 고른 경우 요청 결과에는 허용이 하나 들어 있지만 정확한 위치는 여전히 없다.
        // 그래서 결과 대신 지금 실제로 가진 권한을 다시 읽는다.
        permission = context.locationPermission()
        blockedBySystem = permission != LocationPermission.PRECISE && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // 화면에 처음 들어오면 바로 권한을 요청한다.
    LaunchedEffect(Unit) {
        if (!hasPermission && !requestedOnEntry) {
            requestedOnEntry = true
            requestPermission.launch(LOCATION_PERMISSIONS)
        }
    }
    // 설정 앱에서 권한을 켜고 돌아온 경우를 반영한다.
    LifecycleResumeEffect(Unit) {
        permission = context.locationPermission()
        onPauseOrDispose { }
    }
    // 권한이 생기면 위치 확인을 시작한다.
    LaunchedEffect(hasPermission) {
        if (hasPermission && state is LocationUiState.Idle) onCheck()
    }

    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(onBackClick = onBackClick, title = "반납 위치 확인")

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    permission == LocationPermission.APPROXIMATE -> Status(
                        icon = Icons.Outlined.LocationOff, isError = true,
                        title = "정확한 위치 권한이 필요해요.",
                        description = if (blockedBySystem) {
                            "설정의 위치 권한에서\n'정확한 위치 사용'을 켜 주세요."
                        } else {
                            "대략적인 위치로는 주차할 수 있는 곳인지\n확인할 수 없어요."
                        },
                    )
                    !hasPermission -> Status(
                        icon = Icons.Outlined.LocationOff, isError = true,
                        title = "위치 권한이 필요해요.",
                        description = "주차할 수 있는 곳인지 확인하려면\n현재 위치가 필요해요.",
                    )
                    state is LocationUiState.Denied -> Status(
                        icon = Icons.Outlined.LocationOff, isError = true,
                        title = "이 위치에는 반납할 수 없어요.",
                        description = state.message,
                    )
                    state is LocationUiState.Failed -> Status(
                        icon = Icons.Outlined.ErrorOutline, isError = true,
                        title = "위치를 확인하지 못했어요.",
                        description = state.message,
                    )
                    state is LocationUiState.Allowed -> Status(
                        icon = Icons.Outlined.LocationOn, isError = false,
                        title = "반납할 수 있는 위치예요.",
                        description = state.zone ?: "주차 사진을 찍어 주세요.",
                    )
                    else -> Status(
                        icon = Icons.Outlined.LocationOn, isError = false, showProgress = true,
                        title = "현재 위치를 확인하고 있어요.",
                        description = "주차할 수 있는 곳인지 확인하고 있습니다.",
                    )
                }
            }

            when {
                !hasPermission -> PrimaryButton(
                    text = when {
                        blockedBySystem -> "설정에서 허용하기"
                        permission == LocationPermission.APPROXIMATE -> "정확한 위치 허용하기"
                        else -> "위치 권한 허용하기"
                    },
                    onClick = {
                        if (blockedBySystem) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        } else {
                            requestPermission.launch(LOCATION_PERMISSIONS)
                        }
                    },
                )
                state is LocationUiState.Denied || state is LocationUiState.Failed ->
                    PrimaryButton(text = "다시 확인하기", onClick = onCheck)
                else -> Text(
                    text = "잠시만 기다려 주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Status(
    icon: ImageVector,
    isError: Boolean,
    title: String,
    description: String,
    showProgress: Boolean = false,
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
        if (showProgress) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 4.dp,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(88.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
        }
    }
    Spacer(modifier = Modifier.height(28.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = description,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

// Preview 창에서 기기 없이 화면을 확인하기 위한 함수 (앱 실행과 무관)
@Preview(showBackground = true, heightDp = 780)
@Composable
private fun LocationDeniedPreview() {
    SafeZoneTheme {
        LocationCheckScreen(
            state = LocationUiState.Denied("주차 금지 구역이에요. 지정 주차구역으로 옮겨주세요."),
            onCheck = {},
            onBackClick = {},
        )
    }
}
