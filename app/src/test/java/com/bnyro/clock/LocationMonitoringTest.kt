package com.bnyro.clock

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.HomeLocation
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.location.HomeLocationTracker
import com.bnyro.clock.util.location.LocationPreferences
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationMonitoringTest {
    private val app = ApplicationProvider.getApplicationContext<App>()
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val storage get() = LocationPreferences()
    private val tracker by lazy { HomeLocationTracker(app) }

    @Before fun setup() = runBlocking {
        Preferences.instance.edit().clear().commit()
        app.container.alarmRepository.getAlarms().forEach { app.container.alarmRepository.deleteAlarm(it) }
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    private suspend fun eligible() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        storage.saveHome(HomeLocation(0.0, 0.0))
        app.container.alarmRepository.addAlarm(Alarm(time = 28_800_000, awayTime = 36_000_000, enabled = true))
    }

    @Test fun registrationStopsWithoutHomeRuleOrBackgroundPermission() = runBlocking {
        tracker.updateRegistration()
        assertTrue(shadowOf(manager).locationUpdatePendingIntents.isEmpty())
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        eligible()
        tracker.updateRegistration()
        assertEquals(1, shadowOf(manager).getLocationUpdatePendingIntents(LocationManager.NETWORK_PROVIDER).size)
        assertTrue(shadowOf(manager).getLocationUpdatePendingIntents(LocationManager.GPS_PROVIDER).isEmpty())
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        tracker.updateRegistration()
        assertTrue(shadowOf(manager).locationUpdatePendingIntents.isEmpty())
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test fun providerRecoveryAndRepeatedCallbacksKeepRecheckDeadline() = runBlocking {
        eligible()
        tracker.updateRegistration()
        val deadline = shadowOf(alarms).scheduledAlarms.single().triggerAtTime
        repeat(3) { tracker.updateRegistration() }
        assertEquals(deadline, shadowOf(alarms).scheduledAlarms.single().triggerAtTime)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        tracker.updateRegistration()
        assertTrue(shadowOf(manager).locationUpdatePendingIntents.isEmpty())
        assertEquals(deadline, shadowOf(alarms).scheduledAlarms.single().triggerAtTime)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        tracker.updateRegistration()
        assertEquals(1, shadowOf(manager).locationUpdatePendingIntents.size)
        storage.clearHome()
        tracker.updateRegistration()
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test fun deniedBackgroundUsesUnknownAndDisablingLastRuleStopsMonitoring() = runBlocking {
        eligible()
        storage.saveSample(com.bnyro.clock.domain.model.LocationSample(0.0, 0.0, 10f, System.currentTimeMillis()))
        tracker.updateRegistration()
        assertEquals(com.bnyro.clock.util.location.LocationState.HOME, tracker.status.value.state)
        val alarm = app.container.alarmRepository.getAlarms().single()
        app.container.alarmRepository.updateAlarm(alarm.copy(enabled = false))
        tracker.updateRegistration()
        assertTrue(shadowOf(manager).locationUpdatePendingIntents.isEmpty())
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        tracker.refresh()
        assertEquals(com.bnyro.clock.util.location.LocationState.UNKNOWN, tracker.status.value.state)
    }

    @Test fun foregroundCaptureAcceptsOnlyFreshAccurateFix() = runBlocking {
        eligible()
        val now = System.currentTimeMillis()
        assertTrue(HomeLocationTracker.acceptCapture(com.bnyro.clock.domain.model.LocationSample(0.0, 0.0, 100f, now - 120_000), now))
        assertFalse(HomeLocationTracker.acceptCapture(com.bnyro.clock.domain.model.LocationSample(0.0, 0.0, 100.1f, now), now))
        assertFalse(HomeLocationTracker.acceptCapture(com.bnyro.clock.domain.model.LocationSample(0.0, 0.0, 10f, now - 120_001), now))
        val fix = Location(LocationManager.GPS_PROVIDER).apply { latitude = 0.0; longitude = 0.0; accuracy = 25f; time = now }
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix)
        assertEquals(25f, tracker.captureHome()!!.accuracyMeters)
        assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
    }

    @Test fun captureCancellationRemovesListeners() = runBlocking {
        eligible()
        val capture = async { tracker.captureHome() }
        withTimeout(5_000) { while (shadowOf(manager).locationUpdateListeners.isEmpty()) delay(10) }
        capture.cancelAndJoin()
        assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
    }

    @Test fun liveCaptureSuccessRemovesListeners() = runBlocking {
        eligible()
        val capture = async { tracker.captureHome() }
        withTimeout(5_000) { while (shadowOf(manager).locationUpdateListeners.isEmpty()) delay(10) }
        shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 46.0; longitude = 14.0; accuracy = 15f; time = System.currentTimeMillis()
        })
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(46.0, withTimeout(5_000) { capture.await() }!!.latitude, 0.0)
        assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
    }

    @Test fun captureTimeoutRemovesListeners() = runBlocking {
        eligible()
        assertNull(withTimeout(35_000) { tracker.captureHome() })
        assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
    }
}
