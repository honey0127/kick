package com.example.safezone.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.safezone.ui.theme.SafeZoneTheme

/**
 * 앱의 첫 화면.
 * 화면을 그리기만 하고, 버튼을 눌렀을 때 할 일은 호출하는 쪽에서 받는다.
 * → 다음 단계에서 카메라/갤러리를 연결해도 이 파일은 거의 바뀌지 않는다.
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
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Safe Zone",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "주차한 전동 킥보드 사진으로\n보행 공간을 막고 있는지 확인합니다",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = onTakePhotoClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("사진 촬영하기")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onPickPhotoClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("갤러리에서 선택하기")
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 현재 상태를 그대로 표시한다: 분석 기능은 아직 없다.
        // 실제 분석이 연결되면 이 문구를 제거한다.
        Text(
            text = "개발 중 · 사진 분석 기능은 아직 연결되지 않았습니다",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// Preview 창에서 기기 없이 화면을 확인하기 위한 함수 (앱 실행과 무관)
@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SafeZoneTheme {
        HomeScreen(onTakePhotoClick = {}, onPickPhotoClick = {})
    }
}
