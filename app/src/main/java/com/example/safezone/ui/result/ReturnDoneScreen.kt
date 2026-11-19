package com.example.safezone.ui.result

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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.safezone.ui.components.PrimaryButton
import com.example.safezone.ui.theme.SafeZoneTheme

/**
 * 반납 순서 ③ 반납 완료 화면. 서버가 반납 완료를 확인해 준 뒤에만 보여 준다.
 * (임시 분석기에는 반납 단계가 없으므로 이 화면이 나오지 않는다)
 *
 * @param zone 지정 주차구역에 반납한 경우 그 이름
 * @param returnId 서버가 준 반납 번호
 */
@Composable
fun ReturnDoneScreen(
    zone: String?,
    returnId: String?,
    onHomeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
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
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(96.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "반납이 완료됐어요.",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            // 서버가 실제로 돌려준 값만 보여 준다.
            if (zone != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = zone,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (returnId != null) {
                Spacer(modifier = Modifier.height(if (zone != null) 4.dp else 12.dp))
                Text(
                    text = "반납 번호 $returnId",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        PrimaryButton(text = "홈으로 돌아가기", onClick = onHomeClick, icon = Icons.Outlined.Home)
    }
}

// Preview 창에서 기기 없이 화면을 확인하기 위한 함수 (앱 실행과 무관)
@Preview(showBackground = true, heightDp = 780)
@Composable
private fun ReturnDoneScreenPreview() {
    SafeZoneTheme {
        ReturnDoneScreen(zone = "경북대 북문 앞 지정 주차구역", returnId = "RT1E3B1846", onHomeClick = {})
    }
}
