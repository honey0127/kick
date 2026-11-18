package com.example.safezone

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.safezone.data.analysis.MockPhotoAnalyzer
import com.example.safezone.data.location.DeviceLocation
import com.example.safezone.data.location.DeviceLocationSource
import com.example.safezone.data.location.LocationCheckResult
import com.example.safezone.data.location.ReturnApi
import com.example.safezone.navigation.AppNavigation
import com.example.safezone.ui.theme.SafeZoneTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 서버에 연결된 경우의 반납 순서 ① 위치 확인을 확인한다.
 * 실제 GPS 와 서버 대신 정해진 위치와 정해진 판정을 돌려주는 가짜를 넣는다.
 */
@RunWith(AndroidJUnit4::class)
class LocationFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class FakeReturnApi(private val result: LocationCheckResult) : ReturnApi {
        override suspend fun checkLocation(location: DeviceLocation) = result
        override suspend fun complete() {}
    }

    private val here = object : DeviceLocationSource {
        override suspend fun current() = DeviceLocation(35.89, 128.61, 5f)
    }

    private fun start(result: LocationCheckResult) {
        shadowOf(composeRule.activity.application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
        )
        composeRule.setContent {
            SafeZoneTheme {
                AppNavigation(analyzer = MockPhotoAnalyzer(), returnApi = FakeReturnApi(result), locationSource = here)
            }
        }
    }

    @Test
    fun allowedLocation_goesToCamera() {
        start(LocationCheckResult(ok = true, message = "", zone = "북문 주차구역"))

        composeRule.onNodeWithText("사진 촬영하기").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("킥보드 전체가 보이도록 촬영해 주세요.").assertIsDisplayed()
    }

    @Test
    fun deniedLocation_showsServerMessage_andStaysOnLocationScreen() {
        start(LocationCheckResult(ok = false, message = "주차 금지 구역이에요.", zone = null))

        composeRule.onNodeWithText("사진 촬영하기").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("이 위치에는 반납할 수 없어요.").assertIsDisplayed()
        composeRule.onNodeWithText("주차 금지 구역이에요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 확인하기").assertIsDisplayed()
    }
}
