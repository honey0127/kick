package com.example.safezone.data.location

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 여러 위치 제공자 가운데 어떤 위치를 언제 고르는지 확인한다.
 * 실제 GPS 대신 "몇 초 뒤에 어떤 위치를 준다"고 정해 둔 가짜 제공자를 쓰고, 시간은 테스트 시계로 건너뛴다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PickBestLocationTest {

    private val gps = DeviceLocation(35.8926, 128.6090, accuracyMeters = 5f)
    private val network = DeviceLocation(35.8927, 128.6093, accuracyMeters = 40f)

    /** 제공자 이름 -> (답이 오기까지 걸리는 시간, 위치). 여기에 없는 제공자는 끝까지 답하지 않는다(실내의 GPS 처럼). */
    private fun providersAnswering(
        vararg answers: Pair<String, Pair<Long, DeviceLocation?>>,
    ): suspend (String) -> DeviceLocation? {
        val byName = answers.toMap()
        return { provider ->
            val (delayMillis, location) = byName[provider] ?: awaitCancellation()
            delay(delayMillis)
            location
        }
    }

    @Test
    fun accurateFix_isUsedAsSoonAsItArrives() = runTest {
        val picked = pickBestLocation(
            providers = listOf("gps", "network"),
            timeoutMillis = 20_000,
            settleMillis = 5_000,
            request = providersAnswering("gps" to (3_000L to gps), "network" to (10_000L to network)),
        )

        assertEquals(gps, picked)
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun coarseFixFirst_waitsBriefly_andPrefersMoreAccurateOne() = runTest {
        // 네트워크 위치(±40m)가 1초에 먼저 오고 GPS(±5m)가 4초에 온다 → 조금 더 기다려 GPS 위치를 쓴다.
        val picked = pickBestLocation(
            providers = listOf("gps", "network"),
            timeoutMillis = 20_000,
            settleMillis = 5_000,
            request = providersAnswering("network" to (1_000L to network), "gps" to (4_000L to gps)),
        )

        assertEquals(gps, picked)
        assertEquals(4_000L, currentTime)
    }

    @Test
    fun indoors_gpsNeverAnswers_usesNetworkFixAfterShortWait() = runTest {
        val picked = pickBestLocation(
            providers = listOf("gps", "network"),
            timeoutMillis = 20_000,
            settleMillis = 5_000,
            request = providersAnswering("network" to (1_000L to network)),
        )

        // GPS 를 20초 다 기다리지 않는다: 첫 위치(1초) 뒤 5초만 더 기다린다.
        assertEquals(network, picked)
        assertEquals(6_000L, currentTime)
    }

    @Test
    fun noProviderAnswers_givesUpAfterTimeout() = runTest {
        val picked = pickBestLocation(
            providers = listOf("gps", "network"),
            timeoutMillis = 20_000,
            settleMillis = 5_000,
            request = providersAnswering(),
        )

        assertNull(picked)
        assertEquals(20_000L, currentTime)
    }

    @Test
    fun providerThatFailsOrHasNoFix_isSkipped() = runTest {
        val picked = pickBestLocation(
            providers = listOf("gps", "fused", "network"),
            timeoutMillis = 20_000,
            settleMillis = 5_000,
        ) { provider ->
            when (provider) {
                "gps" -> throw SecurityException("이 제공자를 쓸 수 없음")
                "fused" -> null
                else -> {
                    delay(2_000)
                    network
                }
            }
        }

        assertEquals(network, picked)
        // 더 답할 제공자가 없으므로 덜 정확해도 바로 쓴다.
        assertEquals(2_000L, currentTime)
    }

    @Test
    fun noFixFromAnyProvider_returnsNullWithoutWaiting() = runTest {
        val picked = pickBestLocation(
            providers = listOf("gps", "network"),
            timeoutMillis = 20_000,
            settleMillis = 5_000,
        ) { null }

        assertNull(picked)
        assertEquals(0L, currentTime)
    }

    @Test
    fun noEnabledProvider_returnsNull() = runTest {
        // 위치(GPS)가 꺼져 있으면 물어볼 제공자가 없다.
        val picked = pickBestLocation(providers = emptyList(), timeoutMillis = 20_000, settleMillis = 5_000) { gps }

        assertNull(picked)
    }
}
