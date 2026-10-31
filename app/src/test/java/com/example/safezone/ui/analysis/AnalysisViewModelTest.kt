package com.example.safezone.ui.analysis

import com.example.safezone.data.analysis.PhotoAnalyzer
import com.example.safezone.data.model.AnalysisResult
import com.example.safezone.data.model.ParkingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 호출 횟수를 세고, 처음 [failTimes]번은 실패하는 테스트용 분석기. */
    private class FakeAnalyzer(private var failTimes: Int = 0) : PhotoAnalyzer {
        val requestedUris = mutableListOf<String>()

        override suspend fun analyze(photoUri: String): AnalysisResult {
            requestedUris += photoUri
            delay(1_000)
            if (failTimes > 0) {
                failTimes--
                error("분석 서버 오류")
            }
            return AnalysisResult(ParkingStatus.SAFE, findings = emptyList(), isMock = false)
        }
    }

    private fun TestScope.newViewModel(analyzer: PhotoAnalyzer) = AnalysisViewModel(analyzer)

    @Test
    fun analyze_showsAnalyzingImmediately_thenSuccess() = runTest(dispatcher) {
        val viewModel = newViewModel(FakeAnalyzer())

        viewModel.analyze("content://photo/1")
        assertEquals(AnalysisUiState.Analyzing, viewModel.uiState.value)

        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is AnalysisUiState.Success)
        assertEquals(ParkingStatus.SAFE, (state as AnalysisUiState.Success).result.status)
    }

    @Test
    fun analyze_whileAnalyzing_isIgnored() = runTest(dispatcher) {
        val analyzer = FakeAnalyzer()
        val viewModel = newViewModel(analyzer)

        viewModel.analyze("content://photo/1")
        viewModel.analyze("content://photo/1")
        viewModel.analyze("content://photo/2")
        advanceUntilIdle()

        assertEquals(listOf("content://photo/1"), analyzer.requestedUris)
    }

    @Test
    fun failure_showsFailed_andRetryUsesSamePhoto() = runTest(dispatcher) {
        val analyzer = FakeAnalyzer(failTimes = 1)
        val viewModel = newViewModel(analyzer)

        viewModel.analyze("content://photo/1")
        advanceUntilIdle()
        assertEquals(AnalysisUiState.Failed, viewModel.uiState.value)

        viewModel.retry()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is AnalysisUiState.Success)
        assertEquals(listOf("content://photo/1", "content://photo/1"), analyzer.requestedUris)
    }

    @Test
    fun cancel_stopsAnalysis_andResultNeverArrives() = runTest(dispatcher) {
        val viewModel = newViewModel(FakeAnalyzer())

        viewModel.analyze("content://photo/1")
        viewModel.cancel()
        advanceUntilIdle()

        assertEquals(AnalysisUiState.Idle, viewModel.uiState.value)
    }
}
