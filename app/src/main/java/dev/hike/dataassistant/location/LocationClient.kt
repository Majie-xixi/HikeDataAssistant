package dev.hike.dataassistant.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import dev.hike.dataassistant.data.DataSources
import dev.hike.dataassistant.data.PhonePosition
import dev.hike.dataassistant.data.Reading
import dev.hike.dataassistant.data.ReadingStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * 按需定位（开发文档 §7）：只在 App 可见或生成分享包时主动请求一次；
 * 平时可选被动接收系统已生成的位置更新"最近位置"。
 * 不做持续高精度定位。使用框架 LocationManager，不依赖 GMS。
 */
class LocationClient(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _lastPassive = MutableStateFlow<Reading<PhonePosition>?>(null)
    val lastPassive: StateFlow<Reading<PhonePosition>?> = _lastPassive

    private var passiveRegistered = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 主动获取一次新位置。超时后回退：新鲜 lastKnown 可用则标记 UNCERTAIN 返回，
     * 否则 MISSING。时效/精度的最终判定由 RouteContextResolver 完成。
     */
    suspend fun getCurrentPosition(timeoutMs: Long = 15_000): Reading<PhonePosition> {
        if (!hasPermission()) {
            return Reading(null, DataSources.PHONE_LOCATION, null, ReadingStatus.MISSING, note = "未授予定位权限")
        }
        if (!anyProviderEnabled()) {
            return Reading(null, DataSources.PHONE_LOCATION, null, ReadingStatus.MISSING, note = "定位服务未开启")
        }
        val fresh = withTimeoutOrNull(timeoutMs) { requestCurrentFix() }
        if (fresh != null) {
            return Reading(
                value = fresh.toPhonePosition(),
                source = DataSources.PHONE_LOCATION,
                sampledAtEpochMs = fresh.time,
                status = ReadingStatus.FRESH
            )
        }
        val last = bestLastKnown()
        return if (last != null && System.currentTimeMillis() - last.time <= 5 * 60_000) {
            Reading(
                value = last.toPhonePosition(),
                source = DataSources.PHONE_LOCATION,
                sampledAtEpochMs = last.time,
                status = ReadingStatus.UNCERTAIN,
                note = "新定位超时，返回最近已知位置（${(System.currentTimeMillis() - last.time) / 1000} 秒前）"
            )
        } else {
            Reading(null, DataSources.PHONE_LOCATION, null, ReadingStatus.MISSING, note = "定位超时（${timeoutMs / 1000} 秒）")
        }
    }

    /** 被动监听：仅当其他应用（如两步路）触发定位时收到更新，不主动耗电。 */
    fun startPassive() {
        if (passiveRegistered || !hasPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                60_000L,
                0f,
                passiveListener,
                context.mainLooper
            )
            passiveRegistered = true
        } catch (_: SecurityException) {
        }
    }

    fun stopPassive() {
        if (!passiveRegistered) return
        locationManager.removeUpdates(passiveListener)
        passiveRegistered = false
    }

    private val passiveListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _lastPassive.value = Reading(
                value = location.toPhonePosition(),
                source = DataSources.PHONE_LOCATION,
                sampledAtEpochMs = location.time,
                status = ReadingStatus.UNCERTAIN,
                note = "被动位置（其他应用触发）"
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private fun anyProviderEnabled(): Boolean =
        enabledProviders().isNotEmpty()

    private fun enabledProviders(): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .filter { locationManager.allProviders.contains(it) && locationManager.isProviderEnabled(it) }

    /** 并发请求所有可用定位源，取最先返回的非空定位并取消其余。 */
    private suspend fun requestCurrentFix(): Location? = coroutineScope {
        val providers = enabledProviders()
        if (providers.isEmpty()) return@coroutineScope null
        val channel = Channel<Location?>(capacity = providers.size)
        val jobs = providers.map { provider ->
            launch { channel.send(requestFromProvider(provider).firstOrNull()) }
        }
        var result: Location? = null
        try {
            repeat(providers.size) {
                val candidate = channel.receive()
                if (candidate != null) {
                    result = candidate
                    return@coroutineScope result // finally 会取消其余请求
                }
            }
        } finally {
            jobs.forEach { it.cancel() }
        }
        result
    }

    private fun requestFromProvider(provider: String): Flow<Location?> = callbackFlow {
        val listener = LocationListener { location ->
            trySend(location)
            close()
        }
        val executor = Executors.newSingleThreadExecutor()
        val signal = CancellationSignal()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.getCurrentLocation(provider, signal, executor) { location ->
                    trySend(location)
                    close()
                }
            } else {
                // API 29：getCurrentLocation 尚不存在，一次性注册+首结果即撤
                @Suppress("MissingPermission")
                locationManager.requestLocationUpdates(provider, 0L, 0f, listener, context.mainLooper)
            }
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }
        awaitClose {
            signal.cancel()
            locationManager.removeUpdates(listener)
            executor.shutdown()
        }
    }

    private fun bestLastKnown(): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p ->
                try {
                    locationManager.getLastKnownLocation(p)
                } catch (_: SecurityException) {
                    null
                }
            }
            .maxByOrNull { it.time }

    private fun Location.toPhonePosition() = PhonePosition(
        lat = latitude,
        lon = longitude,
        accuracyMeters = accuracy,
        altitudeMeters = if (hasAltitude()) altitude else null
    )
}
