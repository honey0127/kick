package com.example.safezone.data.analysis

import android.util.Base64
import android.util.Log
import com.example.safezone.data.location.ReturnSession
import com.example.safezone.data.location.ReturnSessionExpiredException
import com.example.safezone.data.location.isReturnSessionOver
import com.example.safezone.data.model.AnalysisResult
import com.example.safezone.data.model.EvidenceImage
import com.example.safezone.data.model.EvidenceImageKind
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
 *
 * - session 을 넣은 경우(앱의 반납 순서): 위치 확인으로 받은 반납 토큰과 함께 /api/return/photo 로 보낸다
 *   (서버가 위치 확인 여부와 판정 횟수를 검사한다). 토큰이 없거나 서버가 토큰을 더 받지 않으면
 *   [ReturnSessionExpiredException] 을 던진다. 위치 확인을 건너뛰게 되는 /api/analyze 로는 보내지 않는다.
 * - session 을 넣지 않은 경우(위치 확인 단계 없이 분석기만 쓰는 테스트): /api/analyze 로 보낸다.
 *   서버를 KICK_OPEN_ANALYZE=1 (또는 KICK_DEV=1) 로 실행해야 열린다.
 *
 * @param serverUrl 예: 에뮬레이터에서 PC 서버는 "http://10.0.2.2:8000"
 * @param readPhoto 사진 URI -> 파일 바이트 (앱에서는 ContentResolver 로 읽는다)
 * @param session 위치 확인에서 받은 반납 토큰 (RemoteReturnApi 와 같은 객체)
 */
class RemotePhotoAnalyzer(
    private val serverUrl: String,
    private val readPhoto: suspend (photoUri: String) -> ByteArray,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val session: ReturnSession? = null,
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
        // 반납 순서에서는 토큰이 꼭 있어야 한다. 없으면 요청을 보내지 않고 위치 확인부터 다시 하게 한다.
        val token = session?.let {
            it.token ?: throw ReturnSessionExpiredException("반납 토큰이 없음 (위치 확인 필요)")
        }
        val path = if (token != null) "/api/return/photo" else "/api/analyze"
        val conn = URL(serverUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            // 서버가 처음 요청을 받을 때 AI 모델을 불러오느라 오래 걸릴 수 있다.
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { out ->
                if (token != null) {
                    out.write(
                        ("--$boundary\r\n" +
                            "Content-Disposition: form-data; name=\"token\"\r\n\r\n" +
                            "$token\r\n").toByteArray(),
                    )
                }
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
                if (token != null && isReturnSessionOver(code)) {
                    session?.clearIfCurrent(token)
                    throw ReturnSessionExpiredException("반납 세션 종료 ($code) $detail")
                }
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
 * 근거는 서버가 실제로 돌려준 측정값과 사유 문구, 근거 이미지만 쓴다.
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
    // 근거 이미지: 색칠한 사진은 항상, 조감도는 점자블록으로 실측했을 때만 온다.
    val evidenceImages = listOfNotNull(
        root.jpegOrNull("overlay_jpeg")?.let { EvidenceImage(EvidenceImageKind.OVERLAY, it) },
        root.jpegOrNull("bev_jpeg")?.let { EvidenceImage(EvidenceImageKind.BIRDS_EYE, it) },
    )
    return AnalysisResult(status = status, findings = findings, isMock = false, evidenceImages = evidenceImages)
}

/**
 * base64 글자로 온 JPEG 를 파일 내용(바이트)으로 되돌린다.
 * 값이 없거나 깨져 있으면 null 을 돌려준다. 근거 이미지가 없어도 판정 결과는 보여 줄 수 있어야 하기 때문이다.
 */
private fun JSONObject.jpegOrNull(key: String): ByteArray? {
    if (isNull(key)) return null // 항목이 없거나 값이 null
    return try {
        Base64.decode(getString(key), Base64.DEFAULT).takeIf { it.isNotEmpty() }
    } catch (e: IllegalArgumentException) {
        null // base64 형식이 아님
    }
}
