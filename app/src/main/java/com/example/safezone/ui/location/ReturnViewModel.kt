package com.example.safezone.ui.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.safezone.data.location.DeviceLocationSource
import com.example.safezone.data.location.ReturnApi
import com.example.safezone.data.location.ReturnSessionExpiredException
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

/** 반납 완료(③) 요청의 상태. 결과 화면이 양호일 때 "반납 완료하기" 버튼과 함께 쓴다. */
sealed interface CompletionUiState {
    /** 아직 반납 완료를 누르지 않음 */
    data object Idle : CompletionUiState
    data object Completing : CompletionUiState

    /** 서버가 반납 완료를 확인해 줌. 반납 완료 화면으로 넘어간다. */
    data class Done(val returnId: String?, val zone: String?) : CompletionUiState

    /**
     * 반납을 완료하지 못함.
     * @property sessionExpired true 면 다시 눌러도 실패하므로 위치 확인부터 다시 해야 한다.
     *   false 면 서버에 연결하지 못한 것이라 다시 시도할 수 있다.
     */
    data class Failed(val sessionExpired: Boolean) : CompletionUiState
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

    private val _completion = MutableStateFlow<CompletionUiState>(CompletionUiState.Idle)
    val completion: StateFlow<CompletionUiState> = _completion.asStateFlow()

    private var job: Job? = null
    private var completeJob: Job? = null

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
                // 화면 제목이 이미 "위치를 확인하지 못했어요."이므로 여기서는 원인만 적는다. 자세한 원인은 Logcat 에 남는다.
                LocationUiState.Failed("서버에 연결하지 못했어요.\n인터넷 연결을 확인하고 다시 시도해 주세요.")
            }
        }
    }

    /** 반납을 새로 시작할 때 이전 위치 확인 결과와 반납 완료 상태를 지운다. */
    fun reset() {
        job?.cancel()
        completeJob?.cancel()
        _uiState.value = LocationUiState.Idle
        _completion.value = CompletionUiState.Idle
    }

    /**
     * 사진이 APPROVE 된 반납을 완료 처리하고, 성공·실패를 completion 으로 알린다. 이미 처리 중이면 무시한다.
     * 실패 원인은 Logcat 에 남는다 (RemoteReturnApi).
     */
    fun complete() {
        if (completeJob?.isActive == true) return
        _completion.value = CompletionUiState.Completing
        completeJob = viewModelScope.launch {
            _completion.value = try {
                val receipt = api.complete()
                CompletionUiState.Done(returnId = receipt.returnId, zone = receipt.zone)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReturnSessionExpiredException) {
                CompletionUiState.Failed(sessionExpired = true)
            } catch (e: Exception) {
                CompletionUiState.Failed(sessionExpired = false)
            }
        }
    }
}
