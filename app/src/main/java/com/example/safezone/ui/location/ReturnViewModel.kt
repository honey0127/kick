package com.example.safezone.ui.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.safezone.data.location.DeviceLocationSource
import com.example.safezone.data.location.ReturnApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 반납 위치 확인 화면의 상태. */
sealed interface LocationUiState {
    /** 아직 확인을 시작하지 않음 (위치 권한을 기다리는 중) */
    data object Idle : LocationUiState
    data object Checking : LocationUiState

    /** 반납할 수 있는 위치. 다음 단계(사진)로 넘어간다. */
    data class Allowed(val zone: String?) : LocationUiState

    /** 서버가 반납할 수 없는 위치라고 판단함 (금지구역, 서비스 지역 밖, GPS 정확도 부족 등) */
    data class Denied(val message: String) : LocationUiState

    /** 위치를 얻지 못했거나 서버에 연결하지 못함 */
    data class Failed(val message: String) : LocationUiState
}

/**
 * 반납 순서 ① 위치 확인과 ③ 반납 완료를 관리한다.
 * Activity 범위로 만들어 화면 회전 중에도 확인 결과가 유지된다.
 */
class ReturnViewModel(
    private val api: ReturnApi,
    private val locationSource: DeviceLocationSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LocationUiState>(LocationUiState.Idle)
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    /** 현재 위치를 얻어 서버에 반납 가능 여부를 묻는다. 이미 확인 중이면 무시한다. */
    fun check() {
        if (job?.isActive == true) return
        _uiState.value = LocationUiState.Checking
        job = viewModelScope.launch {
            _uiState.value = try {
                val location = locationSource.current()
                if (location == null) {
                    LocationUiState.Failed("현재 위치를 찾지 못했어요.\n위치(GPS)를 켜고 다시 시도해 주세요.")
                } else {
                    val r = api.checkLocation(location)
                    if (r.ok) LocationUiState.Allowed(r.zone) else LocationUiState.Denied(r.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LocationUiState.Failed("위치를 확인하지 못했어요.\n인터넷 연결을 확인하고 다시 시도해 주세요.")
            }
        }
    }

    /** 홈에서 반납을 새로 시작할 때 이전 확인 결과를 지운다. */
    fun reset() {
        job?.cancel()
        _uiState.value = LocationUiState.Idle
    }

    /** 사진이 APPROVE 된 뒤 홈으로 갈 때 반납 완료를 알린다. 실패해도 화면 이동은 막지 않는다. */
    fun complete() {
        viewModelScope.launch {
            try {
                api.complete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 서버 기록은 Logcat 에 남는다 (RemoteReturnApi). 사용자는 이미 반납 가능 판정을 받았다.
            }
        }
    }
}
