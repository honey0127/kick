package com.example.safezone.ui.analysis

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
import androidx.compose.material.icons.outlined.ElectricScooter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.safezone.ui.components.BackTopBar
import com.example.safezone.ui.components.PrimaryButton
import com.example.safezone.ui.theme.SafeZoneTheme

/**
 * 분석 진행 화면. 분석 중에는 로딩을, 실패하면 오류 안내와 재시도 버튼을 보여 준다.
 * 분석 성공 시 결과 화면으로 넘어가는 일은 호출하는 쪽(AppNavigation)이 맡는다.
 */
@Composable
fun AnalyzingScreen(
    isFailed: Boolean,
    onRetryClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(onBackClick = onBackClick)

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
                if (isFailed) {
                    StatusIcon(icon = Icons.Outlined.ErrorOutline, showProgress = false)
                    Spacer(modifier = Modifier.height(28.dp))
                    Title("분석하지 못했어요.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Description("일시적인 문제가 생겼어요.\n잠시 후 다시 시도해 주세요.")
                } else {
                    StatusIcon(icon = Icons.Outlined.ElectricScooter, showProgress = true)
                    Spacer(modifier = Modifier.height(28.dp))
                    Title("주차 상태를 확인하고 있어요.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Description("보행 공간을 방해하는 요소가 있는지\n분석하고 있습니다.")
                }
            }

            if (isFailed) {
                PrimaryButton(text = "다시 시도하기", onClick = onRetryClick)
            } else {
                Text(
                    text = "잠시만 기다려 주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 원형 배경 위의 아이콘. 분석 중에는 둘레에 진행 표시를 두른다. */
@Composable
private fun StatusIcon(icon: ImageVector, showProgress: Boolean) {
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
                tint = if (showProgress) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(44.dp),
            )
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Description(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun AnalyzingScreenPreview() {
    SafeZoneTheme { AnalyzingScreen(isFailed = false, onRetryClick = {}, onBackClick = {}) }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun AnalyzingFailedPreview() {
    SafeZoneTheme { AnalyzingScreen(isFailed = true, onRetryClick = {}, onBackClick = {}) }
}
