package com.example.safezone.ui.location

import com.example.safezone.data.location.DeviceLocation
import com.example.safezone.data.location.DeviceLocationSource
import com.example.safezone.data.location.LocationCheckResult
import com.example.safezone.data.location.ReturnApi
import com.example.safezone.data.location.ReturnReceipt
import com.example.safezone.data.location.ReturnSessionExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReturnViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * @param result 위치 확인 결과. null 이면 서버에 연결하지 못한 것처럼 예외를 던진다.
     * @param completeErrors 반납 완료를 부를 때마다 앞에서부터 하나씩 던질 예외. 다 쓰면 성공한다.
     */
    private class FakeApi(
        private val result: LocationCheckResult? = null,
        completeErrors: List<Exception> = emptyList(),
    ) : ReturnApi {
        private val completeErrors = completeErrors.toMutableList()
        var completed = 0

        override suspend fun checkLocation(location: DeviceLocation): LocationCheckResult =
            result ?: throw IOException("서버 꺼짐")

        override suspend fun complete(): ReturnReceipt {
            completed++
            if (completeErrors.isNotEmpty()) throw completeErrors.removeAt(0)
            return ReturnReceipt(returnId = "RT1", zone = "북문 주차구역")
        }
    }

    private val here = DeviceLocation(35.89, 128.61, 5f)

    /** 정해진 위치(또는 위치 없음)를 돌려주는 가짜 GPS. */
    private fun source(location: DeviceLocation?) = object : DeviceLocationSource {
        override suspend fun current(): DeviceLocation? = location
    }

    @Test
    fun allowedLocation_becomesAllowed() = runTest(dispatcher) {
        val vm = ReturnViewModel(FakeApi(LocationCheckResult(true, "", "북문 주차구역")), source(here))
        vm.check()
        assertEquals(LocationUiState.Checking, vm.uiState.value)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LocationUiState.Allowed("북문 주차구역"), vm.uiState.value)
    }

    @Test
    fun deniedLocation_showsServerMessage() = runTest(dispatcher) {
        val vm = ReturnViewModel(FakeApi(LocationCheckResult(false, "주차 금지 구역이에요.", null)), source(here))
        vm.check()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LocationUiState.Denied("주차 금지 구역이에요."), vm.uiState.value)
    }

    @Test
    fun noLocation_orServerError_becomesFailed() = runTest(dispatcher) {
        val noGps = ReturnViewModel(FakeApi(LocationCheckResult(true, "", null)), source(null))
        noGps.check()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(noGps.uiState.value is LocationUiState.Failed)

        val serverDown = ReturnViewModel(FakeApi(result = null), source(here))
        serverDown.check()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(serverDown.uiState.value is LocationUiState.Failed)
    }

    @Test
    fun complete_showsCompleting_thenDoneWithReceipt() = runTest(dispatcher) {
        val api = FakeApi(LocationCheckResult(true, "", null))
        val vm = ReturnViewModel(api, source(here))
        assertEquals(CompletionUiState.Idle, vm.completion.value)

        vm.complete()
        assertEquals(CompletionUiState.Completing, vm.completion.value)
        // 처리 중에 버튼을 또 눌러도 요청은 한 번만 간다.
        vm.complete()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(CompletionUiState.Done(returnId = "RT1", zone = "북문 주차구역"), vm.completion.value)
        assertEquals(1, api.completed)
    }

    @Test
    fun complete_withExpiredSession_needsLocationCheckAgain() = runTest(dispatcher) {
        val api = FakeApi(completeErrors = listOf(ReturnSessionExpiredException("반납 세션 종료 (410)")))
        val vm = ReturnViewModel(api, source(here))

        vm.complete()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(CompletionUiState.Failed(sessionExpired = true), vm.completion.value)
    }

    @Test
    fun complete_whenServerUnreachable_canBeRetried() = runTest(dispatcher) {
        val api = FakeApi(completeErrors = listOf(IOException("서버 꺼짐")))
        val vm = ReturnViewModel(api, source(here))

        vm.complete()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(CompletionUiState.Failed(sessionExpired = false), vm.completion.value)

        vm.complete()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.completion.value is CompletionUiState.Done)
        assertEquals(2, api.completed)
    }

    @Test
    fun reset_clearsLocationResult_andCompletion() = runTest(dispatcher) {
        val api = FakeApi(LocationCheckResult(true, "", null))
        val vm = ReturnViewModel(api, source(here))
        vm.check()
        vm.complete()
        dispatcher.scheduler.advanceUntilIdle()

        vm.reset()

        assertEquals(LocationUiState.Idle, vm.uiState.value)
        assertEquals(CompletionUiState.Idle, vm.completion.value)
    }
}
