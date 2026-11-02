package com.bnyro.clock

import android.Manifest
import android.content.Context
import android.location.LocationManager
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.*
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.location.*
import java.time.Clock
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import android.content.Intent
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.services.AlarmService
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmConcurrencyTest {
    @Test fun lateMissingRowStopsOnlyItsOwnServiceStart() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        Preferences.instance.edit().clear().commit()
        val main = shadowOf(Looper.getMainLooper())
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        try {
            main.idle()
            controller.get().onStartCommand(Intent(app, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, -100L).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, 0L), 0, 1)
            withTimeout(5_000) { while (main.isIdle) delay(10) }
            val row = Alarm(time = 28_800_000, enabled = true)
            val alarm = row.copy(id = app.container.alarmRepository.addAlarm(row))
            val trigger = System.currentTimeMillis() - 1
            LocationPreferences().saveProgress(alarm.id, LocationProgress(lastTrigger = trigger))
            controller.get().onStartCommand(Intent(app, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, alarm.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, trigger), 0, 2)
            main.idle()
            assertEquals(1, shadowOf(controller.get()).stopSelfId)
        } finally { controller.destroy() }
    }

    @Test fun lateSnoozeStopsOnlyItsActiveServiceStart() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        Preferences.instance.edit().clear().commit()
        val main = shadowOf(Looper.getMainLooper())
        val component = android.content.ComponentName(app, com.bnyro.clock.util.receivers.PreAlarmReceiver::class.java)
        val oldState = app.packageManager.getComponentEnabledSetting(component)
        app.packageManager.setComponentEnabledSetting(component, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        try {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            val repository = app.container.alarmRepository
            val first = Alarm(time = 28_800_000, enabled = true, snoozeMinutes = 1)
            val a = first.copy(id = repository.addAlarm(first))
            val b = first.copy(id = repository.addAlarm(first.copy()))
            val trigger = System.currentTimeMillis() / 60_000 * 60_000
            LocationPreferences().saveProgress(a.id, LocationProgress(lastTrigger = trigger))
            LocationPreferences().saveProgress(b.id, LocationProgress(lastTrigger = trigger + 1))
            shadowOf(app).clearNextStartedActivities()
            main.idle()
            controller.get().onStartCommand(Intent(app, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, a.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, trigger), 0, 1)
            withTimeout(5_000) {
                while (shadowOf(app).peekNextStartedActivity() == null) { main.idle(); delay(10) }
            }
            shadowOf(app).clearNextStartedActivities()
            app.sendBroadcast(Intent(AlarmService.ALARM_INTENT_ACTION).setPackage(app.packageName)
                .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.SNOOZE_ACTION))
            main.idle()
            withTimeout(5_000) { while (main.isIdle) delay(10) }
            controller.get().onStartCommand(Intent(app, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, b.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, trigger + 1), 0, 2)
            main.idle()
            assertEquals(1, shadowOf(controller.get()).stopSelfId)
        } finally {
            controller.destroy()
            app.packageManager.setComponentEnabledSetting(component, oldState, android.content.pm.PackageManager.DONT_KILL_APP)
        }
    }

    @Test fun deletionWhileServiceLoadsCannotStartStaleRinging() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        Preferences.instance.edit().clear().commit()
        val repository = app.container.alarmRepository
        val row = Alarm(time = 28_800_000, enabled = true)
        val alarm = row.copy(id = repository.addAlarm(row))
        val trigger = System.currentTimeMillis() - 60_000
        LocationPreferences().saveProgress(alarm.id, LocationProgress(lastTrigger = trigger))
        val main = shadowOf(Looper.getMainLooper())
        main.idle()
        assertFalse(Dispatchers.Main.immediate.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext))
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        try {
            shadowOf(app).clearNextStartedActivities()
            controller.get().onStartCommand(Intent(app, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, alarm.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, trigger), 0, 1)
            withTimeout(5_000) { while (main.isIdle) delay(10) }
            CreateUpdateDeleteAlarmUseCase(app, repository).deleteAlarm(alarm)
            main.idle()
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { controller.destroy() }
    }

    @Test fun mainCrudIoDoesNotDeadlockDelivery() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        val original = app.container
        val reachedUpdate = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val gate = AtomicBoolean(false)
        val database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (sql.trimStart().startsWith("UPDATE", true) && gate.compareAndSet(true, false)) {
                    reachedUpdate.countDown()
                    check(releaseUpdate.await(10, TimeUnit.SECONDS)) { "Test Room gate was not released" }
                }
            }, Executor { it.run() }).build()
        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var crud: Job? = null
        try {
            app.container = AppContainer(database)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val before = ZonedDateTime.parse("2026-11-02T07:00:00Z")
            val due = ZonedDateTime.parse("2026-11-02T10:00:00Z")
            Preferences.instance.edit().clear().commit()
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            LocationPreferences().saveHome(HomeLocation(0.0, 0.0))
            LocationPreferences().saveSample(LocationSample(0.01, 0.0, 10f, System.currentTimeMillis()))
            val repository = app.container.alarmRepository
            val alarm = Alarm(time = 28_800_000, awayTime = 36_000_000, enabled = true, startDate = 20_759)
            val row = alarm.copy(id = repository.addAlarm(alarm))
            LocationAlarmCoordinator.reconcile(app, ReconcileReason.USER_EDIT, before)
            gate.set(true)
            crud = mainScope.launch(start = CoroutineStart.UNDISPATCHED) {
                CreateUpdateDeleteAlarmUseCase(app, repository, Clock.fixed(due.toInstant(), ZoneId.of("UTC"))).updateAlarm(row.copy(label = "Edited"))
            }
            assertTrue("Real Room update must reach the IO gate", reachedUpdate.await(5, TimeUnit.SECONDS))
            val delivery = async(Dispatchers.IO) {
                LocationAlarmCoordinator.deliver(app, row.id, Instant.parse("2026-11-02T10:00:00Z").toEpochMilli(), due)
            }
            releaseUpdate.countDown()
            // Main's looper stays paused until delivery has completed.
            assertTrue(withTimeout(5_000) { delivery.await() })
            assertEquals("Edited", repository.getAlarmById(row.id)!!.label)
        } finally {
            releaseUpdate.countDown()
            mainScope.cancel()
            withTimeout(5_000) {
                while (crud?.isCompleted == false) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            }
            database.close()
            app.container = original
        }
    }
}
