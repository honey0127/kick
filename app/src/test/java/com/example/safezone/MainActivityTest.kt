package com.example.safezone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowToast

/**
 * 첫 화면의 성공 기준을 확인한다: 화면이 뜨고, 아직 연결 안 된 버튼은 안내 토스트만 띄운다.
 * Robolectric으로 JVM에서 돌기 때문에 기기·에뮬레이터 없이 `./gradlew testDebugUnitTest`로 실행된다.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreen_showsTitleAndBothButtons() {
        composeRule.onNodeWithText("Safe Zone").assertIsDisplayed()
        composeRule.onNodeWithText("사진 촬영하기").assertIsDisplayed()
        composeRule.onNodeWithText("갤러리에서 선택하기").assertIsDisplayed()
    }

    @Test
    fun eachButton_showsNotImplementedToast() {
        // 클릭 처리가 끝난 뒤(waitForIdle) 토스트를 확인한다.
        composeRule.onNodeWithText("사진 촬영하기").performClick()
        composeRule.waitForIdle()
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals("다음 단계에서 구현할 기능입니다", ShadowToast.getTextOfLatestToast())

        composeRule.onNodeWithText("갤러리에서 선택하기").performClick()
        composeRule.waitForIdle()
        assertEquals(2, ShadowToast.shownToastCount())
        assertEquals("다음 단계에서 구현할 기능입니다", ShadowToast.getTextOfLatestToast())
    }
}
