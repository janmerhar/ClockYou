package com.bnyro.clock.util.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.LocationSample
import com.bnyro.clock.util.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

data class LocationStatus(
    val state: LocationState = LocationState.UNKNOWN,
    val sample: LocationSample? = null,
    val foregroundPermission: Boolean = false,
    val backgroundPermission: Boolean = false,
    val networkAvailable: Boolean = false
)

class HomeLocationTracker(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val storage = LocationPreferences()
    private val registrationMutex = Mutex()
    private var networkRegistered = false
    private var recheckRegistered = false
    private val mutableStatus = MutableStateFlow(LocationStatus())
    val status = mutableStatus.asStateFlow()

    fun hasForegroundPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission() = hasForegroundPermission() && (Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    private fun networkAvailable() = runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)

    private fun publishStatus() {
        val foreground = hasForegroundPermission()
        val network = networkAvailable()
        val sample = storage.getSample()
        mutableStatus.value = LocationStatus(
            if (hasBackgroundPermission() && network) LocationAlarmPolicy.classify(storage.getHome(), sample, System.currentTimeMillis()) else LocationState.UNKNOWN,
            sample, foreground, hasBackgroundPermission(), network
        )
    }

    @SuppressLint("MissingPermission")
    private fun cachedSamples(): List<LocationSample> {
        if (!hasForegroundPermission()) return emptyList()
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider)?.toSample() }.getOrNull()
        }
    }

    fun refresh() {
        cachedSamples().forEach(storage::saveSample)
        publishStatus()
    }

    @SuppressLint("MissingPermission")
    suspend fun captureHome(): LocationSample? = withContext(Dispatchers.IO) {
        if (!hasForegroundPermission()) return@withContext null
        val now = System.currentTimeMillis()
        val cached = cachedSamples().filter { acceptCapture(it, now) }.maxByOrNull { it.timestampMillis }
        val result = cached ?: withTimeoutOrNull(30_000) {
            var listener: LocationListener? = null
            try {
                suspendCancellableCoroutine<LocationSample?> { continuation ->
                    val captureListener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            val sample = location.toSample() ?: return
                            if (acceptCapture(sample, System.currentTimeMillis()) && continuation.isActive) continuation.resume(sample)
                        }
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                        @Deprecated("Required on older Android versions")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    }
                    listener = captureListener
                    continuation.invokeOnCancellation { manager.removeUpdates(captureListener) }
                    val providers = manager.getProviders(true).filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
                    try {
                        if (providers.isEmpty()) continuation.resume(null)
                        else providers.forEach { manager.requestLocationUpdates(it, 0, 0f, captureListener, Looper.getMainLooper()) }
                    } catch (_: SecurityException) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            } finally { listener?.let(manager::removeUpdates) }
        }
        result?.let(storage::saveSample)
        publishStatus()
        result
    }

    @SuppressLint("MissingPermission")
    suspend fun updateRegistration() = withContext(Dispatchers.IO) {
        registrationMutex.withLock {
            val eligible = storage.getHome() != null && hasBackgroundPermission() &&
                (context.applicationContext as App).container.alarmRepository.getAlarms().any { it.enabled && it.awayTime != null }
            val locationIntent = locationIntent()
            if (eligible && networkAvailable()) {
                if (!networkRegistered) {
                    try {
                        manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, RECHECK_MILLIS, 0f, locationIntent)
                        networkRegistered = true
                    } catch (_: SecurityException) { manager.removeUpdates(locationIntent) }
                }
            } else {
                manager.removeUpdates(locationIntent)
                networkRegistered = false
            }
            if (eligible) {
                if (!recheckRegistered) {
                    val now = System.currentTimeMillis()
                    val saved = Preferences.instance.getLong(RECHECK_DEADLINE, now + RECHECK_MILLIS)
                    val next = if (saved > now) saved else saved + ((now - saved) / RECHECK_MILLIS + 1) * RECHECK_MILLIS
                    alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + (next - now), RECHECK_MILLIS, recheckIntent())
                    check(Preferences.instance.edit().putLong(RECHECK_DEADLINE, next).commit())
                    recheckRegistered = true
                }
            } else {
                alarmManager.cancel(recheckIntent())
                recheckRegistered = false
                Preferences.instance.edit().remove(RECHECK_DEADLINE).apply()
            }
            publishStatus()
        }
    }

    private fun locationIntent(): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, HomeLocationReceiver::class.java).setAction(LOCATION_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)

    private fun recheckIntent(): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, HomeLocationReceiver::class.java).setAction(RECHECK_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val LOCATION_ACTION = "com.bnyro.clock.LOCATION_UPDATE"
        const val RECHECK_ACTION = "com.bnyro.clock.LOCATION_RECHECK"
        private const val RECHECK_DEADLINE = "location_runtime_recheck_deadline"
        private const val RECHECK_MILLIS = 30 * 60 * 1000L

        fun acceptCapture(sample: LocationSample, nowMillis: Long) =
            LocationPreferences.validSample(sample, nowMillis) && nowMillis - sample.timestampMillis <= 120_000 && sample.accuracyMeters <= 100
    }
}

fun Location.toSample(): LocationSample? =
    if (hasAccuracy()) LocationSample(latitude, longitude, accuracy, time) else null
