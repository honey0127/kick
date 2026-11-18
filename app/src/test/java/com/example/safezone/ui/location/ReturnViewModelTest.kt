package com.example.safezone.ui.location

import com.example.safezone.data.location.DeviceLocation
import com.example.safezone.data.location.DeviceLocationSource
import com.example.safezone.data.location.LocationCheckResult
import com.example.safezone.data.location.ReturnApi
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

    private class FakeApi(private val result: LocationCheckResult? = null) : ReturnApi {
        var completed = 0

        override suspend fun checkLocation(location: DeviceLocation): LocationCheckResult =
            result ?: throw IOException("서버 꺼짐")

        override suspend fun complete() {
            completed++
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
    fun reset_returnsToIdle_andComplete_callsApi() = runTest(dispatcher) {
        val api = FakeApi(LocationCheckResult(true, "", null))
        val vm = ReturnViewModel(api, source(here))
        vm.check()
        dispatcher.scheduler.advanceUntilIdle()
        vm.reset()
        assertEquals(LocationUiState.Idle, vm.uiState.value)

        vm.complete()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, api.completed)
    }
}
