package com.bnyro.clock

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.*
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.location.*
import com.bnyro.clock.util.receivers.AlarmReceiver
import java.time.*
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationAlarmIntegrationTest {
    private val originalTimeZone = TimeZone.getDefault()
    private val app = ApplicationProvider.getApplicationContext<App>()
    private val repository get() = app.container.alarmRepository
    private val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val storage get() = LocationPreferences()
    private val date = LocalDate.of(2026, 11, 2)
    private var now = date.atStartOfDay(ZoneId.of("UTC"))
    private fun clock(hour: Int, minute: Int = 0, day: LocalDate = date) {
        now = day.atTime(hour, minute).atZone(ZoneId.of("UTC"))
    }
    private fun queued() = shadowOf(manager).scheduledAlarms.filter { it.operation != null && shadowOf(it.operation).savedIntent.component?.className == AlarmReceiver::class.java.name }
    private suspend fun add(normal: Long = 28_800_000, away: Long? = 36_000_000): Alarm {
        val alarm = Alarm(time = normal, awayTime = away, enabled = true, startDate = date.toEpochDay())
        return alarm.copy(id = repository.addAlarm(alarm))
    }

    @Before fun setup() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC")); clock(7)
        Preferences.instance.edit().clear().commit()
        repository.getAlarms().forEach { repository.deleteAlarm(it) }
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        storage.saveHome(HomeLocation(0.0, 0.0))
        storage.saveSample(LocationSample(0.01, 0.0, 10f, System.currentTimeMillis()))
        shadowOf(app).clearStartedServices()
    }

    @After fun restoreZone() { TimeZone.setDefault(originalTimeZone) }

    @Test fun dueCommitSurvivesReconcileBeforeDelivery() = runBlocking {
        val alarm = add()
        LocationAlarmCoordinator.reconcile(app, ReconcileReason.USER_EDIT, now)
        val original = Intent(shadowOf(queued().single().operation).savedIntent)
        val trigger = original.getLongExtra(AlarmHelper.EXTRA_TRIGGER_TIME, -1)
        clock(10)
        storage.saveSample(LocationSample(0.0, 0.0, 10f, System.currentTimeMillis()))
        LocationAlarmCoordinator.reconcile(app, now = now)
        assertEquals(trigger, queued().single().triggerAtTime)
        assertEquals(trigger, shadowOf(queued().single().operation).savedIntent.getLongExtra(AlarmHelper.EXTRA_TRIGGER_TIME, -1))
        assertTrue(LocationAlarmCoordinator.deliver(app, alarm.id, trigger, now))
        assertFalse(LocationAlarmCoordinator.deliver(app, alarm.id, trigger, now))
        assertEquals(1, shadowOf(app).allStartedServices.count { it.component?.className?.endsWith("AlarmService") == true })
        assertEquals(date.toEpochDay(), storage.getProgress(alarm.id).consumedDate)
    }

    @Test fun fallbackNeverRequeuesSkippedDate() = runBlocking {
        clock(23, 45)
        val alarm = add(600_000, 85_800_000)
        repository.updateAlarm(alarm.copy(dismissedAt = date.plusDays(1).atTime(0, 10).toInstant(ZoneOffset.UTC).toEpochMilli()))
        storage.saveSample(LocationSample(0.0, 0.0, 10f, System.currentTimeMillis()))
        LocationAlarmCoordinator.reconcile(app, now = now)
        assertEquals(Instant.parse("2026-11-04T00:10:00Z").toEpochMilli(), queued().single().triggerAtTime)
        repeat(3) {
            LocationAlarmCoordinator.reconcile(app, now = now)
            assertEquals(Instant.parse("2026-11-04T00:10:00Z").toEpochMilli(), queued().single().triggerAtTime)
        }
    }

    @Test fun consumptionSnoozeDeleteAndEdit() = runBlocking {
        clock(23, 58)
        val alarm = add(86_340_000, 86_340_000)
        LocationAlarmCoordinator.reconcile(app, ReconcileReason.USER_EDIT, now)
        val trigger = queued().single().triggerAtTime
        clock(23, 59)
        assertTrue(LocationAlarmCoordinator.deliver(app, alarm.id, trigger, now))
        val snooze = LocationAlarmCoordinator.snooze(app, alarm.id, trigger, 10, now)!!
        assertEquals(Instant.parse("2026-11-03T00:09:00Z").toEpochMilli(), snooze)
        clock(0, 3, date.plusDays(1))
        LocationAlarmCoordinator.reconcile(app, now = now)
        assertEquals(snooze, queued().single().triggerAtTime)
        clock(0, 9, date.plusDays(1))
        assertTrue(LocationAlarmCoordinator.deliver(app, alarm.id, snooze, now))
        assertEquals(date.toEpochDay(), storage.getProgress(alarm.id).consumedDate)
        val useCase = CreateUpdateDeleteAlarmUseCase(app, repository, Clock.fixed(now.toInstant(), now.zone))
        useCase.updateAlarm(repository.getAlarmById(alarm.id)!!.copy(time = 600_000, awayTime = 1_200_000))
        assertEquals(date.plusDays(1), storage.getOccurrence(alarm.id)!!.date)
        useCase.deleteAlarm(alarm)
        assertTrue(queued().isEmpty()); assertNull(storage.getOccurrence(alarm.id)); assertNull(repository.getAlarmById(alarm.id))
    }

    @Test fun recoverAfterBootClockAndPermissionGrant() = runBlocking {
        val alarm = add()
        LocationAlarmCoordinator.reconcile(app, ReconcileReason.USER_EDIT, now)
        clock(7, 45)
        storage.saveSample(LocationSample(0.0, 0.0, 10f, System.currentTimeMillis()))
        LocationAlarmCoordinator.reconcile(app, ReconcileReason.RECOVERY, now)
        assertEquals(36_000_000L, storage.getOccurrence(alarm.id)!!.timeOfDay)
        clock(10, 1)
        LocationAlarmCoordinator.reconcile(app, ReconcileReason.RECOVERY, now)
        assertEquals(date.plusDays(1), storage.getOccurrence(alarm.id)!!.date)
    }

    @Test fun newAlarmAnchorsTodayWhenHomeHasPassedButAwayIsFuture() = runBlocking {
        clock(9)
        storage.saveSample(LocationSample(0.01, 0.0, 10f, System.currentTimeMillis()))
        CreateUpdateDeleteAlarmUseCase(app, repository, Clock.fixed(now.toInstant(), now.zone)).createAlarm(Alarm(time = 28_800_000, awayTime = 36_000_000, enabled = true, endOccurrences = 1, startDate = date.toEpochDay()))
        assertEquals(Instant.parse("2026-11-02T10:00:00Z").toEpochMilli(), queued().single().triggerAtTime)
    }

    @Test fun consumedWallDateDoesNotSkipNextDateAfterTimezoneChange() = runBlocking {
        clock(23, 40)
        val alarm = add(85_800_000, 85_800_000)
        LocationAlarmCoordinator.reconcile(app, ReconcileReason.USER_EDIT, now)
        val trigger = queued().single().triggerAtTime
        clock(23, 50)
        assertTrue(LocationAlarmCoordinator.deliver(app, alarm.id, trigger, now))
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Athens"))
        clock(0, 5, date.plusDays(1))
        now = now.withZoneSameInstant(ZoneId.of("Europe/Athens"))
        LocationAlarmCoordinator.reconcile(app, now = now)
        assertEquals(Instant.parse("2026-11-03T21:50:00Z").toEpochMilli(), queued().single().triggerAtTime)
    }

    @Test fun ruleEditsAndRuleRemovalCannotReviveConsumedDate() = runBlocking {
        val alarm = add()
        LocationAlarmCoordinator.reconcile(app, ReconcileReason.USER_EDIT, now)
        val trigger = queued().single().triggerAtTime
        clock(10)
        assertTrue(LocationAlarmCoordinator.deliver(app, alarm.id, trigger, now))
        clock(10, 1)
        val useCase = CreateUpdateDeleteAlarmUseCase(app, repository, Clock.fixed(now.toInstant(), now.zone))
        useCase.updateAlarm(repository.getAlarmById(alarm.id)!!.copy(awayTime = 43_200_000))
        assertEquals(date.plusDays(1), storage.getOccurrence(alarm.id)!!.date)
        useCase.updateAlarm(repository.getAlarmById(alarm.id)!!.copy(time = 43_200_000, awayTime = null))
        assertNull(storage.getOccurrence(alarm.id))
        assertEquals(date.plusDays(1), AlarmHelper.getNextOccurrence(repository.getAlarmById(alarm.id)!!, now = now.toLocalDateTime()))
    }

    @Test fun originalBroadcastDeliversOnceAfterStartupReconcile() = runBlocking {
        val past = ZonedDateTime.now().minusMinutes(1).withSecond(0).withNano(0)
        val time = past.toLocalTime().toNanoOfDay() / 1_000_000
        val definition = Alarm(time = time, awayTime = time, enabled = true, startDate = past.toLocalDate().toEpochDay())
        val alarm = definition.copy(id = repository.addAlarm(definition))
        storage.saveOccurrence(alarm.id, LocationOccurrence(past.toLocalDate(), time, LocationAlarmScheduler.configKey(alarm)))
        AlarmHelper.schedule(app, alarm, past.toInstant().toEpochMilli())
        val original = Intent(shadowOf(queued().single().operation).savedIntent)
        LocationAlarmCoordinator.reconcile(app)
        app.sendBroadcast(original)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        withTimeout(5_000) { app.applicationScope.coroutineContext[Job]!!.children.toList().joinAll() }
        assertEquals(past.toInstant().toEpochMilli(), storage.getProgress(alarm.id).lastTrigger)
        app.sendBroadcast(original)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        withTimeout(5_000) { app.applicationScope.coroutineContext[Job]!!.children.toList().joinAll() }
        assertEquals(1, shadowOf(app).allStartedServices.count { it.component?.className?.endsWith("AlarmService") == true })
    }
}
