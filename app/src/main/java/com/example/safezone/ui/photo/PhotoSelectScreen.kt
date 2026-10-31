package com.example.safezone.ui.photo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.safezone.ui.components.BackTopBar
import com.example.safezone.ui.components.PrimaryButton
import java.io.File

/**
 * 킥보드 주차 상태를 직접 촬영하는 화면.
 * 카메라 권한이 있으면 미리보기와 촬영 버튼을, 없으면 권한 안내를 보여 준다.
 */
@Composable
fun PhotoSelectScreen(
    onBackClick: () -> Unit,
    onPhotoCaptured: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current

    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    // 화면 회전 후에도 "이미 한 번 요청했다"는 사실을 기억해 요청 창이 반복해서 뜨지 않게 한다.
    var requestedOnEntry by rememberSaveable { mutableStateOf(false) }
    // 시스템이 더 이상 권한 요청 창을 띄우지 않는 상태 (보통 두 번 거부한 경우) → 설정 화면으로 안내한다.
    var blockedBySystem by rememberSaveable { mutableStateOf(false) }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        // 거부 직후 "이유를 설명하고 다시 물어봐도 되는가"가 false면 시스템이 요청을 막은 상태다.
        // 이 값은 상태로 저장해야 화면이 다시 그려진다 (그리는 중에 계산하면 갱신되지 않는다).
        blockedBySystem = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }

    // 화면에 처음 들어오면 바로 권한을 요청한다.
    LaunchedEffect(Unit) {
        if (!hasPermission && !requestedOnEntry) {
            requestedOnEntry = true
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }
    // 설정 앱에서 권한을 켜고 돌아온 경우를 반영한다.
    LifecycleResumeEffect(Unit) {
        hasPermission = context.hasCameraPermission()
        onPauseOrDispose { }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(onBackClick = onBackClick, title = "주차 사진 촬영")

        if (hasPermission) {
            CameraCapture(onPhotoCaptured = onPhotoCaptured, modifier = Modifier.weight(1f))
        } else {
            val canAskAgain = !blockedBySystem
            CameraPermissionRequired(
                canAskAgain = canAskAgain,
                onAllowClick = {
                    if (canAskAgain) {
                        requestPermission.launch(Manifest.permission.CAMERA)
                    } else {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CameraCapture(
    onPhotoCaptured: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // CameraController는 미리보기를 항상 켜고, 여기서는 사진 촬영 기능만 추가로 켠다.
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
    var isCapturing by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { viewContext ->
                    PreviewView(viewContext).apply {
                        this.controller = controller
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        CaptureGuide()
        Spacer(modifier = Modifier.height(20.dp))

        CaptureButton(
            isCapturing = isCapturing,
            onClick = {
                isCapturing = true
                val file = newCapturedPhotoFile(context)
                controller.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            isCapturing = false
                            onPhotoCaptured(Uri.fromFile(file))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            isCapturing = false
                            file.delete()
                            Toast.makeText(context, "촬영에 실패했어요. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            },
        )
    }
}

@Composable
private fun CaptureGuide(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            "킥보드 전체가 보이도록 촬영해 주세요.",
            "주변 보행 공간도 함께 촬영해 주세요.",
            "점자블록이나 출입구가 있다면 포함해 주세요.",
        ).forEach { guide ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = guide,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 하단 중앙의 원형 촬영 버튼. 저장이 끝날 때까지 다시 누를 수 없다. */
@Composable
private fun CaptureButton(
    isCapturing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(76.dp)
            .border(4.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = !isCapturing, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "사진 촬영" },
        contentAlignment = Alignment.Center,
    ) {
        if (isCapturing) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** 카메라 권한이 없을 때 보여 주는 안내. 단위 테스트에서 직접 확인할 수 있도록 internal로 둔다. */
@Composable
internal fun CameraPermissionRequired(
    canAskAgain: Boolean,
    onAllowClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.NoPhotography,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp),
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "카메라 권한이 필요해요",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (canAskAgain) {
                    "킥보드 주차 상태를 촬영하려면\n카메라 사용을 허용해 주세요."
                } else {
                    "권한이 꺼져 있어요.\n설정 > 권한에서 카메라를 허용해 주세요."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        PrimaryButton(
            text = if (canAskAgain) "카메라 권한 허용하기" else "설정에서 허용하기",
            onClick = onAllowClick,
        )
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * 촬영 사진은 앱 전용 캐시에 저장한다 (다른 앱과 공유하지 않으므로 FileProvider가 필요 없다).
 * 이전에 찍은 사진은 더 쓰지 않으므로 지워서 캐시가 쌓이지 않게 한다.
 */
private fun newCapturedPhotoFile(context: Context): File {
    val dir = File(context.cacheDir, "captured").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    return File(dir, "parking_${System.currentTimeMillis()}.jpg")
}
