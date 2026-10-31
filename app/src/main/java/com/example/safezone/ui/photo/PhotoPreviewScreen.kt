package com.example.safezone.ui.photo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.safezone.ui.components.BackTopBar
import com.example.safezone.ui.components.PrimaryButton
import com.example.safezone.ui.components.SecondaryButton

// 글자 크기 1.0 기준으로 두 버튼 문구가 잘리지 않고 나란히 들어가는 버튼 영역의 최소 폭
// ("다시 선택하기"가 2/5 폭에서 한 줄로 들어가려면 약 144dp 필요 → 360dp)
private val SideBySideMinWidth = 360.dp

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
    // 갤러리 사진은 접근 권한이 앱 실행 동안만 유지된다. 앱이 종료 후 복원되면 읽지 못할 수 있다.
    var loadFailed by remember(photoUri) { mutableStateOf(false) }

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
                    onSuccess = { loadFailed = false },
                    onError = { loadFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
                if (loadFailed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.BrokenImage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "사진을 불러오지 못했어요.\n다시 선택해 주세요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "사진에 킥보드와 주변 보행 공간이 잘 보이는지 확인해 주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))

            // 두 버튼 문구가 한 줄에 들어갈 만큼 넓으면 나란히, 아니면 세로로 쌓는다.
            // (작은 폰이나 시스템 글자 크기를 키운 경우 나란히 두면 문구가 잘린다)
            BoxWithConstraints {
                val fontScale = LocalDensity.current.fontScale
                val reselect = @Composable { buttonModifier: Modifier ->
                    SecondaryButton(text = "다시 선택하기", onClick = onReselectClick, modifier = buttonModifier)
                }
                val analyze = @Composable { buttonModifier: Modifier ->
                    PrimaryButton(
                        text = "주차 상태 분석하기",
                        onClick = onAnalyzeClick,
                        // 보이지 않는 사진을 분석 요청하지 않도록 막는다.
                        enabled = !loadFailed,
                        modifier = buttonModifier,
                    )
                }
                if (maxWidth >= SideBySideMinWidth * fontScale) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        reselect(Modifier.weight(2f))
                        analyze(Modifier.weight(3f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        analyze(Modifier)
                        reselect(Modifier)
                    }
                }
            }
        }
    }
}
