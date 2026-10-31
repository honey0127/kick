package com.example.safezone.ui.photo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.safezone.ui.theme.SafeZoneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 카메라 권한이 없을 때의 안내 문구와 버튼 동작을 확인한다. */
@RunWith(AndroidJUnit4::class)
class CameraPermissionRequiredTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun canAskAgain_showsAllowButton() {
        var clicks = 0
        composeRule.setContent {
            SafeZoneTheme { CameraPermissionRequired(canAskAgain = true, onAllowClick = { clicks++ }) }
        }

        composeRule.onNodeWithText("카메라 권한이 필요해요").assertIsDisplayed()
        composeRule.onNodeWithText("카메라 권한 허용하기").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun permanentlyDenied_guidesToSettings() {
        var clicks = 0
        composeRule.setContent {
            SafeZoneTheme { CameraPermissionRequired(canAskAgain = false, onAllowClick = { clicks++ }) }
        }

        composeRule.onNodeWithText("권한이 꺼져 있어요.\n설정 > 권한에서 카메라를 허용해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("설정에서 허용하기").performClick()
        assertEquals(1, clicks)
    }
}
