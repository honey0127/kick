package com.example.safezone

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/**
 * 핵심 사용자 흐름을 처음부터 끝까지 확인한다: 사진 선택 → 사진 확인 → 분석 진행 → 결과 확인.
 * 갤러리(Photo Picker)는 시스템 앱이라 테스트에서 열 수 없으므로, 사용자가 사진을 고른 결과를 직접 넣어 준다.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
class AppFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun useFakeImageLoader() {
        // Robolectric에서는 실제 사진을 디코딩하지 못해 미리보기가 "불러오지 못함" 상태가 되고 분석 버튼이 막힌다.
        // 어떤 사진이든 성공적으로 불러온 것처럼 가짜 이미지를 돌려주게 한다.
        val engine = FakeImageLoaderEngine.Builder().default(FakeImage()).build()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(composeRule.activity).components { add(engine) }.build(),
        )
    }

    @After
    fun resetImageLoader() = SingletonImageLoader.reset()

    /** 갤러리 버튼을 누르고, 사진 선택기에서 사진 한 장을 고른 것처럼 결과를 돌려준다. */
    private fun pickPhotoFromGallery() {
        composeRule.onNodeWithText("갤러리에서 선택하기").performClick()
        composeRule.waitForIdle()

        val activity = composeRule.activity
        val pickerIntent = shadowOf(activity).nextStartedActivityForResult.intent
        val pickedPhoto = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1")
        shadowOf(activity).receiveResult(pickerIntent, Activity.RESULT_OK, Intent().setData(pickedPhoto))
        composeRule.waitForIdle()
    }

    /** MockPhotoAnalyzer의 분석 대기 시간(2초)을 테스트 시계로 건너뛴다. */
    private fun finishAnalysis() {
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        composeRule.waitForIdle()
    }

    @Test
    fun gallery_preview_analyze_result_home() {
        pickPhotoFromGallery()
        composeRule.onNodeWithText("사진을 확인해 주세요.").assertIsDisplayed()

        composeRule.onNodeWithText("주차 상태 분석하기").performClick()
        composeRule.onNodeWithText("주차 상태를 확인하고 있어요.").assertIsDisplayed()

        finishAnalysis()
        // Mock 분석기의 첫 결과는 SAFE이고, 임시 결과라는 안내가 함께 보여야 한다.
        composeRule.onNodeWithText("주차 상태가 양호해요!").assertIsDisplayed()
        composeRule.onNodeWithText("테스트용 임시 결과예요. 실제 AI 분석이 아닙니다.").assertIsDisplayed()

        composeRule.onNodeWithText("홈으로 돌아가기").performClick()
        composeRule.onNodeWithText("안전한 주차,\n올바른 보행의 시작").assertIsDisplayed()
    }

    @Test
    fun backDuringAnalysis_returnsToPreview_andCancels() {
        pickPhotoFromGallery()
        composeRule.onNodeWithText("주차 상태 분석하기").performClick()
        composeRule.onNodeWithText("주차 상태를 확인하고 있어요.").assertIsDisplayed()

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("사진을 확인해 주세요.").assertIsDisplayed()

        // 취소한 분석의 결과가 뒤늦게 도착해 결과 화면으로 넘어가면 안 된다.
        finishAnalysis()
        composeRule.onNodeWithText("사진을 확인해 주세요.").assertIsDisplayed()
    }

    @Test
    fun backFromResult_goesHome_notBackToAnalysis() {
        pickPhotoFromGallery()
        composeRule.onNodeWithText("주차 상태 분석하기").performClick()
        finishAnalysis()
        composeRule.onNodeWithText("주차 상태가 양호해요!").assertIsDisplayed()

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("안전한 주차,\n올바른 보행의 시작").assertIsDisplayed()
    }
}
