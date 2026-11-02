package com.bnyro.clock.util.location

import android.content.SharedPreferences
import com.bnyro.clock.domain.model.HomeLocation
import com.bnyro.clock.domain.model.LocationSample
import com.bnyro.clock.util.Preferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

class LocationPreferences(private val preferences: SharedPreferences = Preferences.instance, private val nowMillis: () -> Long = System::currentTimeMillis) {
    fun getOccurrence(alarmId: Long): LocationOccurrence? = runCatching {
        read<StoredOccurrence>("location_runtime_occurrence_$alarmId")?.let {
            if (it.timeOfDay !in 0 until 86_400_000L) return@let null
            LocationOccurrence(LocalDate.ofEpochDay(it.date), it.timeOfDay, it.configKey, it.freezeAtMillis, it.frozen)
        }
    }.getOrNull()

    fun saveOccurrence(alarmId: Long, occurrence: LocationOccurrence) = write("location_runtime_occurrence_$alarmId",
        Json.encodeToString(StoredOccurrence(occurrence.date.toEpochDay(), occurrence.timeOfDay, occurrence.configKey, occurrence.freezeAtMillis, occurrence.frozen)))

    fun clearOccurrence(alarmId: Long) = write("location_runtime_occurrence_$alarmId", null)
    fun getProgress(alarmId: Long): LocationProgress = read<LocationProgress>("location_runtime_progress_$alarmId") ?: LocationProgress()
    fun saveProgress(alarmId: Long, progress: LocationProgress) = write("location_runtime_progress_$alarmId", Json.encodeToString(progress))
    fun clearAlarm(alarmId: Long) {
        clearOccurrence(alarmId)
        write("location_runtime_progress_$alarmId", null)
    }

    internal fun notifyQueueChanged() {
        synchronized(lock) { mutableRevision.value++ }
    }

    private fun write(key: String, value: String?) {
        synchronized(lock) {
            if (preferences.getString(key, null) == value) return
            check(preferences.edit().putString(key, value).commit())
            mutableRevision.value++
        }
    }

    @Serializable
    private data class StoredOccurrence(val date: Long, val timeOfDay: Long, val configKey: String,
        val freezeAtMillis: Long? = null, val frozen: Boolean = false)

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
            mutableRevision.value++
        }
    }

    private inline fun <reified T> read(key: String): T? = runCatching {
        preferences.getString(key, null)?.let { Json.decodeFromString<T>(it) }
    }.getOrNull()

    companion object {
        private val lock = Any()
        private val mutableRevision = MutableStateFlow(0L)
        val revision = mutableRevision.asStateFlow()
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
