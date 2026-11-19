package com.example.safezone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.test.FakeImage
import coil3.test.FakeImageLoaderEngine
import com.example.safezone.data.analysis.MockPhotoAnalyzer
import com.example.safezone.navigation.AppNavigation
import com.example.safezone.ui.theme.SafeZoneTheme
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/**
 * 서버 주소를 설정하지 않은 경우(임시 분석기)의 사용자 흐름을 확인한다:
 * 홈 → 촬영 화면 또는 사진 선택 → 사진 확인 → 분석 진행 → 결과 확인.
 * 갤러리(Photo Picker)는 시스템 앱이라 테스트에서 열 수 없으므로, 사용자가 사진을 고른 결과를 직접 넣어 준다.
 *
 * 분석기는 항상 임시 분석기(MockPhotoAnalyzer)를 넣는다. MainActivity를 그대로 띄우면 PC에 설정한
 * 분석 서버 주소(local.properties)에 따라 실제 서버로 요청을 보내게 되어, 테스트 결과가 PC마다 달라진다.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
class AppFlowTest {

    // 빈 Activity 위에 앱 화면(AppNavigation)을 직접 올린다.
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        // Robolectric에서는 실제 사진을 디코딩하지 못해 미리보기가 "불러오지 못함" 상태가 되고 분석 버튼이 막힌다.
        // 어떤 사진이든 성공적으로 불러온 것처럼 가짜 이미지를 돌려주게 한다.
        val engine = FakeImageLoaderEngine.Builder().default(FakeImage()).build()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(composeRule.activity).components { add(engine) }.build(),
        )

        val analyzer = MockPhotoAnalyzer()
        composeRule.setContent {
            SafeZoneTheme { AppNavigation(analyzer = analyzer) }
        }
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
    fun cameraButton_withPermission_showsFrameGuideAndShutter() {
        shadowOf(composeRule.activity.application).grantPermissions(Manifest.permission.CAMERA)

        composeRule.onNodeWithText("사진 촬영하기").performClick()
        composeRule.waitForIdle()

        // 미리보기 위의 촬영 틀 안내와, 그 아래 촬영 안내 3줄
        composeRule.onNodeWithText("킥보드 전체와 보도 양쪽 끝이 틀 안에 들어오게").assertIsDisplayed()
        composeRule.onNodeWithText("킥보드 전체가 파란 틀 안에 들어오게 촬영해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("2~3걸음 뒤에서 보도가 뻗은 방향으로, 양옆 보도까지 함께 촬영해 주세요.").assertIsDisplayed()
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
        assert(action == MediaStore.ACTION_PICK_IMAGES || action == Intent.ACTION_OPEN_DOCUMENT) {
            "예상하지 못한 intent action: $action"
        }
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
