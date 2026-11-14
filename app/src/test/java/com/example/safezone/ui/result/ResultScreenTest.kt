package com.example.safezone.ui.result

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.test.FakeImage
import coil3.test.FakeImageLoaderEngine
import com.example.safezone.data.model.AnalysisResult
import com.example.safezone.data.model.EvidenceImage
import com.example.safezone.data.model.EvidenceImageKind
import com.example.safezone.data.model.ParkingStatus
import com.example.safezone.ui.theme.SafeZoneTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/** 결과 화면이 상태별로 명세서의 문구·버튼을 보여 주고, 결과에 담긴 근거만 표시하는지 확인한다. */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
class ResultScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var primaryClicks = 0
    private var homeClicks = 0

    @After
    fun resetImageLoader() = SingletonImageLoader.reset()

    private fun show(
        status: ParkingStatus,
        findings: List<String> = emptyList(),
        isMock: Boolean = false,
        evidenceImages: List<EvidenceImage> = emptyList(),
    ) {
        composeRule.setContent {
            SafeZoneTheme {
                ResultScreen(
                    result = AnalysisResult(status, findings, isMock, evidenceImages),
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

    @Test
    fun evidenceImages_areShownWithCaptions() {
        // Robolectric에서는 실제 이미지를 디코딩하지 못하므로, 어떤 이미지든 불러온 것처럼 가짜 이미지를 돌려주게 한다.
        val engine = FakeImageLoaderEngine.Builder().default(FakeImage()).build()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(RuntimeEnvironment.getApplication()).components { add(engine) }.build(),
        )
        show(
            ParkingStatus.UNSAFE,
            findings = listOf("남은 통행폭 약 0.9m (점자블록 기준 실측)"),
            evidenceImages = listOf(
                EvidenceImage(EvidenceImageKind.OVERLAY, byteArrayOf(1)),
                EvidenceImage(EvidenceImageKind.BIRDS_EYE, byteArrayOf(2)),
            ),
        )

        composeRule.onNodeWithText("판정 근거 이미지").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("AI가 인식한 영역").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("AI가 인식한 영역을 색으로 표시한 사진").assertExists()
        composeRule.onNodeWithText("위에서 내려다본 모습").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("위에서 내려다본 그림").assertExists()
    }

    @Test
    fun noEvidenceImages_hidesImageCard() {
        show(ParkingStatus.UNSAFE, findings = listOf("점자블록 침범 가능성"))

        composeRule.onNodeWithText("판정 근거 이미지").assertDoesNotExist()
    }
}
