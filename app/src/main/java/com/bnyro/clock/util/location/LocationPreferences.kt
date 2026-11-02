package com.bnyro.clock.util.location

import android.content.SharedPreferences
import com.bnyro.clock.domain.model.HomeLocation
import com.bnyro.clock.domain.model.LocationSample
import com.bnyro.clock.util.Preferences
import kotlinx.serialization.json.Json

class LocationPreferences(private val preferences: SharedPreferences = Preferences.instance, private val nowMillis: () -> Long = System::currentTimeMillis) {
    fun getHome(): HomeLocation? = read<HomeLocation>(HOME)?.takeIf { validCoordinates(it.latitude, it.longitude) }

    fun saveHome(home: HomeLocation) {
        require(validCoordinates(home.latitude, home.longitude))
        synchronized(lock) { check(preferences.edit().putString(HOME, Json.encodeToString(home)).commit()) }
    }

    fun clearHome() {
        synchronized(lock) { check(preferences.edit().remove(HOME).commit()) }
    }

    fun getSample(): LocationSample? = read<LocationSample>(SAMPLE)?.takeIf { validSample(it, nowMillis()) }

    fun saveSample(sample: LocationSample) {
        synchronized(lock) {
            if (!validSample(sample, nowMillis())) return
            if ((getSample()?.timestampMillis ?: Long.MIN_VALUE) > sample.timestampMillis) return
            check(preferences.edit().putString(SAMPLE, Json.encodeToString(sample)).commit())
        }
    }

    fun clearRuntimeState() {
        synchronized(lock) {
            val editor = preferences.edit()
            preferences.all.keys.filter(::isRuntimeKey).forEach(editor::remove)
            check(editor.commit())
        }
    }

    private inline fun <reified T> read(key: String): T? = runCatching {
        preferences.getString(key, null)?.let { Json.decodeFromString<T>(it) }
    }.getOrNull()

    companion object {
        private val lock = Any()
        private const val HOME = "location_home"
        private const val SAMPLE = "location_runtime_sample"
        const val MAX_SAMPLE_AGE_MILLIS = 43_200_000L

        fun isRuntimeKey(key: String) = key.startsWith("location_runtime_")

        fun validCoordinates(latitude: Double, longitude: Double) =
            latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

        fun validSample(sample: LocationSample, nowMillis: Long) =
            validCoordinates(sample.latitude, sample.longitude) &&
                sample.accuracyMeters.isFinite() && sample.accuracyMeters >= 0f &&
                sample.timestampMillis >= 0 && sample.timestampMillis <= nowMillis &&
                nowMillis - sample.timestampMillis <= MAX_SAMPLE_AGE_MILLIS
    }
}
