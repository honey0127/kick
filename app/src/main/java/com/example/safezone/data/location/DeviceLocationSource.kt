package com.example.safezone.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 휴대폰의 현재 위치를 한 번 얻는다. 테스트에서는 가짜 위치를 넣는다. */
interface DeviceLocationSource {
    /** 위치를 얻지 못하면(위치 꺼짐, 시간 초과 등) null. 권한은 호출하는 화면에서 먼저 확인한다. */
    suspend fun current(): DeviceLocation?
}

/** 앱이 받은 위치 권한 수준. */
enum class LocationPermission {
    NONE,

    /** '대략적 위치'만 허용됨. 오차가 km 단위라 주차 구역을 가릴 수 없다. */
    APPROXIMATE,

    /** '정확한 위치'까지 허용됨. 반납 위치 확인에는 이 권한이 필요하다. */
    PRECISE,
}

fun Context.locationPermission(): LocationPermission = when {
    isGranted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationPermission.PRECISE
    isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationPermission.APPROXIMATE
    else -> LocationPermission.NONE
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** 이 정도로 정확한 위치가 오면 더 기다리지 않는다 (GPS 가 잡혔을 때 흔히 나오는 오차 범위). */
private const val GOOD_ACCURACY_METERS = 15f

/**
 * Android 기본 LocationManager 로 현재 위치를 얻는다 (Google Play 서비스 없이 동작).
 *
 * 켜져 있는 위치 제공자(GPS, 네트워크, Android 12 이상은 통합 제공자)에 동시에 묻고 가장 정확한 값을 쓴다.
 * GPS 는 정확하지만 실내에서는 잘 잡히지 않고, 네트워크 위치는 실내에서도 잡히지만 덜 정확하기 때문이다.
 * 새 위치를 얻지 못하면 null 을 돌려준다. 기기에 남아 있는 '마지막으로 알려진 위치'는 쓰지 않는다
 * (언제 어디서 잡힌 것인지 알 수 없어, 지금 있는 곳이 아닌 위치로 반납 가능 여부를 판단하게 된다).
 *
 * @param timeoutMillis 첫 위치를 기다리는 최대 시간
 * @param settleMillis 첫 위치가 덜 정확할 때, 더 정확한 위치를 기다려 보는 시간
 */
class AndroidLocationSource(
    private val context: Context,
    private val timeoutMillis: Long = 20_000,
    private val settleMillis: Long = 5_000,
) : DeviceLocationSource {

    @SuppressLint("MissingPermission") // 위치 확인 화면에서 '정확한 위치' 권한을 받은 뒤에만 부른다
    override suspend fun current(): DeviceLocation? {
        if (context.locationPermission() != LocationPermission.PRECISE) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            // 통합 제공자는 Android 12(API 31)부터 LocationManager 로 쓸 수 있다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }.filter { manager.isProviderEnabled(it) }

        return pickBestLocation(providers, timeoutMillis, settleMillis) { provider ->
            suspendCancellableCoroutine<DeviceLocation?> { cont ->
                val signal = CancellationSignal()
                // 더 기다릴 필요가 없어지면(다른 제공자가 먼저 답함, 화면을 나감) 위치 요청도 멈춘다.
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, signal, ContextCompat.getMainExecutor(context),
                ) { location ->
                    // 제공자가 위치를 얻지 못하면 null 이 온다.
                    if (cont.isActive) cont.resume(location?.toDeviceLocation())
                }
            }
        }
    }
}

private fun Location.toDeviceLocation(): DeviceLocation =
    DeviceLocation(latitude, longitude, if (hasAccuracy()) accuracy else null)

/**
 * 여러 위치 제공자에 동시에 묻고, 돌아온 위치 가운데 가장 정확한 것을 고른다.
 *
 * - 충분히 정확한 위치([goodAccuracyMeters] 이하)가 오면 바로 그 위치를 쓴다.
 * - 덜 정확한 위치가 먼저 오면 [settleMillis] 만큼만 더 기다려 보고, 그때까지 온 것 중 가장 정확한 위치를 쓴다.
 * - [timeoutMillis] 안에 어떤 제공자도 위치를 주지 않으면 null.
 *
 * @param request 제공자 하나에 현재 위치를 한 번 묻는다. 얻지 못하면 null.
 */
internal suspend fun pickBestLocation(
    providers: List<String>,
    timeoutMillis: Long,
    settleMillis: Long,
    goodAccuracyMeters: Float = GOOD_ACCURACY_METERS,
    request: suspend (provider: String) -> DeviceLocation?,
): DeviceLocation? = coroutineScope {
    // 제공자마다 답(위치 또는 null)을 하나씩 보낸다.
    val answers = Channel<DeviceLocation?>(Channel.UNLIMITED)
    val requests = providers.map { provider ->
        launch {
            val answer = try {
                request(provider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // 이 제공자를 쓸 수 없으면 다른 제공자의 답을 기다린다
            }
            answers.send(answer)
        }
    }

    var best: DeviceLocation? = null
    var unanswered = providers.size

    // 1) 첫 위치가 올 때까지 기다린다.
    withTimeoutOrNull(timeoutMillis) {
        while (best == null && unanswered > 0) {
            best = answers.receive()
            unanswered--
        }
    }
    // 2) 첫 위치가 덜 정확하면, 더 정확한 위치가 오는지 조금만 더 기다린다.
    if (best != null) {
        withTimeoutOrNull(settleMillis) {
            while (best.accuracyOrWorst() > goodAccuracyMeters && unanswered > 0) {
                val next = answers.receive()
                unanswered--
                if (next != null && next.accuracyOrWorst() < best.accuracyOrWorst()) best = next
            }
        }
    }

    // 아직 답하지 않은 제공자에는 더 묻지 않는다.
    requests.forEach { it.cancel() }
    best
}

/** 오차를 모르는 위치는 가장 덜 정확한 것으로 친다. */
private fun DeviceLocation?.accuracyOrWorst(): Float = this?.accuracyMeters ?: Float.MAX_VALUE
