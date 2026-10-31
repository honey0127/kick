package com.example.safezone.navigation

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.safezone.ui.home.HomeScreen
import com.example.safezone.ui.photo.PhotoPreviewScreen

/** 사진을 어디서 가져왔는지. "다시 선택하기"가 같은 곳으로 돌아가는 데 쓴다. */
enum class PhotoSource { CAMERA, GALLERY }

/** 화면 경로. 사진 URI는 문자열로 인코딩해 경로에 담는다. */
object Routes {
    const val HOME = "home"
    const val PHOTO_PREVIEW = "photo_preview/{source}/{uri}"

    fun photoPreview(source: PhotoSource, uri: Uri): String =
        "photo_preview/${source.name}/${Uri.encode(uri.toString())}"
}

@Composable
fun AppNavigation(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // 임시 동작: 아직 연결되지 않은 기능은 있는 것처럼 보이지 않도록 미구현임을 알린다.
    val showNotImplemented = {
        Toast.makeText(context, "다음 단계에서 구현할 기능입니다", Toast.LENGTH_SHORT).show()
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
                // TODO(4단계): PhotoSelectScreen으로 이동
                onTakePhotoClick = showNotImplemented,
                onPickPhotoClick = launchPhotoPicker,
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
                        // TODO(4단계): 촬영 화면으로 돌아가기
                        PhotoSource.CAMERA -> navController.popBackStack()
                    }
                },
                // TODO(5단계): AnalyzingScreen으로 이동
                onAnalyzeClick = showNotImplemented,
            )
        }
    }
}
