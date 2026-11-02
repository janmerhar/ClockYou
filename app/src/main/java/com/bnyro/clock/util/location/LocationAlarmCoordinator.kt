package com.bnyro.clock.util.location

import android.content.BroadcastReceiver
import android.util.Log
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.services.AlarmService
import java.time.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object LocationAlarmCoordinator {
    private val mutex = Mutex()
    suspend fun <T> serialized(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun progressFor(alarm: Alarm, zone: ZoneId): LocationProgress {
        val storage = LocationPreferences()
        val progress = storage.getProgress(alarm.id)
        if (progress.lastTrigger == null && progress.skippedDate == null && alarm.dismissedAt != null) {
            val migrated = progress.copy(skippedDate = Instant.ofEpochMilli(alarm.dismissedAt!!).atZone(zone).toLocalDate().toEpochDay())
            storage.saveProgress(alarm.id, migrated)
            return migrated
        }
        return progress
    }

    suspend fun reconcile(context: Context, reason: ReconcileReason = ReconcileReason.AUTOMATIC, now: ZonedDateTime = ZonedDateTime.now()) {
        serialized { reconcileLocked(context, reason, now) }
        if (reason == ReconcileReason.RECOVERY) LocationPreferences().notifyQueueChanged()
        (context.applicationContext as App).locationTracker.updateRegistration()
    }

    internal suspend fun reconcileLocked(context: Context, reason: ReconcileReason, now: ZonedDateTime = ZonedDateTime.now()) {
        val app = context.applicationContext as App
        app.locationTracker.refresh()
        app.container.alarmRepository.getAlarms().forEach {
            if (it.awayTime != null || reason != ReconcileReason.AUTOMATIC) scheduleLocked(context, it, reason, now)
        }
    }

    internal suspend fun scheduleLocked(context: Context, alarm: Alarm, reason: ReconcileReason = ReconcileReason.AUTOMATIC, now: ZonedDateTime = ZonedDateTime.now()) {
        val app = context.applicationContext as App
        val storage = LocationPreferences()
        if (alarm.snoozedUntil != null) {
            if (reason == ReconcileReason.AUTOMATIC && alarm.snoozedUntil!! <= now.toInstant().toEpochMilli()) return
            AlarmHelper.cancel(context, alarm)
            AlarmHelper.schedule(context, alarm, alarm.snoozedUntil!!)
            return
        }
        if (!alarm.enabled || alarm.awayTime == null) {
            storage.clearOccurrence(alarm.id)
            val ordinary = if (alarm.enabled && AlarmHelper.hasRecurrenceEnded(alarm)) alarm.copy(enabled = false) else alarm
            if (ordinary.enabled != alarm.enabled) app.container.alarmRepository.updateAlarm(ordinary)
            AlarmHelper.enqueue(context, ordinary)
            return
        }
        val saved = storage.getOccurrence(alarm.id)
        var progress = progressFor(alarm, now.zone)
        if (reason == ReconcileReason.RECOVERY && saved != null && saved.triggerMillis(now.zone) <= now.toInstant().toEpochMilli()) {
            progress = progress.copy(consumedDate = maxOf(progress.consumedDate ?: Long.MIN_VALUE, saved.date.toEpochDay()))
            storage.saveProgress(alarm.id, progress)
        }
        val state = app.locationTracker.status.value.state
        val next = LocationAlarmScheduler.resolve(alarm, state, saved, now, reason, progress)
        // A due broadcast still owns its pending work; do not replace its extras or advance it.
        if (reason == ReconcileReason.AUTOMATIC && saved == next && saved != null && saved.triggerMillis(now.zone) <= now.toInstant().toEpochMilli()) return
        if (next != null && next.date > now.toLocalDate() &&
            AlarmHelper.occurrenceOnOrAfter(alarm, maxOf(now.toLocalDate(), LocalDate.ofEpochDay(alarm.startDate))) == now.toLocalDate() &&
            LocationAlarmPolicy.wallTime(now.toLocalDate(), LocationAlarmPolicy.chooseTime(alarm.time, alarm.awayTime, state), now.zone) <= now.toInstant()) {
            storage.saveProgress(alarm.id, progress.copy(consumedDate = maxOf(progress.consumedDate ?: Long.MIN_VALUE, now.toLocalDate().toEpochDay())))
        }
        AlarmHelper.cancel(context, alarm)
        if (next == null) {
            storage.clearOccurrence(alarm.id)
            if (alarm.endDate != null || alarm.endOccurrences != null) app.container.alarmRepository.updateAlarm(alarm.copy(enabled = false))
        } else {
            storage.saveOccurrence(alarm.id, next)
            AlarmHelper.schedule(context, alarm, next.triggerMillis(now.zone))
        }
    }

    suspend fun deliver(context: Context, id: Long, trigger: Long, now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        val delivered = serialized {
            val app = context.applicationContext as App
            val repository = app.container.alarmRepository
            val alarm = repository.getAlarmById(id) ?: return@serialized false
            val storage = LocationPreferences()
            val progress = progressFor(alarm, now.zone)
            if (progress.lastTrigger == trigger || trigger > now.toInstant().toEpochMilli()) return@serialized false
            val zone = ZoneId.systemDefault()
            val isSnooze = alarm.snoozedUntil != null
            val date = Instant.ofEpochMilli(trigger).atZone(zone).toLocalDate()
            if (isSnooze) {
                if (alarm.snoozedUntil != trigger) return@serialized false
            } else {
                if (!alarm.enabled || LocationAlarmScheduler.excluded(alarm, date, zone, progress)) return@serialized false
                if (alarm.awayTime != null) {
                    val saved = storage.getOccurrence(id) ?: return@serialized false
                    if (saved.configKey != LocationAlarmScheduler.configKey(alarm) || saved.triggerMillis(zone) != trigger) return@serialized false
                } else if (LocationAlarmPolicy.wallTime(date, alarm.time, zone).toEpochMilli() != trigger ||
                    AlarmHelper.occurrenceOnOrAfter(alarm, maxOf(date, LocalDate.ofEpochDay(alarm.startDate))) != date || !AlarmHelper.withinEnd(alarm, date)) return@serialized false
            }
            storage.saveProgress(id, progress.copy(
                consumedDate = if (isSnooze) progress.consumedDate else maxOf(progress.consumedDate ?: Long.MIN_VALUE, date.toEpochDay()),
                lastTrigger = trigger))
            val updated = alarm.copy(snoozedUntil = null, dismissedAt = if (isSnooze) alarm.dismissedAt else trigger)
            repository.updateAlarm(updated)
            if (!isSnooze) storage.clearOccurrence(id)
            app.locationTracker.refresh()
            scheduleLocked(context, updated, now = now)
            ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java)
                .putExtra(AlarmHelper.EXTRA_ID, id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, trigger))
            true
        }
        (context.applicationContext as App).locationTracker.updateRegistration()
        return delivered
    }

    suspend fun snooze(context: Context, id: Long, ringTime: Long, minutes: Int, now: ZonedDateTime = ZonedDateTime.now()): Long? = serialized {
        val repository = (context.applicationContext as App).container.alarmRepository
        val alarm = repository.getAlarmById(id) ?: return@serialized null
        if (!alarm.snoozeEnabled || LocationPreferences().getProgress(id).lastTrigger != ringTime || minutes <= 0) return@serialized null
        val wanted = (ringTime + minutes * 60_000L) / 60_000 * 60_000
        val trigger = if (wanted > now.toInstant().toEpochMilli()) wanted else (now.toInstant().toEpochMilli() / 60_000 + 1) * 60_000
        val snoozed = alarm.copy(snoozedUntil = trigger)
        repository.updateAlarm(snoozed)
        AlarmHelper.cancel(context, snoozed)
        AlarmHelper.schedule(context, snoozed, trigger)
        trigger
    }
}

internal fun BroadcastReceiver.alarmWork(context: Context, block: suspend () -> Unit) {
    val pending = goAsync()
    (context.applicationContext as App).applicationScope.launch {
        try { withTimeout(8_000) { block() } }
        catch (error: Exception) { Log.e("AlarmReceiver", "Alarm operation did not complete", error) }
        finally { pending.finish() }
    }
}
