package com.example.safezone.ui.location

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.safezone.ui.theme.SafeZoneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** 위치 권한 수준에 따라 위치 확인을 시작하거나, 필요한 권한을 안내하는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class LocationCheckScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var checks = 0

    private fun show(vararg grantedPermissions: String) {
        if (grantedPermissions.isNotEmpty()) {
            shadowOf(composeRule.activity.application).grantPermissions(*grantedPermissions)
        }
        composeRule.setContent {
            SafeZoneTheme {
                LocationCheckScreen(state = LocationUiState.Idle, onCheck = { checks++ }, onBackClick = {})
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun preciseLocationPermission_startsLocationCheck() {
        show(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(1, checks)
        composeRule.onNodeWithText("현재 위치를 확인하고 있어요.").assertIsDisplayed()
    }

    @Test
    fun approximateLocationOnly_asksForPreciseLocation_andDoesNotCheck() {
        // Android 12 이상에서 권한 창의 '대략적 위치'를 고른 상태
        show(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals("대략적 위치로는 서버에 위치를 묻지 않아야 한다", 0, checks)
        composeRule.onNodeWithText("정확한 위치 권한이 필요해요.").assertIsDisplayed()
        // 권한을 다시 요청하는 버튼, 또는 (시스템이 요청 창을 더 띄우지 않으면) 설정으로 가는 버튼이 나온다.
        // 테스트 환경에서는 권한 요청이 곧바로 거부로 끝나므로 어느 쪽이든 받아들인다.
        composeRule.onNode(hasText("정확한 위치 허용하기") or hasText("설정에서 허용하기")).assertIsDisplayed()
    }

    @Test
    fun noLocationPermission_asksForPermission_andDoesNotCheck() {
        show()

        assertEquals(0, checks)
        composeRule.onNodeWithText("위치 권한이 필요해요.").assertIsDisplayed()
        composeRule.onNode(hasText("위치 권한 허용하기") or hasText("설정에서 허용하기")).assertIsDisplayed()
    }
}
