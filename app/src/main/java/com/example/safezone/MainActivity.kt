package com.example.safezone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.safezone.navigation.AppNavigation
import com.example.safezone.ui.theme.SafeZoneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 화면을 상태바·내비게이션바 뒤까지 확장 (템플릿 기본 설정 유지)
        enableEdgeToEdge()
        setContent {
            SafeZoneTheme {
                // Scaffold가 시스템 바 높이만큼의 여백(innerPadding)을 계산해 준다.
                // 이 여백을 화면에 적용해야 내용이 상태바·내비게이션바에 가려지지 않는다.
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
