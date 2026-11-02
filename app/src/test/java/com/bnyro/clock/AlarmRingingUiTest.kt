package com.bnyro.clock

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.presentation.screens.alarm.AlarmActivity
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.location.LocationPreferences
import com.bnyro.clock.util.location.LocationProgress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w540dp-h960dp")
class AlarmRingingUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var controller: org.robolectric.android.controller.ActivityController<AlarmActivity>? = null
    private val app = ApplicationProvider.getApplicationContext<App>()
    private val original = app.container
    private val name = "ringing-test-${java.util.UUID.randomUUID()}"
    private lateinit var database: AppDatabase
    private val reached = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val gatedId = AtomicLong(-1)

    @Before fun setup() {
        // Robolectric's keyguard shadow requires a locked device for a null callback.
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        Preferences.instance.edit().clear().commit()
        database = Room.databaseBuilder(app, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback({ sql, args ->
                if (sql.trimStart().startsWith("SELECT", true) && sql.contains("WHERE id") &&
                    (args.firstOrNull() as? Number)?.toLong()?.let { gatedId.compareAndSet(it, -1) } == true) {
                    reached.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }, Executor { it.run() }).build()
        app.container = AppContainer(database)
    }

    @After fun cleanup() {
        release.countDown()
        controller?.pause()?.stop()?.destroy()
        app.container = original
        database.close()
        app.deleteDatabase(name)
    }

    private fun add(minutes: Int): Alarm = runBlocking {
        val definition = Alarm(time = 28_800_000, enabled = true, snoozeMinutes = minutes)
        definition.copy(id = database.alarmsDao().insert(definition))
    }
    private fun load(alarm: Alarm, trigger: Long) {
        val intent = Intent(app, AlarmActivity::class.java)
            .putExtra(AlarmHelper.EXTRA_ID, alarm.id).putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, trigger)
        if (controller == null) controller = org.robolectric.Robolectric.buildActivity(AlarmActivity::class.java, intent).setup()
        else controller!!.newIntent(intent)
    }

    @Test fun delayedLoadShowsConfiguredSnoozeAndSchedulesThatDuration() {
        val alarm = add(1)
        val trigger = System.currentTimeMillis() / 60_000 * 60_000
        LocationPreferences().saveProgress(alarm.id, LocationProgress(lastTrigger = trigger))
        gatedId.set(alarm.id)
        load(alarm, trigger)
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        compose.onAllNodesWithText("Snooze", substring = true).assertCountEquals(0)
        release.countDown()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Snooze 1 minutes").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Snooze 1 minutes").performClick()
        compose.waitUntil(5_000) { runBlocking { database.alarmsDao().findById(alarm.id)?.snoozedUntil != null } }
        assertEquals(trigger + 60_000, runBlocking { database.alarmsDao().findById(alarm.id) }!!.snoozedUntil)
    }

    @Test fun olderLoadCannotReplaceANewerRingingIntent() {
        val old = add(30)
        val current = add(1)
        val trigger = System.currentTimeMillis() / 60_000 * 60_000
        gatedId.set(old.id)
        load(old, trigger)
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        load(current, trigger + 1)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Snooze 1 minutes").fetchSemanticsNodes().isNotEmpty() }
        release.countDown()
        compose.waitForIdle()
        compose.onNodeWithText("Snooze 1 minutes").assertExists()
        compose.onAllNodesWithText("Snooze 30 minutes").assertCountEquals(0)
    }
}
