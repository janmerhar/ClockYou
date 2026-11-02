package com.bnyro.clock

import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.HomeLocation
import com.bnyro.clock.domain.model.LocationSample
import com.bnyro.clock.domain.usecase.ClockBackupUseCase
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.location.LocationPreferences
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationStorageTest {
    private val context = ApplicationProvider.getApplicationContext<App>()
    private val prefs get() = Preferences.instance
    private fun storage() = LocationPreferences(prefs) { 4_000L }

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun oldBackupDefaultsToRuleOff() {
        val alarm = Json.decodeFromString<Alarm>("""{"time":28800000,"label":"Old alarm"}""")
        assertNull(alarm.awayTime)
        assertEquals(28_800_000L, alarm.time)
        assertEquals(36_000_000L, Json.decodeFromString<Alarm>(Json.encodeToString(alarm.copy(awayTime = 36_000_000L))).awayTime)
    }

    @Test fun homeCreateUpdateRemove() {
        assertNull(storage().getHome())
        storage().saveHome(HomeLocation(46.05, 14.51))
        assertEquals(HomeLocation(46.05, 14.51), storage().getHome())
        storage().saveHome(HomeLocation(0.0, 0.0))
        assertEquals(HomeLocation(0.0, 0.0), storage().getHome())
        storage().clearHome()
        assertNull(storage().getHome())
    }

    @Test fun concurrentWritersKeepNewestSample() {
        repeat(30) {
            val start = CountDownLatch(1)
            val writers = listOf(2_000L, 3_000L).map { timestamp ->
                thread { start.await(); storage().saveSample(LocationSample(0.0, 0.0, 10f, timestamp)) }
            }
            start.countDown()
            writers.forEach { it.join(5_000); assertFalse(it.isAlive) }
            assertEquals(3_000L, storage().getSample()?.timestampMillis)
        }
    }

    @Test fun invalidAndFutureSamplesCannotReplaceGoodFix() {
        val good = LocationSample(0.0, 0.0, 10f, 3_000)
        storage().saveSample(good)
        listOf(good.copy(latitude = 91.0), good.copy(accuracyMeters = Float.NaN), good.copy(timestampMillis = 4_001)).forEach(storage()::saveSample)
        assertEquals(good, storage().getSample())
        prefs.edit().putString("location_runtime_sample", "broken").commit()
        assertNull(storage().getSample())
    }

    @Test fun backupIncludesHomeButExcludesAndClearsRuntime() = runBlocking {
        storage().saveHome(HomeLocation(46.0, 14.0))
        storage().saveSample(LocationSample(0.0, 0.0, 10f, 3_000))
        prefs.edit().putString("location_runtime_occurrence_1", "temporary").commit()
        val useCase = ClockBackupUseCase(context)
        val backup = useCase.capture(emptyList())
        assertTrue(backup.preferences!!.containsKey("location_home"))
        assertFalse(backup.preferences.keys.any { it.startsWith("location_runtime_") })
        storage().clearHome()
        useCase.restore(backup)
        assertEquals(HomeLocation(46.0, 14.0), storage().getHome())
        assertNull(storage().getSample())
        assertFalse(prefs.contains("location_runtime_occurrence_1"))
        storage().saveSample(LocationSample(0.0, 0.0, 10f, 3_000))
        useCase.restore(backup.copy(preferences = null))
        assertNull(storage().getSample())
    }
}
