package com.example.safezone.data.analysis

import android.util.Log
import com.example.safezone.data.model.AnalysisResult
import com.example.safezone.data.model.ParkingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private const val TAG = "SafeZone"

/**
 * AI 분석 서버(ai/ 폴더의 FastAPI)에 사진을 보내 판정을 받는다.
 * 서버는 KICK_OPEN_ANALYZE=1 (또는 KICK_DEV=1) 로 실행해야 /api/analyze 가 열린다.
 *
 * @param serverUrl 예: 에뮬레이터에서 PC 서버는 "http://10.0.2.2:8000"
 * @param readPhoto 사진 URI -> 파일 바이트 (앱에서는 ContentResolver 로 읽는다)
 */
class RemotePhotoAnalyzer(
    private val serverUrl: String,
    private val readPhoto: suspend (photoUri: String) -> ByteArray,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PhotoAnalyzer {

    override suspend fun analyze(photoUri: String): AnalysisResult = withContext(ioDispatcher) {
        try {
            val photo = readPhoto(photoUri)
            parseAnalysisResponse(upload(photo))
        } catch (e: CancellationException) {
            // 사용자가 분석을 취소한 경우는 실패가 아니므로 기록하지 않는다.
            throw e
        } catch (e: Exception) {
            // 화면에는 "분석하지 못했어요"만 보이므로, 원인(서버 꺼짐·주소 오류·404·응답 형식 등)을 Logcat 에 남긴다.
            // Logcat 에서 "SafeZone" 으로 검색하면 볼 수 있다.
            Log.w(TAG, "분석 서버 요청 실패: $serverUrl", e)
            throw e
        }
    }

    private fun upload(photo: ByteArray): String {
        val boundary = "----SafeZone" + UUID.randomUUID().toString().replace("-", "")
        val conn = URL(serverUrl.trimEnd('/') + "/api/analyze").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            // 서버가 처음 요청을 받을 때 AI 모델을 불러오느라 오래 걸릴 수 있다.
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { out ->
                out.write(
                    ("--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\n" +
                        "Content-Type: image/jpeg\r\n\r\n").toByteArray(),
                )
                out.write(photo)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("분석 서버 오류 ($code) $detail")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * 서버 응답 JSON -> 앱 결과.
 * APPROVE/REJECT/RETAKE 를 SAFE/UNSAFE/UNCERTAIN 으로 바꾸고,
 * 근거는 서버가 실제로 돌려준 측정값과 사유 문구만 쓴다.
 */
fun parseAnalysisResponse(json: String): AnalysisResult {
    val root = JSONObject(json)
    val status = when (val s = root.getString("status")) {
        "APPROVE" -> ParkingStatus.SAFE
        "REJECT" -> ParkingStatus.UNSAFE
        "RETAKE" -> ParkingStatus.UNCERTAIN
        else -> throw IOException("알 수 없는 판정: $s")
    }
    val findings = mutableListOf<String>()
    // 판단 보류(RETAKE)는 서버가 "이 사진으로는 폭을 믿을 수 없다"고 한 경우라, 측정값을 근거로 내세우지 않는다.
    val metrics = if (status == ParkingStatus.UNCERTAIN) null else root.optJSONObject("metrics")
    metrics?.let { m ->
        when {
            m.has("residual_cm") ->
                findings += "남은 통행폭 약 ${String.format(Locale.ROOT, "%.1f", m.getDouble("residual_cm") / 100)}m (점자블록 기준 실측)"
            m.has("width_occupancy") ->
                findings += "보도 폭의 약 ${(m.getDouble("width_occupancy") * 100).roundToInt()}% 차지 (사진 비율로 추정)"
        }
    }
    root.optJSONArray("reasons")?.let { reasons ->
        for (i in 0 until reasons.length()) {
            reasons.getJSONObject(i).optString("message").takeIf { it.isNotBlank() }?.let { findings += it }
        }
    }
    return AnalysisResult(status = status, findings = findings, isMock = false)
}
