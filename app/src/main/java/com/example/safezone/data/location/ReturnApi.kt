package com.example.safezone.data.location

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "SafeZone"

/** 휴대폰이 알려 준 현재 위치. */
data class DeviceLocation(val latitude: Double, val longitude: Double, val accuracyMeters: Float?)

/**
 * 반납 위치 확인 결과.
 * @property message 서버가 보낸 안내 문구 (예: "주차 금지 구역이에요"). 화면에 그대로 보여 준다.
 */
data class LocationCheckResult(val ok: Boolean, val message: String, val zone: String?)

/**
 * 서버가 반납 완료를 확인해 준 결과.
 * @property returnId 서버가 준 반납 번호 (예: "RT1E3B1846")
 * @property zone 지정 주차구역에 반납한 경우 그 이름
 */
data class ReturnReceipt(val returnId: String?, val zone: String?)

/**
 * 위치 확인을 통과하면 서버가 주는 반납 토큰.
 * 사진 판정(/api/return/photo)과 반납 완료(/api/return/complete) 요청에 함께 보낸다.
 * 서버는 토큰이 없거나 앞 단계를 통과하지 않은 요청을 거절하므로, 위치 확인 없이 사진만 보내 반납할 수 없다.
 */
class ReturnSession {
    @Volatile var token: String? = null
    @Volatile var zone: String? = null

    fun clear() {
        token = null
        zone = null
    }

    /** [usedToken] 이 아직 현재 토큰일 때만 지운다. 그 사이 새 위치 확인으로 받은 토큰은 남긴다. */
    fun clearIfCurrent(usedToken: String) {
        if (token == usedToken) clear()
    }
}

/**
 * 반납 토큰이 없거나 더 쓸 수 없어서 위치 확인부터 다시 해야 한다는 뜻.
 * 같은 요청을 다시 보내도 계속 실패하므로, 화면은 "다시 시도" 대신 위치 확인으로 돌아가는 안내를 보여 준다.
 */
class ReturnSessionExpiredException(message: String) : IOException(message)

/**
 * 서버가 "이 반납 토큰은 더 쓸 수 없다"고 답할 때의 HTTP 상태 코드인지.
 * 409: 순서가 맞지 않음(이미 반납 완료한 토큰 등), 410: 만료(위치 확인 후 10분), 429: 사진 판정 횟수 초과.
 */
internal fun isReturnSessionOver(httpCode: Int): Boolean = httpCode == 409 || httpCode == 410 || httpCode == 429

/** 반납 순서 중 위치 확인과 반납 완료를 맡는다. 사진 판정은 PhotoAnalyzer 가 맡는다. */
interface ReturnApi {
    suspend fun checkLocation(location: DeviceLocation): LocationCheckResult

    /**
     * 사진이 APPROVE 된 반납을 완료 처리한다.
     * @throws ReturnSessionExpiredException 반납 토큰이 없거나 서버가 더 받지 않는 경우 (위치 확인부터 다시)
     * @throws Exception 서버에 연결하지 못한 경우 등. 다시 시도할 수 있다.
     */
    suspend fun complete(): ReturnReceipt
}

/**
 * AI 서버(ai/ 폴더의 FastAPI)의 반납 API: /api/return/location -> (사진) -> /api/return/complete
 *
 * @param vehicleId 반납할 기기 번호. 아직 기기 번호를 받는 화면이 없어 고정값을 보낸다.
 */
class RemoteReturnApi(
    private val serverUrl: String,
    private val session: ReturnSession,
    private val vehicleId: String = "SAFEZONE-APP",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReturnApi {

    override suspend fun checkLocation(location: DeviceLocation): LocationCheckResult = withContext(ioDispatcher) {
        val body = JSONObject()
            .put("vehicle_id", vehicleId)
            .put("lat", location.latitude)
            .put("lon", location.longitude)
        location.accuracyMeters?.let { body.put("accuracy", it.toDouble()) }
        val json = postJson("/api/return/location", body)
        // 사용자가 위치 확인을 취소한 뒤 늦게 도착한 응답으로 반납 토큰을 바꾸지 않는다.
        ensureActive()
        parseLocationResponse(json, session)
    }

    override suspend fun complete(): ReturnReceipt = withContext(ioDispatcher) {
        val token = session.token ?: throw ReturnSessionExpiredException("반납 토큰이 없음 (위치 확인 필요)")
        val json = try {
            postJson("/api/return/complete", JSONObject().put("token", token))
        } catch (e: ReturnSessionExpiredException) {
            session.clearIfCurrent(token)
            throw e
        }
        // 응답을 기다리는 사이 새 위치 확인으로 토큰이 바뀌었다면 새 토큰은 지우지 않는다.
        session.clearIfCurrent(token)
        parseReturnReceipt(json)
    }

    private fun postJson(path: String, body: JSONObject): String {
        val conn = URL(serverUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (isReturnSessionOver(code)) throw ReturnSessionExpiredException("반납 세션 종료 ($code) $detail")
                throw IOException("반납 서버 오류 ($code) $detail")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 원인(서버 꺼짐·주소 오류 등)은 Logcat 에서 "SafeZone" 으로 검색하면 볼 수 있다.
            Log.w(TAG, "반납 서버 요청 실패: $serverUrl$path", e)
            throw e
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * 위치 확인 응답 JSON -> 결과. 통과하면 토큰을 session 에 저장하고, 아니면 이전 토큰을 지운다.
 * 응답 예: {"ok": true, "code": "PARKING_ZONE", "message": "...", "zone": {"name": "북문 주차구역", ...}, "token": "..."}
 * zone 은 지정 주차구역일 때만 구역 정보(객체)로 오고, 그 외에는 null 이다.
 */
fun parseLocationResponse(json: String, session: ReturnSession): LocationCheckResult {
    val root = JSONObject(json)
    val token = if (root.isNull("token")) null else root.getString("token")
    val ok = root.optBoolean("ok", false) && token != null
    val zone = root.optJSONObject("zone")?.optString("name")?.takeIf { it.isNotBlank() }
    if (ok) {
        session.token = token
        session.zone = zone
    } else {
        session.clear()
    }
    val message = if (root.isNull("message")) "" else root.getString("message")
    return LocationCheckResult(ok = ok, message = message, zone = zone)
}

/**
 * 반납 완료 응답 JSON -> 결과.
 * 응답 예: {"ok": true, "vehicle_id": "SAFEZONE-APP", "zone": {"name": "북문 주차구역", ...}, "return_id": "RT1E3B1846"}
 * zone 은 위치 확인 응답과 같은 형식이다 (지정 주차구역이면 객체, 아니면 null).
 */
fun parseReturnReceipt(json: String): ReturnReceipt {
    val root = JSONObject(json)
    return ReturnReceipt(
        // optString 은 값이 JSON null 이면 글자 "null" 을 돌려주므로 먼저 걸러 낸다.
        returnId = if (root.isNull("return_id")) null else root.optString("return_id").takeIf { it.isNotBlank() },
        zone = root.optJSONObject("zone")?.optString("name")?.takeIf { it.isNotBlank() },
    )
}
