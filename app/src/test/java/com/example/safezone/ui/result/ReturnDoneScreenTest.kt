package com.example.safezone.ui.result

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

/** 반납 완료 화면이 서버가 돌려준 값(구역 이름, 반납 번호)만 보여 주는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class ReturnDoneScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var homeClicks = 0

    private fun show(zone: String?, returnId: String?) {
        composeRule.setContent {
            SafeZoneTheme {
                ReturnDoneScreen(zone = zone, returnId = returnId, onHomeClick = { homeClicks++ })
            }
        }
    }

    @Test
    fun showsZoneAndReturnNumber_andHomeButton() {
        show(zone = "북문 주차구역", returnId = "RT1E3B1846")

        composeRule.onNodeWithText("반납이 완료됐어요.").assertIsDisplayed()
        composeRule.onNodeWithText("북문 주차구역").assertIsDisplayed()
        composeRule.onNodeWithText("반납 번호 RT1E3B1846").assertIsDisplayed()
        composeRule.onNodeWithText("홈으로 돌아가기").performClick()
        assertEquals(1, homeClicks)
    }

    @Test
    fun withoutZoneOrNumber_showsNoPlaceholder() {
        // 지정 주차구역이 아닌 곳에 반납했고 반납 번호도 오지 않은 경우: 없는 값을 지어내 채우지 않는다.
        show(zone = null, returnId = null)

        composeRule.onNodeWithText("반납이 완료됐어요.").assertIsDisplayed()
        composeRule.onNodeWithText("반납 번호", substring = true).assertDoesNotExist()
    }
}
