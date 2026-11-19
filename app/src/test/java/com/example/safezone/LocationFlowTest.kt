package com.example.safezone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.test.FakeImage
import coil3.test.FakeImageLoaderEngine
import com.example.safezone.data.analysis.MockPhotoAnalyzer
import com.example.safezone.data.analysis.PhotoAnalyzer
import com.example.safezone.data.location.DeviceLocation
import com.example.safezone.data.location.DeviceLocationSource
import com.example.safezone.data.location.LocationCheckResult
import com.example.safezone.data.location.ReturnApi
import com.example.safezone.data.location.ReturnReceipt
import com.example.safezone.data.location.ReturnSessionExpiredException
import com.example.safezone.data.model.AnalysisResult
import com.example.safezone.data.model.ParkingStatus
import com.example.safezone.navigation.AppNavigation
import com.example.safezone.ui.theme.SafeZoneTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * 서버에 연결된 경우의 반납 순서(① 위치 확인 → ② 사진 판정 → ③ 반납 완료)를 화면 이동까지 확인한다.
 * 실제 GPS 와 서버 대신 정해진 위치와 정해진 판정을 돌려주는 가짜를 넣는다.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
class LocationFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** @param completeError 반납 완료 요청이 실패하는 상황을 만들 때 던질 예외 */
    private class FakeReturnApi(
        private val result: LocationCheckResult,
        private val completeError: Exception? = null,
    ) : ReturnApi {
        var locationChecks = 0
        var completions = 0

        override suspend fun checkLocation(location: DeviceLocation): LocationCheckResult {
            locationChecks++
            return result
        }

        override suspend fun complete(): ReturnReceipt {
            completions++
            completeError?.let { throw it }
            return ReturnReceipt(returnId = "RT1", zone = result.zone)
        }
    }

    private val here = object : DeviceLocationSource {
        override suspend fun current() = DeviceLocation(35.89, 128.61, 5f)
    }

    private val allowed = LocationCheckResult(ok = true, message = "", zone = "북문 주차구역")

    /** 어떤 사진이든 바로 양호(APPROVE)로 판정하는 가짜 분석기. 서버 결과처럼 임시 결과 표시는 없다. */
    private val approving = object : PhotoAnalyzer {
        override suspend fun analyze(photoUri: String) =
            AnalysisResult(ParkingStatus.SAFE, findings = emptyList(), isMock = false)
    }

    private fun start(api: ReturnApi, analyzer: PhotoAnalyzer = MockPhotoAnalyzer()) {
        shadowOf(composeRule.activity.application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
        )
        // Robolectric에서는 실제 사진을 디코딩하지 못해 사진 확인 화면의 분석 버튼이 막힌다.
        // 어떤 사진이든 성공적으로 불러온 것처럼 가짜 이미지를 돌려주게 한다.
        val engine = FakeImageLoaderEngine.Builder().default(FakeImage()).build()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(composeRule.activity).components { add(engine) }.build(),
        )
        composeRule.setContent {
            SafeZoneTheme {
                AppNavigation(analyzer = analyzer, returnApi = api, locationSource = here)
            }
        }
    }

    @After
    fun resetImageLoader() = SingletonImageLoader.reset()

    /**
     * 홈에서 갤러리 버튼을 누른다. 위치 확인을 통과하면 사진 선택기가 열리므로, 사진 한 장을 고른 것처럼 결과를 돌려준다.
     * (카메라는 테스트에서 실제로 찍을 수 없어 갤러리로 확인한다)
     */
    private fun pickPhotoFromGallery() {
        composeRule.onNodeWithText("갤러리에서 선택하기").performClick()
        composeRule.waitForIdle()

        val activity = composeRule.activity
        val picker = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("위치 확인을 통과했는데 사진 선택기가 열리지 않았습니다", picker)
        val pickedPhoto = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1")
        shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(pickedPhoto))
        composeRule.waitForIdle()
    }

    @Test
    fun allowedLocation_goesToCamera() {
        start(FakeReturnApi(allowed))

        composeRule.onNodeWithText("사진 촬영하기").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("킥보드 전체가 보이도록 촬영해 주세요.").assertIsDisplayed()
    }

    @Test
    fun deniedLocation_showsServerMessage_andStaysOnLocationScreen() {
        start(FakeReturnApi(LocationCheckResult(ok = false, message = "주차 금지 구역이에요.", zone = null)))

        composeRule.onNodeWithText("사진 촬영하기").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("이 위치에는 반납할 수 없어요.").assertIsDisplayed()
        composeRule.onNodeWithText("주차 금지 구역이에요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 확인하기").assertIsDisplayed()
    }

    @Test
    fun expiredSessionDuringAnalysis_goesBackToLocationCheck_notRetry() {
        val api = FakeReturnApi(allowed)
        // 사진을 보냈더니 서버가 "반납 토큰이 만료됐다"고 답하는 상황 (위치 확인 후 10분 경과 등)
        val expired = object : PhotoAnalyzer {
            override suspend fun analyze(photoUri: String): AnalysisResult =
                throw ReturnSessionExpiredException("반납 세션 종료 (410)")
        }
        start(api, analyzer = expired)

        pickPhotoFromGallery()
        composeRule.onNodeWithText("주차 상태 분석하기").performClick()
        composeRule.waitForIdle()

        // 같은 사진을 다시 보내도 실패하므로 "다시 시도하기"가 아니라 위치 확인으로 안내해야 한다.
        composeRule.onNodeWithText("위치 확인을 다시 해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도하기").assertDoesNotExist()

        composeRule.onNodeWithText("위치 다시 확인하기").performClick()
        composeRule.waitForIdle()

        // 위치 확인을 한 번 더 거친 뒤, 처음 고른 방법(갤러리)으로 이어져야 한다.
        assertEquals(2, api.locationChecks)
        assertNotNull("위치를 다시 확인한 뒤 사진 선택기가 열리지 않았습니다", shadowOf(composeRule.activity).nextStartedActivityForResult)
    }

    @Test
    fun approvedPhoto_completeReturn_showsDoneScreen_thenHome() {
        val api = FakeReturnApi(allowed)
        start(api, analyzer = approving)

        pickPhotoFromGallery()
        composeRule.onNodeWithText("주차 상태 분석하기").performClick()
        composeRule.waitForIdle()

        // 반납 순서에서는 "홈으로 돌아가기" 대신 반납 완료 버튼이 나오고, 누르기 전에는 서버에 알리지 않는다.
        composeRule.onNodeWithText("주차 상태가 양호해요!").assertIsDisplayed()
        composeRule.onNodeWithText("홈으로 돌아가기").assertDoesNotExist()
        assertEquals(0, api.completions)

        composeRule.onNodeWithText("반납 완료하기").performClick()
        composeRule.waitForIdle()

        assertEquals(1, api.completions)
        composeRule.onNodeWithText("반납이 완료됐어요.").assertIsDisplayed()
        composeRule.onNodeWithText("북문 주차구역").assertIsDisplayed()
        composeRule.onNodeWithText("반납 번호 RT1").assertIsDisplayed()

        composeRule.onNodeWithText("홈으로 돌아가기").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("안전한 주차,\n올바른 보행의 시작").assertIsDisplayed()
    }

    @Test
    fun completeWithExpiredSession_showsReason_andGoesBackToLocationCheck() {
        // 양호 판정을 받은 뒤 10분 넘게 두었다가 반납 완료를 누른 상황
        val api = FakeReturnApi(allowed, completeError = ReturnSessionExpiredException("반납 세션 종료 (410)"))
        start(api, analyzer = approving)

        pickPhotoFromGallery()
        composeRule.onNodeWithText("주차 상태 분석하기").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("반납 완료하기").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("반납이 완료됐어요.").assertDoesNotExist()
        composeRule.onNodeWithText("위치 확인 후 시간이 지나 반납을 완료하지 못했어요.\n위치 확인부터 다시 해 주세요.")
            .assertIsDisplayed()

        composeRule.onNodeWithText("위치 다시 확인하기").performClick()
        composeRule.waitForIdle()

        assertEquals(2, api.locationChecks)
        assertNotNull("위치를 다시 확인한 뒤 사진 선택기가 열리지 않았습니다", shadowOf(composeRule.activity).nextStartedActivityForResult)
    }
}
