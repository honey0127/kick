package com.example.safezone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 실제 MainActivity 를 띄워 첫 화면의 성공 기준을 확인한다.
 * Robolectric으로 JVM에서 돌기 때문에 기기·에뮬레이터 없이 `./gradlew testDebugUnitTest`로 실행된다.
 *
 * 홈에서 다음 화면으로 가는 흐름은 여기서 확인하지 않는다. MainActivity 는 PC 에 설정한 분석 서버 주소
 * (local.properties)에 따라 위치 확인을 거치거나 건너뛰므로, 같은 테스트가 PC 마다 다른 화면을 만나게 된다.
 * 그 흐름은 분석기를 직접 넣는 AppFlowTest(서버 없음)와 LocationFlowTest(서버 연결)에서 확인한다.
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
}
