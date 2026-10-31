package com.example.safezone.ui.photo

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.safezone.ui.components.BackTopBar
import com.example.safezone.ui.components.PrimaryButton
import com.example.safezone.ui.components.SecondaryButton

/**
 * 분석 요청 전에 고른 사진을 확인하는 화면.
 * 사진은 원본 비율을 유지한 채(ContentScale.Fit) 남는 공간에 맞춰 표시한다.
 */
@Composable
fun PhotoPreviewScreen(
    photoUri: String,
    onBackClick: () -> Unit,
    onReselectClick: () -> Unit,
    onAnalyzeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(onBackClick = onBackClick)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "사진을 확인해 주세요.",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = photoUri,
                    contentDescription = "선택한 주차 사진",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "사진에 킥보드와 주변 보행 공간이 잘 보이는지 확인해 주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = "다시 선택하기",
                    onClick = onReselectClick,
                    modifier = Modifier.weight(2f),
                )
                PrimaryButton(
                    text = "주차 상태 분석하기",
                    onClick = onAnalyzeClick,
                    modifier = Modifier.weight(3f),
                )
            }
        }
    }
}
