package com.example.safezone.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.safezone.data.analysis.PhotoAnalyzer
import com.example.safezone.data.model.AnalysisResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 분석 화면과 결과 화면이 함께 보는 상태. */
sealed interface AnalysisUiState {
    /** 아직 분석을 요청하지 않음 */
    data object Idle : AnalysisUiState
    data object Analyzing : AnalysisUiState
    data class Success(val result: AnalysisResult) : AnalysisUiState
    data object Failed : AnalysisUiState
}

/**
 * 분석 요청과 결과를 관리한다.
 * Activity 범위로 만들어 화면 회전이나 분석 → 결과 화면 이동 중에도 결과가 유지된다.
 */
class AnalysisViewModel(
    private val analyzer: PhotoAnalyzer,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AnalysisUiState>(AnalysisUiState.Idle)
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var lastPhotoUri: String? = null

    /** 분석을 시작한다. 이미 분석 중이면 무시해 중복 요청을 막는다. */
    fun analyze(photoUri: String) {
        if (job?.isActive == true) return
        lastPhotoUri = photoUri
        // 화면 이동 전에 상태를 바로 바꿔 둬야 분석 화면이 이전 결과를 보고 넘어가 버리지 않는다.
        _uiState.value = AnalysisUiState.Analyzing
        job = viewModelScope.launch {
            _uiState.value = try {
                AnalysisUiState.Success(analyzer.analyze(photoUri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnalysisUiState.Failed
            }
        }
    }

    /** 실패한 분석을 같은 사진으로 다시 시도한다. */
    fun retry() {
        lastPhotoUri?.let(::analyze)
    }

    /** 분석 화면에서 뒤로 나갈 때 진행 중인 분석을 멈춘다. */
    fun cancel() {
        job?.cancel()
        _uiState.value = AnalysisUiState.Idle
    }
}
