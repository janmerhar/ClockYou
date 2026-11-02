package com.bnyro.clock

import com.bnyro.clock.domain.model.HomeLocation
import com.bnyro.clock.domain.model.LocationSample
import com.bnyro.clock.util.location.LocationAlarmPolicy
import com.bnyro.clock.util.location.LocationState
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class LocationAlarmPolicyTest {
    private val now = 50_000_000L
    private val home = HomeLocation(0.0, 0.0)
    private val fix = LocationSample(0.0, 0.0, 10f, now)

    @Test fun accuracyCircleMustFitEntirelyInsideOrOutsideHome() {
        assertEquals(LocationState.HOME, LocationAlarmPolicy.classify(home, fix, now))
        assertEquals(LocationState.AWAY, LocationAlarmPolicy.classify(home, fix.copy(latitude = 0.01), now))
        assertEquals(LocationState.UNKNOWN, LocationAlarmPolicy.classify(home, fix.copy(accuracyMeters = 350f), now))
    }

    @Test fun invalidAbsentFutureAndStaleFixesAreUnknown() {
        assertEquals(LocationState.UNKNOWN, LocationAlarmPolicy.classify(null, fix, now))
        assertEquals(LocationState.UNKNOWN, LocationAlarmPolicy.classify(home, null, now))
        listOf(fix.copy(latitude = 91.0), fix.copy(longitude = Double.NaN), fix.copy(accuracyMeters = -1f),
            fix.copy(timestampMillis = now + 1), fix.copy(timestampMillis = now - 43_200_001)).forEach {
            assertEquals(LocationState.UNKNOWN, LocationAlarmPolicy.classify(home, it, now))
        }
        assertEquals(LocationState.HOME, LocationAlarmPolicy.classify(home, fix.copy(timestampMillis = now - 43_200_000), now))
    }

    @Test fun selectHomeAwayUnknownAndOff() {
        assertEquals(28_800_000L, LocationAlarmPolicy.chooseTime(28_800_000, 36_000_000, LocationState.HOME))
        assertEquals(36_000_000L, LocationAlarmPolicy.chooseTime(28_800_000, 36_000_000, LocationState.AWAY))
        assertEquals(28_800_000L, LocationAlarmPolicy.chooseTime(28_800_000, 36_000_000, LocationState.UNKNOWN))
        assertEquals(28_800_000L, LocationAlarmPolicy.chooseTime(28_800_000, null, LocationState.AWAY))
    }

    @Test fun cutoffAcrossMidnightAndDst() {
        assertEquals(Instant.parse("2026-11-01T23:40:00Z"), LocationAlarmPolicy.cutoff(LocalDate.of(2026, 11, 2), 600_000, 7_200_000, ZoneId.of("UTC")))
        assertEquals(Instant.parse("2027-03-28T00:40:00Z"), LocationAlarmPolicy.cutoff(LocalDate.of(2027, 3, 28), 11_400_000, 15_000_000, ZoneId.of("Europe/Berlin")))
    }
}
