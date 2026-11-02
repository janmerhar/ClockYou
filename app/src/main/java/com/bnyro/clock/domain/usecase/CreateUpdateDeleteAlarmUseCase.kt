package com.bnyro.clock.domain.usecase

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.location.*
import com.bnyro.clock.util.services.AlarmService
import java.time.Clock
import java.time.ZonedDateTime
import java.time.Instant
import java.time.ZoneId

class CreateUpdateDeleteAlarmUseCase(
    private val context: Context,
    private val alarmRepository: AlarmRepository,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val app get() = context.applicationContext as App

    private fun anchorStartDate(alarm: Alarm) {
        val selected = LocationAlarmPolicy.chooseTime(alarm.time, alarm.awayTime, app.locationTracker.status.value.state)
        val recurrence = alarm.copy(time = selected, awayTime = null)
        val next = AlarmHelper.getNextOccurrence(recurrence, now = ZonedDateTime.now(clock).toLocalDateTime()) ?: return
        alarm.startDate = if (alarm.endOccurrences != null) next.toEpochDay()
            else AlarmHelper.getNextRepetitionStart(recurrence, ZonedDateTime.now(clock).toLocalDateTime())?.toEpochDay() ?: alarm.startDate
    }

    suspend fun createAlarm(alarm: Alarm): Alarm {
        val created = LocationAlarmCoordinator.serialized {
            app.locationTracker.refresh()
            val candidate = alarm.copy(id = 0, snoozedUntil = null, dismissedAt = null)
            anchorStartDate(candidate)
            val row = candidate.copy(id = alarmRepository.addAlarm(candidate))
            LocationAlarmCoordinator.scheduleLocked(context, row, ReconcileReason.USER_EDIT, ZonedDateTime.now(clock))
            row
        }
        app.locationTracker.updateRegistration()
        return created
    }

    suspend fun updateAlarm(alarm: Alarm): Alarm? {
        val updated = LocationAlarmCoordinator.serialized {
            val old = alarmRepository.getAlarmById(alarm.id) ?: return@serialized null
            app.locationTracker.refresh()
            val rearm = old.enabled != alarm.enabled
            val candidate = alarm.copy(snoozedUntil = if (rearm) null else old.snoozedUntil,
                dismissedAt = if (rearm) null else old.dismissedAt)
            if (rearm) {
                LocationPreferences().clearAlarm(alarm.id)
                anchorStartDate(candidate)
            }
            val changed = rearm || LocationAlarmScheduler.configKey(candidate) != LocationAlarmScheduler.configKey(old)
            alarmRepository.updateAlarm(candidate)
            if (changed || candidate.awayTime != null || candidate.snoozedUntil != null) {
                LocationAlarmCoordinator.scheduleLocked(context, candidate, if (changed) ReconcileReason.USER_EDIT else ReconcileReason.AUTOMATIC, ZonedDateTime.now(clock))
            }
            candidate
        }
        app.locationTracker.updateRegistration()
        return updated
    }

    suspend fun dismissUpcomingAlarm(alarm: Alarm) {
        LocationAlarmCoordinator.serialized {
            val current = alarmRepository.getAlarmById(alarm.id) ?: return@serialized
            if (current.snoozedUntil == null && current.dismissedAt?.let { it > System.currentTimeMillis() } == true) return@serialized
            val trigger = AlarmHelper.getAlarmTime(current) ?: return@serialized
            val storage = LocationPreferences()
            if (current.snoozedUntil == null) {
                val date = Instant.ofEpochMilli(trigger).atZone(ZoneId.systemDefault()).toLocalDate()
                storage.saveProgress(current.id, storage.getProgress(current.id).copy(skippedDate = date.toEpochDay()))
            }
            val updated = current.copy(snoozedUntil = null, dismissedAt = if (current.snoozedUntil == null) trigger else current.dismissedAt)
            storage.clearOccurrence(current.id)
            alarmRepository.updateAlarm(updated)
            LocationAlarmCoordinator.scheduleLocked(context, updated)
        }
        app.locationTracker.updateRegistration()
    }

    suspend fun deleteAlarm(alarm: Alarm) {
        LocationAlarmCoordinator.serialized {
            val current = alarmRepository.getAlarmById(alarm.id) ?: return@serialized
            alarmRepository.deleteAlarm(current)
            AlarmHelper.cancel(context, current)
            LocationPreferences().clearAlarm(current.id)
            NotificationManagerCompat.from(context).cancel(current.id.toInt() + 8000)
            context.sendBroadcast(Intent(AlarmService.ALARM_INTENT_ACTION).setPackage(context.packageName)
                .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.DISMISS_ACTION).putExtra(AlarmHelper.EXTRA_ID, current.id))
        }
        app.locationTracker.updateRegistration()
    }
}
