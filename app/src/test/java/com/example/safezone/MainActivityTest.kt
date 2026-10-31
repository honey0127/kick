package com.example.safezone

import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * 첫 화면의 성공 기준을 확인한다.
 * Robolectric으로 JVM에서 돌기 때문에 기기·에뮬레이터 없이 `./gradlew testDebugUnitTest`로 실행된다.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreen_showsLogoTitleAndBothButtons() {
        composeRule.onNodeWithText("Safe Zone").assertIsDisplayed()
        composeRule.onNodeWithText("안전한 주차,\n올바른 보행의 시작").assertIsDisplayed()
        composeRule.onNodeWithText("사진 촬영하기").assertIsDisplayed()
        composeRule.onNodeWithText("갤러리에서 선택하기").assertIsDisplayed()
    }

    @Test
    fun galleryButton_launchesPhotoPicker() {
        composeRule.onNodeWithText("갤러리에서 선택하기").performClick()
        composeRule.waitForIdle()

        val started = shadowOf(composeRule.activity).nextStartedActivityForResult
        assertNotNull("갤러리 버튼이 사진 선택 화면을 열지 않았습니다", started)
        // 기기 버전에 따라 Photo Picker 또는 문서 선택기로 열린다. 둘 다 이미지 선택용이다.
        val action = started.intent.action
        assert(action == MediaStore.ACTION_PICK_IMAGES || action == android.content.Intent.ACTION_OPEN_DOCUMENT) {
            "예상하지 못한 intent action: $action"
        }
    }

    @Test
    fun cameraButton_showsNotImplementedToast() {
        // 촬영 화면은 4단계에서 연결한다. 그전까지는 안내 토스트만 띄운다.
        composeRule.onNodeWithText("사진 촬영하기").performClick()
        composeRule.waitForIdle()
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals("다음 단계에서 구현할 기능입니다", ShadowToast.getTextOfLatestToast())
    }
}
