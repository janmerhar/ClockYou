package com.bnyro.clock.util.location

import com.bnyro.clock.domain.model.HomeLocation
import com.bnyro.clock.domain.model.LocationSample
import java.time.*
import kotlin.math.*

enum class LocationState { HOME, AWAY, UNKNOWN }

object LocationAlarmPolicy {
    fun classify(home: HomeLocation?, sample: LocationSample?, nowMillis: Long): LocationState {
        if (home == null || sample == null || !LocationPreferences.validCoordinates(home.latitude, home.longitude) ||
            !LocationPreferences.validSample(sample, nowMillis)) return LocationState.UNKNOWN
        val lat1 = Math.toRadians(home.latitude)
        val lat2 = Math.toRadians(sample.latitude)
        val a = (sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) * sin(Math.toRadians(sample.longitude - home.longitude) / 2).pow(2)).coerceIn(0.0, 1.0)
        val distance = 6_371_000 * 2 * atan2(sqrt(a), sqrt(1 - a))
        return when {
            distance + sample.accuracyMeters <= 300 -> LocationState.HOME
            distance - sample.accuracyMeters > 300 -> LocationState.AWAY
            else -> LocationState.UNKNOWN
        }
    }

    fun chooseTime(normalTime: Long, awayTime: Long?, state: LocationState): Long =
        if (state == LocationState.AWAY && awayTime != null) awayTime else normalTime

    fun cutoff(date: LocalDate, normalTime: Long, awayTime: Long, zone: ZoneId): Instant =
        minOf(wallTime(date, normalTime, zone), wallTime(date, awayTime, zone)).minusSeconds(30 * 60)

    fun wallTime(date: LocalDate, timeOfDay: Long, zone: ZoneId): Instant =
        date.atTime(LocalTime.ofNanoOfDay(timeOfDay * 1_000_000)).atZone(zone).toInstant()
}
