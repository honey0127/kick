package com.example.safezone.navigation

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.safezone.BuildConfig
import com.example.safezone.data.analysis.MockPhotoAnalyzer
import com.example.safezone.data.analysis.PhotoAnalyzer
import com.example.safezone.data.analysis.RemotePhotoAnalyzer
import com.example.safezone.data.location.AndroidLocationSource
import com.example.safezone.data.location.DeviceLocationSource
import com.example.safezone.data.location.RemoteReturnApi
import com.example.safezone.data.location.ReturnApi
import com.example.safezone.data.location.ReturnSession
import com.example.safezone.data.model.ParkingStatus
import java.io.IOException
import com.example.safezone.ui.analysis.AnalysisUiState
import com.example.safezone.ui.analysis.AnalysisViewModel
import com.example.safezone.ui.analysis.AnalyzingScreen
import com.example.safezone.ui.home.HomeScreen
import com.example.safezone.ui.location.LocationCheckScreen
import com.example.safezone.ui.location.LocationUiState
import com.example.safezone.ui.location.ReturnViewModel
import com.example.safezone.ui.photo.PhotoPreviewScreen
import com.example.safezone.ui.photo.PhotoSelectScreen
import com.example.safezone.ui.result.ResultScreen

/** 사진을 어디서 가져왔는지. "다시 선택하기"가 같은 곳으로 돌아가는 데 쓴다. */
enum class PhotoSource { CAMERA, GALLERY }

/** 화면 경로. 사진 URI는 문자열로 인코딩해 경로에 담는다. */
object Routes {
    const val HOME = "home"
    const val LOCATION_CHECK = "location_check/{target}"
    const val PHOTO_SELECT = "photo_select"
    const val PHOTO_PREVIEW = "photo_preview/{source}/{uri}"
    const val ANALYZING = "analyzing"
    const val RESULT = "result"

    /** 위치 확인 화면. 통과하면 target(촬영 또는 갤러리)으로 이어진다. */
    fun locationCheck(target: PhotoSource): String = "location_check/${target.name}"

    fun photoPreview(source: PhotoSource, uri: Uri): String =
        "photo_preview/${source.name}/${Uri.encode(uri.toString())}"
}

/**
 * 앱이 함께 쓰는 서버 연결. ViewModel 로 들고 있어서 화면이 회전해도 같은 반납 토큰(ReturnSession)을 쓴다.
 * returnApi 가 null 이면(임시 분석기) 위치 확인 단계 없이 바로 사진으로 간다.
 */
internal class AppServices(
    val analyzer: PhotoAnalyzer,
    val returnApi: ReturnApi?,
    val locationSource: DeviceLocationSource?,
) : ViewModel()

/**
 * 서버 주소(BuildConfig.ANALYSIS_SERVER_URL)가 있으면 실제 AI 서버로 ① 위치 확인 → ② 사진 판정 → ③ 반납 완료,
 * 없으면 UI 확인용 임시 분석기만 쓴다 (위치 확인 없음).
 */
private fun createServices(context: Context): AppServices {
    val serverUrl = BuildConfig.ANALYSIS_SERVER_URL
    if (serverUrl.isBlank()) return AppServices(MockPhotoAnalyzer(), null, null)
    val session = ReturnSession()
    return AppServices(
        analyzer = RemotePhotoAnalyzer(
            serverUrl = serverUrl,
            readPhoto = { uri ->
                context.contentResolver.openInputStream(uri.toUri())?.use { it.readBytes() }
                    ?: throw IOException("사진을 열 수 없어요: $uri")
            },
            session = session,
        ),
        returnApi = RemoteReturnApi(serverUrl, session),
        locationSource = AndroidLocationSource(context),
    )
}

/**
 * @param analyzer 사진 분석기. 앱에서는 비워 두면 서버 주소 설정에 따라 자동으로 고른다.
 *   테스트에서는 여기에 임시 분석기를 직접 넣어, PC 의 서버 주소 설정(local.properties)과 무관하게 같은 결과가 나오게 한다.
 * @param returnApi 위치 확인·반납 완료 API. 테스트에서 위치 확인 단계를 확인할 때 locationSource 와 함께 넣는다.
 *   analyzer 만 넣으면 위치 확인 없이 진행한다.
 */
@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    analyzer: PhotoAnalyzer? = null,
    returnApi: ReturnApi? = null,
    locationSource: DeviceLocationSource? = null,
) {
    val navController = rememberNavController()

    val appContext = LocalContext.current.applicationContext
    val services: AppServices = viewModel {
        if (analyzer != null) AppServices(analyzer, returnApi, locationSource) else createServices(appContext)
    }
    // 분석 화면과 결과 화면이 같은 결과를 보도록 Activity 범위의 ViewModel 하나를 함께 쓴다.
    val analysisViewModel: AnalysisViewModel = viewModel { AnalysisViewModel(analyzer = services.analyzer) }
    val api = services.returnApi
    val source = services.locationSource
    val returnViewModel: ReturnViewModel? =
        if (api != null && source != null) viewModel { ReturnViewModel(api, source) } else null

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

    // 홈에서 반납을 시작할 때: 서버에 연결된 경우 위치 확인부터, 아니면 바로 사진으로.
    fun startReturn(target: PhotoSource) {
        if (returnViewModel == null) {
            when (target) {
                PhotoSource.CAMERA -> navController.navigate(Routes.PHOTO_SELECT)
                PhotoSource.GALLERY -> launchPhotoPicker()
            }
        } else {
            returnViewModel.reset()
            navController.navigate(Routes.locationCheck(target))
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onTakePhotoClick = { startReturn(PhotoSource.CAMERA) },
                onPickPhotoClick = { startReturn(PhotoSource.GALLERY) },
            )
        }

        composable(
            route = Routes.LOCATION_CHECK,
            arguments = listOf(navArgument("target") { type = NavType.StringType }),
        ) { entry ->
            val target = PhotoSource.valueOf(requireNotNull(entry.arguments?.getString("target")))
            val vm = requireNotNull(returnViewModel) { "위치 확인은 서버에 연결된 경우에만 열린다" }
            val state by vm.uiState.collectAsStateWithLifecycle()

            // 반납 가능한 위치면 고른 방법(촬영/갤러리)으로 사진을 받으러 간다. 위치 확인 화면은 스택에서 뺀다.
            LaunchedEffect(state) {
                if (state is LocationUiState.Allowed) {
                    when (target) {
                        PhotoSource.CAMERA -> navController.navigate(Routes.PHOTO_SELECT) { popUpTo(Routes.HOME) }
                        PhotoSource.GALLERY -> {
                            navController.popBackStack()
                            launchPhotoPicker()
                        }
                    }
                }
            }

            LocationCheckScreen(
                state = state,
                onCheck = vm::check,
                onBackClick = {
                    vm.reset()
                    navController.popBackStack()
                },
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
                            ParkingStatus.SAFE -> {
                                // 서버에 연결된 경우 반납 완료를 알린다 (③). 임시 분석기에서는 아무것도 하지 않는다.
                                returnViewModel?.complete()
                                goHome()
                            }
                            ParkingStatus.UNSAFE, ParkingStatus.UNCERTAIN -> retakePhoto()
                        }
                    },
                    onHomeClick = goHome,
                )
            }
        }
    }
}
