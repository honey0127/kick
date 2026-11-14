package com.example.safezone.ui.result

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.safezone.data.model.AnalysisResult
import com.example.safezone.data.model.ParkingStatus
import com.example.safezone.ui.components.PrimaryButton
import com.example.safezone.ui.theme.SafeZoneTheme

/**
 * 분석 결과 화면. SAFE / UNSAFE / UNCERTAIN을 별도 화면 없이 이 화면 하나에서 표현한다.
 *
 * @param onPrimaryClick 상태별 메인 버튼 (SAFE: 홈, UNSAFE·UNCERTAIN: 다시 촬영)
 * @param onHomeClick UNSAFE에서 메인 버튼 아래 "홈으로" 버튼
 */
@Composable
fun ResultScreen(
    result: AnalysisResult,
    onPrimaryClick: () -> Unit,
    onHomeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = result.status.style()

    Column(modifier = modifier.fillMaxSize()) {
        // ① 상단 로고 (결과 화면에서 뒤로 가면 홈으로 가므로 뒤로 가기 버튼 대신 로고를 둔다)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Safe Zone",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // 근거가 많아도 잘리지 않도록 내용 영역만 스크롤한다. 하단 버튼은 고정.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (result.isMock) {
                Spacer(modifier = Modifier.height(8.dp))
                MockResultNotice()
            }
            Spacer(modifier = Modifier.height(32.dp))

            // ② 결과 아이콘
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(96.dp)
                    .background(style.color.copy(alpha = 0.1f), CircleShape),
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color,
                    modifier = Modifier.size(56.dp),
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            // ③ 결과 제목 ④ 설명
            Text(
                text = style.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = style.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // ⑤ 분석 근거: 결과에 담긴 항목만 표시한다. 없으면 카드 자체를 그리지 않는다.
            if (result.findings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))
                FindingsCard(findings = result.findings, accent = style.color)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // ⑥ 하단 버튼
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(text = style.buttonText, onClick = onPrimaryClick, icon = style.buttonIcon)
            if (result.status == ParkingStatus.UNSAFE) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onHomeClick) {
                    Text(
                        text = "홈으로",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 실제 AI 분석이 아니라는 사실을 결과보다 먼저 보이게 한다. */
@Composable
private fun MockResultNotice() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "테스트용 임시 결과예요. 실제 AI 분석이 아닙니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FindingsCard(findings: List<String>, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "분석 근거",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        findings.forEach { finding ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(accent, CircleShape),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = finding,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/** 상태별 화면 구성 값 (명세서 4-5). */
private data class ResultStyle(
    val icon: ImageVector,
    val color: Color,
    val title: String,
    val description: String,
    val buttonText: String,
    val buttonIcon: ImageVector,
)

@Composable
private fun ParkingStatus.style(): ResultStyle = when (this) {
    ParkingStatus.SAFE -> ResultStyle(
        icon = Icons.Outlined.CheckCircle,
        color = MaterialTheme.colorScheme.primary,
        title = "주차 상태가 양호해요!",
        description = "보행 공간을 방해하는 요소가\n뚜렷하게 발견되지 않았습니다.",
        buttonText = "홈으로 돌아가기",
        buttonIcon = Icons.Outlined.Home,
    )
    ParkingStatus.UNSAFE -> ResultStyle(
        icon = Icons.Outlined.WarningAmber,
        color = MaterialTheme.colorScheme.error,
        title = "주차 위치를 확인해 주세요!",
        description = "킥보드가 보행 공간을 침범하고 있을\n가능성이 있습니다.",
        buttonText = "다시 주차하기",
        buttonIcon = Icons.Outlined.Refresh,
    )
    ParkingStatus.UNCERTAIN -> ResultStyle(
        icon = Icons.AutoMirrored.Outlined.HelpOutline,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        title = "조금 더 확인이 필요해요.",
        description = "사진만으로 주차 상태를\n정확하게 판단하기 어렵습니다.",
        buttonText = "다시 촬영하기",
        buttonIcon = Icons.Outlined.PhotoCamera,
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun ResultUnsafePreview() {
    SafeZoneTheme {
        ResultScreen(
            result = AnalysisResult(
                status = ParkingStatus.UNSAFE,
                findings = listOf("보행 공간 침범 가능성", "점자블록 침범 가능성", "통행 방해 가능성"),
                isMock = true,
            ),
            onPrimaryClick = {},
            onHomeClick = {},
        )
    }
}
