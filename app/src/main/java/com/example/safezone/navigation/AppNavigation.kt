package com.example.safezone.navigation

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.safezone.data.analysis.MockPhotoAnalyzer
import com.example.safezone.data.model.ParkingStatus
import com.example.safezone.ui.analysis.AnalysisUiState
import com.example.safezone.ui.analysis.AnalysisViewModel
import com.example.safezone.ui.analysis.AnalyzingScreen
import com.example.safezone.ui.home.HomeScreen
import com.example.safezone.ui.photo.PhotoPreviewScreen
import com.example.safezone.ui.photo.PhotoSelectScreen
import com.example.safezone.ui.result.ResultScreen

/** 사진을 어디서 가져왔는지. "다시 선택하기"가 같은 곳으로 돌아가는 데 쓴다. */
enum class PhotoSource { CAMERA, GALLERY }

/** 화면 경로. 사진 URI는 문자열로 인코딩해 경로에 담는다. */
object Routes {
    const val HOME = "home"
    const val PHOTO_SELECT = "photo_select"
    const val PHOTO_PREVIEW = "photo_preview/{source}/{uri}"
    const val ANALYZING = "analyzing"
    const val RESULT = "result"

    fun photoPreview(source: PhotoSource, uri: Uri): String =
        "photo_preview/${source.name}/${Uri.encode(uri.toString())}"
}

@Composable
fun AppNavigation(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    // 분석 화면과 결과 화면이 같은 결과를 보도록 Activity 범위의 ViewModel 하나를 함께 쓴다.
    // ⚠️ 지금은 UI 확인용 MockPhotoAnalyzer. 실제 AI 분석이 준비되면 여기서 구현체만 교체한다.
    val analysisViewModel: AnalysisViewModel = viewModel {
        AnalysisViewModel(analyzer = MockPhotoAnalyzer())
    }

    // 결과 화면에서 다시 찍으러 갈 때: 홈 위에 촬영 화면만 남긴다.
    val retakePhoto = {
        navController.navigate(Routes.PHOTO_SELECT) { popUpTo(Routes.HOME) }
    }
    val goHome = {
        navController.popBackStack(Routes.HOME, inclusive = false)
        Unit
    }

    // Android Photo Picker. 권한 없이 사용자가 고른 사진 1장만 앱에 전달된다.
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        // 사용자가 선택 없이 닫으면 null → 현재 화면에 그대로 머문다.
        if (uri != null) {
            navController.navigate(Routes.photoPreview(PhotoSource.GALLERY, uri)) {
                // 다시 고른 경우 이전 미리보기를 쌓지 않고 교체한다.
                popUpTo(Routes.HOME)
            }
        }
    }
    val launchPhotoPicker = {
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onTakePhotoClick = { navController.navigate(Routes.PHOTO_SELECT) },
                onPickPhotoClick = launchPhotoPicker,
            )
        }

        composable(Routes.PHOTO_SELECT) {
            PhotoSelectScreen(
                onBackClick = { navController.popBackStack() },
                // 촬영 화면은 스택에 남겨 둔다 → 미리보기에서 "다시 선택하기"를 누르면 여기로 돌아온다.
                onPhotoCaptured = { uri ->
                    navController.navigate(Routes.photoPreview(PhotoSource.CAMERA, uri))
                },
            )
        }

        composable(
            route = Routes.PHOTO_PREVIEW,
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("uri") { type = NavType.StringType },
            ),
        ) { entry ->
            val args = requireNotNull(entry.arguments)
            val source = PhotoSource.valueOf(requireNotNull(args.getString("source")))
            val photoUri = requireNotNull(args.getString("uri"))

            PhotoPreviewScreen(
                photoUri = photoUri,
                onBackClick = { navController.popBackStack() },
                onReselectClick = {
                    when (source) {
                        PhotoSource.GALLERY -> launchPhotoPicker()
                        PhotoSource.CAMERA -> navController.popBackStack()
                    }
                },
                onAnalyzeClick = {
                    analysisViewModel.analyze(photoUri)
                    // 버튼을 빠르게 두 번 눌러도 분석 화면이 두 번 쌓이지 않게 한다.
                    navController.navigate(Routes.ANALYZING) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.ANALYZING) {
            val state by analysisViewModel.uiState.collectAsStateWithLifecycle()
            val leave = {
                analysisViewModel.cancel()
                navController.popBackStack()
                Unit
            }
            BackHandler(onBack = leave)

            // 분석이 끝나면 결과 화면으로. 분석 화면은 스택에서 빼서 결과에서 뒤로 가면 홈으로 간다.
            LaunchedEffect(state) {
                if (state is AnalysisUiState.Success) {
                    navController.navigate(Routes.RESULT) { popUpTo(Routes.HOME) }
                }
            }

            AnalyzingScreen(
                isFailed = state is AnalysisUiState.Failed,
                onRetryClick = analysisViewModel::retry,
                onBackClick = leave,
            )
        }

        composable(Routes.RESULT) {
            val state by analysisViewModel.uiState.collectAsStateWithLifecycle()
            val result = (state as? AnalysisUiState.Success)?.result

            if (result == null) {
                // 앱이 백그라운드에서 종료됐다 복원된 경우 등 보여 줄 결과가 없으면 홈으로 돌아간다.
                LaunchedEffect(Unit) { goHome() }
            } else {
                ResultScreen(
                    result = result,
                    onPrimaryClick = {
                        when (result.status) {
                            ParkingStatus.SAFE -> goHome()
                            ParkingStatus.UNSAFE, ParkingStatus.UNCERTAIN -> retakePhoto()
                        }
                    },
                    onHomeClick = goHome,
                )
            }
        }
    }
}
