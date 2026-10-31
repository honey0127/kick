package com.example.safezone.data.analysis

import com.example.safezone.data.model.ParkingStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockPhotoAnalyzerTest {

    @Test
    fun cyclesThroughAllStatuses_andAlwaysMarksResultAsMock() = runTest {
        val analyzer = MockPhotoAnalyzer(delayMillis = 0)

        val results = List(4) { analyzer.analyze("content://photo/$it") }

        assertEquals(
            listOf(ParkingStatus.SAFE, ParkingStatus.UNSAFE, ParkingStatus.UNCERTAIN, ParkingStatus.SAFE),
            results.map { it.status },
        )
        // 임시 결과가 실제 분석처럼 보이면 안 된다.
        assertTrue(results.all { it.isMock })
        assertTrue(results.all { it.findings.isNotEmpty() })
    }
}
