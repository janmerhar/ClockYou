package com.bnyro.clock

import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.util.location.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class LocationAlarmSchedulingTest {
    private val date = LocalDate.of(2026, 11, 2)
    private val alarm = Alarm(id = 1, time = 28_800_000, awayTime = 36_000_000, enabled = true, startDate = date.toEpochDay())
    private fun now(hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(ZoneId.of("UTC"))
    private fun saved(time: Long = 36_000_000) = LocationOccurrence(date, time, LocationAlarmScheduler.configKey(alarm))

    @Test fun selectHomeAwayUnknownAndOff() {
        for ((state, expected) in listOf(LocationState.HOME to 28_800_000L, LocationState.AWAY to 36_000_000L, LocationState.UNKNOWN to 28_800_000L)) {
            val next = LocationAlarmScheduler.resolve(alarm, state, null, now(7), ReconcileReason.USER_EDIT)!!
            assertEquals(date, next.date); assertEquals(expected, next.timeOfDay)
        }
        assertEquals(28_800_000L, LocationAlarmScheduler.resolve(alarm.copy(awayTime = null), LocationState.AWAY, null, now(7), ReconcileReason.USER_EDIT)!!.timeOfDay)
        assertEquals(28_800_000L, alarm.time)
    }

    @Test fun freezeAtEarlierTimeCutoff() {
        assertEquals(28_800_000L, LocationAlarmScheduler.resolve(alarm, LocationState.HOME, saved(), now(7), ReconcileReason.AUTOMATIC)!!.timeOfDay)
        assertEquals(36_000_000L, LocationAlarmScheduler.resolve(alarm, LocationState.HOME, saved(), now(7, 45), ReconcileReason.AUTOMATIC)!!.timeOfDay)
        val earlier = alarm.copy(awayTime = 21_600_000)
        val commit = LocationOccurrence(date, 21_600_000, LocationAlarmScheduler.configKey(earlier))
        assertEquals(21_600_000L, LocationAlarmScheduler.resolve(earlier, LocationState.HOME, commit, now(5, 30), ReconcileReason.AUTOMATIC)!!.timeOfDay)
    }

    @Test fun frozenAwaySurvivesEastwardTimezoneChange() {
        val moved = now(7, 45).withZoneSameInstant(ZoneId.of("Europe/Paris"))
        val commit = LocationAlarmScheduler.resolve(alarm, LocationState.UNKNOWN, saved(), moved, ReconcileReason.RECOVERY)!!
        assertEquals(date, commit.date); assertEquals(36_000_000L, commit.timeOfDay)
        assertEquals(Instant.parse("2026-11-02T09:00:00Z").toEpochMilli(), commit.triggerMillis(moved.zone))
    }

    @Test fun frozenAwaySurvivesWestwardTimezoneChangeWithoutAnInterveningCallback() {
        val initial = LocationAlarmScheduler.resolve(alarm, LocationState.AWAY, null, now(7), ReconcileReason.USER_EDIT)!!
        val moved = now(7, 45).withZoneSameInstant(ZoneId.of("Atlantic/Azores"))
        val next = LocationAlarmScheduler.resolve(alarm, LocationState.HOME, initial, moved, ReconcileReason.RECOVERY)!!
        assertEquals(date, next.date)
        assertEquals(36_000_000L, next.timeOfDay)
    }

    @Test fun frozenChoiceSurvivesClockRollback() {
        val frozen = LocationAlarmScheduler.resolve(alarm, LocationState.AWAY, null, now(7, 45), ReconcileReason.USER_EDIT)!!
        val next = LocationAlarmScheduler.resolve(alarm, LocationState.HOME, frozen, now(7), ReconcileReason.RECOVERY)!!
        assertEquals(36_000_000L, next.timeOfDay)
    }

    @Test fun dueCommitIsKeptForAutomaticDeliveryButRecoveryAdvances() {
        assertEquals(saved(), LocationAlarmScheduler.resolve(alarm, LocationState.UNKNOWN, saved(), now(10, 1), ReconcileReason.AUTOMATIC))
        val next = LocationAlarmScheduler.resolve(alarm, LocationState.UNKNOWN, saved(), now(10, 1), ReconcileReason.RECOVERY)!!
        assertEquals(date.plusDays(1), next.date)
    }

    @Test fun consumedAndSkippedDatesAreExcludedOnEveryCandidate() {
        val progress = LocationProgress(consumedDate = date.toEpochDay(), skippedDate = date.plusDays(1).toEpochDay())
        val next = LocationAlarmScheduler.resolve(alarm, LocationState.AWAY, null, now(7), ReconcileReason.USER_EDIT, progress)!!
        assertEquals(date.plusDays(2), next.date)
        val once = alarm.copy(endOccurrences = 1)
        assertNull(LocationAlarmScheduler.resolve(once, LocationState.AWAY, null, now(7), ReconcileReason.USER_EDIT, progress))
    }
}
