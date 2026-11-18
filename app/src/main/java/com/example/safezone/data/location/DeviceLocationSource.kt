package com.example.safezone.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 휴대폰의 현재 위치를 한 번 얻는다. 테스트에서는 가짜 위치를 넣는다. */
interface DeviceLocationSource {
    /** 위치를 얻지 못하면(위치 꺼짐, 시간 초과 등) null. 권한은 호출하는 화면에서 먼저 확인한다. */
    suspend fun current(): DeviceLocation?
}

fun Context.hasLocationPermission(): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Android 기본 LocationManager 로 현재 위치를 얻는다 (Google Play 서비스 없이 동작).
 * GPS 가 켜져 있으면 GPS, 아니면 네트워크 위치를 쓰고, 시간 안에 못 얻으면 마지막으로 알려진 위치를 쓴다.
 */
class AndroidLocationSource(
    private val context: Context,
    private val timeoutMillis: Long = 20_000,
) : DeviceLocationSource {

    @SuppressLint("MissingPermission") // 위치 확인 화면에서 권한을 받은 뒤에만 부른다
    override suspend fun current(): DeviceLocation? {
        if (!context.hasLocationPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { manager.isProviderEnabled(it) } ?: return null

        val location = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, signal, ContextCompat.getMainExecutor(context),
                ) { loc -> if (cont.isActive) cont.resume(loc) }
            }
        } ?: manager.getLastKnownLocation(provider)

        return location?.let {
            DeviceLocation(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null)
        }
    }
}
