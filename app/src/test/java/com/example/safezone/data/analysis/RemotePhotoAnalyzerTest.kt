package com.example.safezone.data.analysis

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.safezone.data.model.ParkingStatus
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.net.InetSocketAddress

/** 서버 응답 변환과, 실제 HTTP 로 사진을 보내는 과정을 가짜 서버로 확인한다. (org.json 때문에 Robolectric) */
@RunWith(AndroidJUnit4::class)
class RemotePhotoAnalyzerTest {

    private lateinit var server: HttpServer
    private var responseCode = 200
    private var responseBody = ""
    private var receivedBody = ByteArray(0)
    private var receivedPath = ""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            receivedPath = ex.requestURI.path
            receivedBody = ex.requestBody.readBytes()
            val bytes = responseBody.toByteArray()
            ex.sendResponseHeaders(responseCode, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private fun analyzer() = RemotePhotoAnalyzer(
        serverUrl = "http://127.0.0.1:${server.address.port}/",
        readPhoto = { "JPEGDATA".toByteArray() },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun approveWithMeasuredWidth_becomesSafe_withMeasuredFinding() {
        val r = parseAnalysisResponse(
            """{"status":"APPROVE","metrics":{"residual_cm":174,"width_occupancy":0.3},
               "reasons":[{"code":"CAUTION_WIDTH","status":"INFO","message":"남은 통행폭 174cm — 보도 기준폭(2m)보다 좁아요."}]}""",
        )
        assertEquals(ParkingStatus.SAFE, r.status)
        assertFalse(r.isMock)
        assertEquals(listOf("남은 통행폭 약 1.7m (점자블록 기준 실측)", "남은 통행폭 174cm — 보도 기준폭(2m)보다 좁아요."), r.findings)
    }

    @Test
    fun rejectWithoutMeasurement_usesRatioFinding() {
        val r = parseAnalysisResponse(
            """{"status":"REJECT","metrics":{"width_occupancy":0.72},
               "reasons":[{"code":"WIDTH","status":"REJECT","message":"보도 폭을 너무 많이 차지해요(72%)."}]}""",
        )
        assertEquals(ParkingStatus.UNSAFE, r.status)
        assertEquals("보도 폭의 약 72% 차지 (사진 비율로 추정)", r.findings.first())
    }

    @Test
    fun retake_becomesUncertain() {
        val r = parseAnalysisResponse("""{"status":"RETAKE","metrics":{},"reasons":[{"message":"킥보드가 보이지 않아요."}]}""")
        assertEquals(ParkingStatus.UNCERTAIN, r.status)
        assertEquals(listOf("킥보드가 보이지 않아요."), r.findings)
    }

    @Test
    fun uploadsPhotoAsMultipart_toAnalyzeEndpoint() = runTest {
        responseBody = """{"status":"APPROVE","metrics":{},"reasons":[]}"""
        val r = analyzer().analyze("content://photo/1")

        assertEquals(ParkingStatus.SAFE, r.status)
        assertEquals("/api/analyze", receivedPath)
        val body = String(receivedBody)
        assertTrue(body.contains("name=\"file\"") && body.contains("JPEGDATA"))
    }

    @Test
    fun serverError_throws_soScreenShowsRetry() = runTest {
        responseCode = 404
        responseBody = """{"detail":"Not Found"}"""
        try {
            analyzer().analyze("content://photo/1")
            fail("예외가 나야 한다")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("404"))
        }
    }

    @Test
    fun failure_isWrittenToLogcat_withCause() = runTest {
        responseCode = 404
        responseBody = """{"detail":"Not Found"}"""
        try {
            analyzer().analyze("content://photo/1")
        } catch (e: IOException) {
            // 예외는 그대로 전달되어야 한다 (화면이 "다시 시도"를 보여 준다)
        }
        // 실패 원인이 Logcat("SafeZone" 태그)에 경고로 남아야 한다.
        val logs = ShadowLog.getLogsForTag("SafeZone")
        assertTrue(logs.any { it.type == Log.WARN && it.throwable?.message?.contains("404") == true })
    }

    @Test
    fun retake_doesNotShowMeasurementAsEvidence() {
        // 서버가 "폭을 판단할 수 없다"고 한 경우, 믿을 수 없는 측정값을 근거로 내세우지 않는다.
        val r = parseAnalysisResponse(
            """{"status":"RETAKE","metrics":{"width_occupancy":0.781,"truncated":true},
               "reasons":[{"code":"TRUNCATED","status":"RETAKE","message":"보도 양 끝이 화면 밖이라 폭을 판단할 수 없어요."}]}""",
        )
        assertEquals(ParkingStatus.UNCERTAIN, r.status)
        assertEquals(listOf("보도 양 끝이 화면 밖이라 폭을 판단할 수 없어요."), r.findings)
    }
}
