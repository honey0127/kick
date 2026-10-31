package com.example.safezone.ui.result

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.safezone.data.model.AnalysisResult
import com.example.safezone.data.model.ParkingStatus
import com.example.safezone.ui.theme.SafeZoneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 결과 화면이 상태별로 명세서의 문구·버튼을 보여 주고, 결과에 담긴 근거만 표시하는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class ResultScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var primaryClicks = 0
    private var homeClicks = 0

    private fun show(status: ParkingStatus, findings: List<String> = emptyList(), isMock: Boolean = false) {
        composeRule.setContent {
            SafeZoneTheme {
                ResultScreen(
                    result = AnalysisResult(status, findings, isMock),
                    onPrimaryClick = { primaryClicks++ },
                    onHomeClick = { homeClicks++ },
                )
            }
        }
    }

    @Test
    fun safe_showsGoodTitle_andHomeButton() {
        show(ParkingStatus.SAFE)

        composeRule.onNodeWithText("주차 상태가 양호해요!").assertIsDisplayed()
        composeRule.onNodeWithText("홈으로 돌아가기").performClick()
        assertEquals(1, primaryClicks)
        composeRule.onNodeWithText("홈으로").assertDoesNotExist()
    }

    @Test
    fun unsafe_showsWarning_reparkButton_andHomeButton() {
        show(ParkingStatus.UNSAFE)

        composeRule.onNodeWithText("주차 위치를 확인해 주세요!").assertIsDisplayed()
        composeRule.onNodeWithText("다시 주차하기").performClick()
        composeRule.onNodeWithText("홈으로").performClick()
        assertEquals(1, primaryClicks)
        assertEquals(1, homeClicks)
    }

    @Test
    fun uncertain_showsRetakeButton() {
        show(ParkingStatus.UNCERTAIN)

        composeRule.onNodeWithText("조금 더 확인이 필요해요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 촬영하기").performClick()
        assertEquals(1, primaryClicks)
    }

    @Test
    fun findings_showOnlyWhatResultContains() {
        show(ParkingStatus.UNSAFE, findings = listOf("점자블록 침범 가능성"))

        // 작은 화면에서는 근거 카드가 스크롤 아래에 있으므로 스크롤한 뒤 확인한다.
        composeRule.onNodeWithText("분석 근거").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("점자블록 침범 가능성").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("보행 공간 침범 가능성").assertDoesNotExist()
    }

    @Test
    fun noFindings_hidesFindingsCard() {
        show(ParkingStatus.SAFE, findings = emptyList())

        composeRule.onNodeWithText("분석 근거").assertDoesNotExist()
    }

    @Test
    fun mockResult_isLabeled_realResultIsNot() {
        show(ParkingStatus.SAFE, isMock = true)
        composeRule.onNodeWithText("테스트용 임시 결과예요. 실제 AI 분석이 아닙니다.").assertIsDisplayed()
    }

    @Test
    fun realResult_hasNoMockLabel() {
        show(ParkingStatus.SAFE, isMock = false)
        composeRule.onNodeWithText("테스트용 임시 결과예요. 실제 AI 분석이 아닙니다.").assertDoesNotExist()
    }
}
