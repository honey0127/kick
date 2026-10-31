package com.example.safezone

import android.Manifest
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

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
    fun cameraButton_withPermission_showsCaptureGuideAndShutter() {
        shadowOf(composeRule.activity.application).grantPermissions(Manifest.permission.CAMERA)

        composeRule.onNodeWithText("사진 촬영하기").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("킥보드 전체가 보이도록 촬영해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("주변 보행 공간도 함께 촬영해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("점자블록이나 출입구가 있다면 포함해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("사진 촬영").assertIsDisplayed()
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
}
