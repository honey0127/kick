package com.example.safezone.data.analysis

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.safezone.data.location.ReturnSession
import com.example.safezone.data.location.ReturnSessionExpiredException
import com.example.safezone.data.model.EvidenceImageKind
import com.example.safezone.data.model.ParkingStatus
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun retakeWithReview_allowsReviewReturn_andShowsServerMessage() {
        val r = parseAnalysisResponse(
            """{"status":"RETAKE","review":true,"review_message":"반납은 받고, 이 사진은 운영팀이 확인합니다.",
               "reasons":[{"code":"TRUNCATED","status":"RETAKE","message":"보도 양 끝이 화면 밖이에요."}]}""",
        )
        assertEquals(ParkingStatus.UNCERTAIN, r.status) // 판정은 양호로 바뀌지 않는다
        assertTrue(r.reviewReturn)
        assertEquals(listOf("보도 양 끝이 화면 밖이에요.", "반납은 받고, 이 사진은 운영팀이 확인합니다."), r.findings)
    }

    @Test
    fun reviewFlag_isIgnored_unlessRetake() {
        assertFalse(parseAnalysisResponse("""{"status":"REJECT","review":true}""").reviewReturn)
        assertFalse(parseAnalysisResponse("""{"status":"RETAKE"}""").reviewReturn)
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
    fun withReturnToken_uploadsToReturnPhotoEndpoint_withToken() = runTest {
        responseBody = """{"status":"APPROVE","metrics":{},"reasons":[]}"""
        val session = ReturnSession().apply { token = "TOKEN123" }
        val analyzer = RemotePhotoAnalyzer(
            serverUrl = "http://127.0.0.1:${server.address.port}/",
            readPhoto = { "JPEGDATA".toByteArray() },
            ioDispatcher = Dispatchers.Unconfined,
            session = session,
        )
        analyzer.analyze("content://photo/1")

        assertEquals("/api/return/photo", receivedPath)
        val body = String(receivedBody)
        assertTrue(body.contains("name=\"token\"\r\n\r\nTOKEN123\r\n"))
        assertTrue(body.contains("name=\"file\"") && body.contains("JPEGDATA"))
    }

    /** 앱의 반납 순서처럼 반납 토큰(session)을 함께 쓰는 분석기. */
    private fun analyzerWith(session: ReturnSession) = RemotePhotoAnalyzer(
        serverUrl = "http://127.0.0.1:${server.address.port}/",
        readPhoto = { "JPEGDATA".toByteArray() },
        ioDispatcher = Dispatchers.Unconfined,
        session = session,
    )

    @Test
    fun returnFlowWithoutToken_sendsNothing_andAsksForLocationCheck() = runTest {
        // 반납 순서인데 토큰이 없으면(위치 확인을 안 했거나 이미 끝난 토큰) /api/analyze 로 돌려 보내지 않는다.
        try {
            analyzerWith(ReturnSession()).analyze("content://photo/1")
            fail("예외가 나야 한다")
        } catch (e: ReturnSessionExpiredException) {
            assertEquals("서버에 아무 요청도 가지 않아야 한다", "", receivedPath)
        }
    }

    @Test
    fun serverRejectsToken_becomesSessionExpired_andForgetsToken() = runTest {
        // 410 만료, 429 사진 판정 횟수 초과, 409 이미 끝난 반납: 모두 위치 확인부터 다시 해야 한다.
        for (code in listOf(410, 429, 409)) {
            responseCode = code
            responseBody = """{"detail":"반납 세션이 만료됐어요. 위치 확인부터 다시 해주세요."}"""
            val session = ReturnSession().apply { token = "TOKEN123" }
            try {
                analyzerWith(session).analyze("content://photo/1")
                fail("예외가 나야 한다 ($code)")
            } catch (e: ReturnSessionExpiredException) {
                assertNull("쓸 수 없는 토큰은 지워야 한다 ($code)", session.token)
            }
        }
    }

    @Test
    fun otherServerError_withToken_keepsToken_soRetryCanWork() = runTest {
        responseCode = 500
        responseBody = """{"detail":"Internal Server Error"}"""
        val session = ReturnSession().apply { token = "TOKEN123" }
        try {
            analyzerWith(session).analyze("content://photo/1")
            fail("예외가 나야 한다")
        } catch (e: IOException) {
            assertFalse("일시적인 서버 오류는 '위치 확인부터 다시'가 아니다", e is ReturnSessionExpiredException)
            assertEquals("TOKEN123", session.token)
        }
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

    @Test
    fun evidenceImages_areDecoded_overlayFirstThenBirdsEye() {
        // base64 "AQID" = 바이트 1,2,3 / "BAU=" = 바이트 4,5
        val r = parseAnalysisResponse(
            """{"status":"APPROVE","metrics":{},"reasons":[],"overlay_jpeg":"AQID","bev_jpeg":"BAU="}""",
        )
        assertEquals(listOf(EvidenceImageKind.OVERLAY, EvidenceImageKind.BIRDS_EYE), r.evidenceImages.map { it.kind })
        assertArrayEquals(byteArrayOf(1, 2, 3), r.evidenceImages[0].jpeg)
        assertArrayEquals(byteArrayOf(4, 5), r.evidenceImages[1].jpeg)
    }

    @Test
    fun missingOrBrokenEvidenceImage_isSkipped_verdictStillShown() {
        // 조감도는 실측하지 못하면 null 로 온다.
        val onlyOverlay = parseAnalysisResponse("""{"status":"REJECT","reasons":[],"overlay_jpeg":"AQID","bev_jpeg":null}""")
        assertEquals(listOf(EvidenceImageKind.OVERLAY), onlyOverlay.evidenceImages.map { it.kind })

        // 근거 이미지가 깨져 있거나 아예 없어도 판정 결과는 그대로 보여 준다.
        val broken = parseAnalysisResponse("""{"status":"REJECT","reasons":[],"overlay_jpeg":"!!!not base64!!!"}""")
        assertEquals(ParkingStatus.UNSAFE, broken.status)
        assertTrue(broken.evidenceImages.isEmpty())
        val none = parseAnalysisResponse("""{"status":"APPROVE","reasons":[]}""")
        assertTrue(none.evidenceImages.isEmpty())
    }
}
