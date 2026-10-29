package com.example.safezone

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.safezone.ui.home.HomeScreen
import com.example.safezone.ui.theme.SafeZoneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 화면을 상태바·내비게이션바 뒤까지 확장 (템플릿 기본 설정 유지)
        enableEdgeToEdge()
        setContent {
            SafeZoneTheme {
                // Scaffold가 시스템 바 높이만큼의 여백(innerPadding)을 계산해 준다.
                // 이 여백을 HomeScreen에 적용해야 제목이 상태바에 가려지지 않는다.
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HomeScreen(
                        // TODO(MVP 2단계): 카메라 촬영 실행으로 교체
                        onTakePhotoClick = { showNotImplementedToast() },
                        // TODO(MVP 2단계): 갤러리(Photo Picker) 실행으로 교체
                        onPickPhotoClick = { showNotImplementedToast() },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    // 임시 동작: 없는 기능을 있는 것처럼 보이지 않도록 미구현임을 알린다.
    private fun showNotImplementedToast() {
        Toast.makeText(this, "다음 단계에서 구현할 기능입니다", Toast.LENGTH_SHORT).show()
    }
}
