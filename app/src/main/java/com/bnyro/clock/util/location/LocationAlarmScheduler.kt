package com.bnyro.clock.util.location

import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.util.AlarmHelper
import java.time.*
import kotlinx.serialization.Serializable

enum class ReconcileReason { AUTOMATIC, USER_EDIT, RECOVERY }
data class LocationOccurrence(val date: LocalDate, val timeOfDay: Long, val configKey: String,
    val freezeAtMillis: Long? = null, val frozen: Boolean = false)
fun LocationOccurrence.triggerMillis(zone: ZoneId): Long = LocationAlarmPolicy.wallTime(date, timeOfDay, zone).toEpochMilli()

@Serializable
data class LocationProgress(val consumedDate: Long? = null, val skippedDate: Long? = null, val lastTrigger: Long? = null)

object LocationAlarmScheduler {
    fun configKey(alarm: Alarm): String = listOf(alarm.time, alarm.awayTime, alarm.days.joinToString(","),
        alarm.startDate, alarm.repeatInterval, alarm.repeatUnit, alarm.repeatAnchor, alarm.repeatDuration,
        alarm.repeatDurationUnit, alarm.endDate, alarm.endOccurrences).joinToString("|")

    fun getCommittedTrigger(alarm: Alarm): Long? {
        alarm.snoozedUntil?.let { return it }
        if (alarm.awayTime == null) return null
        return LocationPreferences().getOccurrence(alarm.id)?.takeIf { it.configKey == configKey(alarm) }
            ?.triggerMillis(ZoneId.systemDefault())
    }

    fun excluded(alarm: Alarm, date: LocalDate, zone: ZoneId, progress: LocationProgress): Boolean =
        progress.consumedDate?.let { date.toEpochDay() <= it } == true || date.toEpochDay() == progress.skippedDate ||
            (progress.consumedDate == null && progress.skippedDate == null &&
                alarm.dismissedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == date } == true)

    fun resolve(alarm: Alarm, state: LocationState, saved: LocationOccurrence?, now: ZonedDateTime,
        reason: ReconcileReason, progress: LocationProgress = LocationProgress()): LocationOccurrence? {
        if (!alarm.enabled || alarm.repeatInterval <= 0) return null
        val key = configKey(alarm)
        val zone = now.zone
        if (saved != null && saved.configKey == key && !excluded(alarm, saved.date, zone, progress) &&
            AlarmHelper.occurrenceOnOrAfter(alarm, maxOf(saved.date, LocalDate.ofEpochDay(alarm.startDate))) == saved.date &&
            AlarmHelper.withinEnd(alarm, saved.date)) {
            val trigger = saved.triggerMillis(zone)
            if (reason == ReconcileReason.AUTOMATIC && trigger <= now.toInstant().toEpochMilli()) return saved
            val cutoff = LocationAlarmPolicy.cutoff(saved.date, alarm.time, alarm.awayTime ?: alarm.time, zone).toEpochMilli()
            val originalCutoff = saved.freezeAtMillis ?: cutoff
            if (reason != ReconcileReason.USER_EDIT && trigger > now.toInstant().toEpochMilli() &&
                (saved.frozen || now.toInstant().toEpochMilli() >= minOf(originalCutoff, cutoff))) {
                return saved.copy(freezeAtMillis = originalCutoff, frozen = true)
            }
        }
        val selected = LocationAlarmPolicy.chooseTime(alarm.time, alarm.awayTime, state)
        if (selected !in 0 until 86_400_000L) return null
        var from = maxOf(now.toLocalDate(), LocalDate.ofEpochDay(alarm.startDate))
        if (reason == ReconcileReason.RECOVERY && saved != null && saved.triggerMillis(zone) <= now.toInstant().toEpochMilli()) {
            from = maxOf(from, saved.date.plusDays(1))
        }
        while (true) {
            val date = AlarmHelper.occurrenceOnOrAfter(alarm, from) ?: return null
            if (!AlarmHelper.withinEnd(alarm, date)) return null
            val cutoff = LocationAlarmPolicy.cutoff(date, alarm.time, alarm.awayTime ?: alarm.time, zone).toEpochMilli()
            val candidate = LocationOccurrence(date, selected, key, cutoff, now.toInstant().toEpochMilli() >= cutoff)
            if (!excluded(alarm, date, zone, progress) && candidate.triggerMillis(zone) > now.toInstant().toEpochMilli()) return candidate
            from = date.plusDays(1)
        }
    }
}
