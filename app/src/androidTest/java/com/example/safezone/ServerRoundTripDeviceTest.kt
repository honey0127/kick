package com.example.safezone

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.safezone.data.analysis.RemotePhotoAnalyzer
import com.example.safezone.data.model.EvidenceImageKind
import com.example.safezone.data.model.ParkingStatus
import com.example.safezone.ui.result.ResultScreen
import com.example.safezone.ui.theme.SafeZoneTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 실제 기기(에뮬레이터)에서만 확인할 수 있는 부분을 본다.
 *
 * - Android의 HTTP 통신으로 사진이 서버까지 가고 응답이 돌아오는지 (디버그 빌드의 http:// 허용 설정 포함)
 * - 응답에 든 판정 근거 이미지(base64 JPEG)가 디코딩되어 결과 화면에 실제로 그려지는지
 *
 * AI 서버 대신, 실제 서버와 같은 모양의 응답을 돌려주는 가짜 서버를 기기 안에서 띄운다.
 * 그래서 AI 모델의 판정이 맞는지는 이 테스트로 알 수 없다. Android 8.0 이상 기기가 필요하다(화면 캡처).
 */
@RunWith(AndroidJUnit4::class)
class ServerRoundTripDeviceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun photoUpload_verdict_andEvidenceImages_workOnDevice() {
        // 색으로 구분할 수 있는 단색 JPEG. 근거 이미지가 제대로 그려졌는지 나중에 화면의 픽셀 색으로 확인한다.
        val photo = solidJpeg(Color.GREEN)
        val overlay = solidJpeg(Color.RED)
        val birdsEye = solidJpeg(Color.BLUE)
        val response = JSONObject()
            .put("status", "REJECT")
            .put(
                "reasons",
                JSONArray().put(
                    JSONObject()
                        .put("code", "NARROW")
                        .put("status", "REJECT")
                        .put("message", "남은 통행폭이 94cm라 휠체어가 지나갈 수 없어요(최소 120cm)."),
                ),
            )
            .put("metrics", JSONObject().put("residual_cm", 94).put("width_occupancy", 0.517))
            .put("overlay_jpeg", Base64.encodeToString(overlay, Base64.NO_WRAP))
            .put("bev_jpeg", Base64.encodeToString(birdsEye, Base64.NO_WRAP))
            .toString()

        // 1) 사진 전송과 응답 해석: 앱이 실제로 쓰는 RemotePhotoAnalyzer를 그대로 사용한다.
        val result = FakeServer(response).use { server ->
            val analyzer = RemotePhotoAnalyzer(serverUrl = server.url, readPhoto = { photo })
            val analyzed = runBlocking { analyzer.analyze("content://test/photo") }

            assertEquals("POST /api/analyze HTTP/1.1", server.requestLine)
            assertTrue("사진이 multipart의 file 항목으로 와야 한다", server.requestBody.containsBytes("name=\"file\"".toByteArray()))
            assertTrue("사진 내용이 그대로 서버에 도착해야 한다", server.requestBody.containsBytes(photo))
            analyzed
        }
        assertEquals(ParkingStatus.UNSAFE, result.status)
        assertFalse(result.isMock)
        assertEquals(listOf(EvidenceImageKind.OVERLAY, EvidenceImageKind.BIRDS_EYE), result.evidenceImages.map { it.kind })

        // 2) 결과 화면 표시
        composeRule.setContent {
            SafeZoneTheme {
                ResultScreen(result = result, onPrimaryClick = {}, onHomeClick = {})
            }
        }
        composeRule.onNodeWithText("주차 위치를 확인해 주세요!").assertIsDisplayed()
        composeRule.onNodeWithText("남은 통행폭 약 0.9m (점자블록 기준 실측)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("판정 근거 이미지").performScrollTo().assertIsDisplayed()

        // 3) 근거 이미지가 실제로 디코딩되어 그려졌는지: 화면에서 잡은 픽셀이 보낸 이미지의 색이어야 한다.
        waitUntilImageIsDrawn("AI가 인식한 영역을 색으로 표시한 사진") { it.red > 0.7f && it.green < 0.3f && it.blue < 0.3f }
        waitUntilImageIsDrawn("위에서 내려다본 그림") { it.blue > 0.7f && it.red < 0.3f && it.green < 0.3f }
    }

    /** 이미지 디코딩은 화면 스레드 밖에서 이뤄지므로, 그려질 때까지 잠시 기다리며 가운데 픽셀 색을 확인한다. */
    private fun waitUntilImageIsDrawn(
        contentDescription: String,
        hasExpectedColor: (androidx.compose.ui.graphics.Color) -> Boolean,
    ) {
        val image = composeRule.onNodeWithContentDescription(contentDescription)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                // 이미지를 불러온 뒤에야 높이가 생기므로, 확인할 때마다 화면 안으로 스크롤한다.
                image.performScrollTo()
                val pixels = image.captureToImage().toPixelMap()
                pixels.width > 1 && pixels.height > 1 && hasExpectedColor(pixels[pixels.width / 2, pixels.height / 2])
            }.getOrDefault(false)
        }
    }

    private fun solidJpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(160, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        return output.toByteArray()
    }
}

/** 바이트 배열 안에 다른 바이트 배열이 그대로 들어 있는지. */
private fun ByteArray.containsBytes(part: ByteArray): Boolean {
    if (part.isEmpty()) return true
    for (start in 0..size - part.size) {
        var matched = 0
        while (matched < part.size && this[start + matched] == part[matched]) matched++
        if (matched == part.size) return true
    }
    return false
}

/**
 * 테스트용 가짜 분석 서버. 기기 자신(127.0.0.1)의 비어 있는 포트에서 HTTP 요청을 받아 기록하고,
 * 미리 정해 둔 응답을 돌려준다.
 */
private class FakeServer(private val responseBody: String) : Closeable {

    // 포트 0: 비어 있는 포트를 운영체제가 골라 준다.
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    val url: String = "http://127.0.0.1:${serverSocket.localPort}"

    @Volatile
    var requestLine: String = ""
        private set

    @Volatile
    var requestBody: ByteArray = ByteArray(0)
        private set

    init {
        // 요청을 기다리는 일은 별도 스레드가 맡는다. close()가 불리면 accept()가 예외를 던져 스레드가 끝난다.
        thread(isDaemon = true, name = "FakeServer") {
            try {
                while (true) serverSocket.accept().use { client ->
                    val input = client.getInputStream()

                    // HTTP 요청 = 요청 줄 + 헤더 여러 줄 + 빈 줄 + 본문
                    val firstLine = input.readLine()
                    var contentLength = 0
                    var chunked = false
                    while (true) {
                        val line = input.readLine()
                        if (line.isEmpty()) break
                        val name = line.substringBefore(":").trim()
                        val value = line.substringAfter(":").trim()
                        if (name.equals("Content-Length", ignoreCase = true)) contentLength = value.toInt()
                        if (name.equals("Transfer-Encoding", ignoreCase = true)) chunked = value.contains("chunked", ignoreCase = true)
                    }
                    // 본문 길이를 알려 주는 방식은 두 가지다: 전체 길이(Content-Length) 또는 조각 단위(chunked).
                    val body = if (chunked) input.readChunkedBody() else input.readExactly(contentLength)
                    requestLine = firstLine
                    requestBody = body

                    val payload = responseBody.toByteArray()
                    val head = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                    val output = client.getOutputStream()
                    output.write(head.toByteArray())
                    output.write(payload)
                    output.flush()
                }
            } catch (e: IOException) {
                // 서버를 닫았거나 연결이 끊긴 경우: 테스트 쪽에서 결과로 드러난다.
            }
        }
    }

    override fun close() = serverSocket.close()

    private fun InputStream.readExactly(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var received = 0
        while (received < length) {
            val count = read(bytes, received, length - received)
            if (count < 0) break
            received += count
        }
        return bytes
    }

    /** 조각 단위 본문: "조각 크기(16진수) 줄 + 내용 + 빈 줄"이 반복되고, 크기 0인 조각으로 끝난다. */
    private fun InputStream.readChunkedBody(): ByteArray {
        val body = ByteArrayOutputStream()
        while (true) {
            val size = this.readLine().substringBefore(";").trim().toInt(16)
            if (size == 0) {
                while (this.readLine().isNotEmpty()) Unit // 끝에 붙는 헤더는 건너뛴다
                break
            }
            body.write(this.readExactly(size))
            this.readLine() // 조각 끝의 줄바꿈
        }
        return body.toByteArray()
    }

    /** 줄 끝(\r\n)까지 한 줄을 읽는다. 본문이 사진(바이너리)이라 문자 단위가 아닌 바이트 단위로 읽는다. */
    private fun InputStream.readLine(): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = read()
            if (byte < 0 || byte == '\n'.code) break
            if (byte != '\r'.code) line.write(byte)
        }
        return line.toString("ISO-8859-1")
    }
}
