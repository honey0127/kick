package com.example.safezone.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ElectricScooter
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.safezone.ui.components.PrimaryButton
import com.example.safezone.ui.components.SecondaryButton
import com.example.safezone.ui.theme.SafeZoneTheme

/**
 * 앱의 첫 화면.
 * 화면을 그리기만 하고, 버튼을 눌렀을 때 할 일은 호출하는 쪽(AppNavigation)에서 받는다.
 */
@Composable
fun HomeScreen(
    onTakePhotoClick: () -> Unit,
    onPickPhotoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp),
    ) {
        // ① 상단: 텍스트 로고 + 안전 아이콘
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Safe Zone",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        // ② 메인 안내
        Text(
            text = "안전한 주차,\n올바른 보행의 시작",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "킥보드 주차 사진을 확인하고 보행 공간을 지켜보세요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ③ 중앙 이미지: 남는 공간을 채우되 너무 커지지 않게 제한한다.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            ParkedScooterIllustration(
                // 남는 높이를 채우되 최대 220dp까지만 (작은 화면에서는 줄어든다)
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .fillMaxHeight(),
            )
        }

        // ④ 하단 버튼
        PrimaryButton(
            text = "사진 촬영하기",
            onClick = onTakePhotoClick,
            icon = Icons.Outlined.PhotoCamera,
        )
        Spacer(modifier = Modifier.height(12.dp))
        SecondaryButton(
            text = "갤러리에서 선택하기",
            onClick = onPickPhotoClick,
            icon = Icons.Outlined.Image,
        )
    }
}

/**
 * 임시 일러스트: 킥보드가 바르게 주차된 모습을 아이콘으로 표현한다.
 * 디자인된 이미지가 준비되면 이 함수만 교체한다.
 */
@Composable
private fun ParkedScooterIllustration(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = 24.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box {
            Icon(
                imageVector = Icons.Outlined.ElectricScooter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(96.dp),
            )
            // 흰 원 위에 체크 아이콘을 올려 배지처럼 보이게 한다.
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 12.dp, y = (-4).dp)
                    .background(MaterialTheme.colorScheme.background, CircleShape)
                    .size(32.dp),
            )
        }
    }
}

// Preview 창에서 기기 없이 화면을 확인하기 위한 함수 (앱 실행과 무관)
@Preview(showBackground = true, heightDp = 780)
@Composable
private fun HomeScreenPreview() {
    SafeZoneTheme {
        HomeScreen(onTakePhotoClick = {}, onPickPhotoClick = {})
    }
}
