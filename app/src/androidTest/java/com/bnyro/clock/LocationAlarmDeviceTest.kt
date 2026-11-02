package com.bnyro.clock

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.util.location.LocationAlarmScheduler
import com.bnyro.clock.util.location.LocationPreferences
import com.bnyro.clock.util.services.AlarmService
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationAlarmDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as App
    private val repository get() = app.container.alarmRepository
    private val useCase get() = CreateUpdateDeleteAlarmUseCase(app, repository)
    private val created = mutableListOf<Alarm>()

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Before fun permissions() {
        assertEquals("com.bnyro.clock.debug", app.packageName)
        shell("appops set ${app.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        shell("dumpsys deviceidle whitelist +${app.packageName}")
    }

    @After fun cleanup() = runBlocking {
        created.forEach { useCase.deleteAlarm(it) }
        app.stopService(Intent(app, AlarmService::class.java))
        Unit
    }

    private fun await(description: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < end) { "Timed out: $description" }
            Thread.sleep(100)
        }
    }

    private fun playerActive(): Boolean {
        val clients = shell("dumpsys media.player").substringBefore("Decoder infos by media types:")
        return clients.contains(app.packageName) && clients.contains("looping(true)")
    }

    private fun clickAlert(text: String) {
        await("ringing control $text") {
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.contains(text, true) == true || node.contentDescription?.contains(text, true) == true) return node
                for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                return null
            }
            val node = find(instrumentation.uiAutomation.rootInActiveWindow) ?: return@await false
            var target = node
            while (!target.isClickable) target = target.parent ?: return@await false
            target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private suspend fun schedule(away: Boolean): Pair<Alarm, Long> {
        val now = ZonedDateTime.now()
        val target = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(if (now.second > 45) 2 else 1)
        val time = target.toLocalTime().toNanoOfDay() / 1_000_000
        val alarm = useCase.createAlarm(Alarm(time = time, awayTime = if (away) (time + 3_600_000) % 86_400_000 else null,
            label = "Native POC ${if (away) "location" else "ordinary"}", enabled = true, startDate = target.toLocalDate().toEpochDay(),
            endOccurrences = 1, snoozeMinutes = 1))
        created += alarm
        val trigger = target.toInstant().toEpochMilli()
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertEquals(trigger, manager.nextAlarmClock.triggerTime)
        if (away) assertEquals(trigger, LocationAlarmScheduler.getCommittedTrigger(alarm))
        return alarm to trigger
    }

    @Test fun ordinaryAlarmRingsSnoozesAndDismisses() = runBlocking {
        val (alarm, trigger) = schedule(false)
        await("ordinary alarm delivery", 90_000) { LocationPreferences().getProgress(alarm.id).lastTrigger == trigger && playerActive() }
        clickAlert("Snooze")
        await("persisted snooze") { runBlocking { repository.getAlarmById(alarm.id)?.snoozedUntil != null } }
        val snooze = repository.getAlarmById(alarm.id)!!.snoozedUntil!!
        assertEquals(trigger + 60_000, snooze)
        await("snoozed alarm delivery", 90_000) { LocationPreferences().getProgress(alarm.id).lastTrigger == snooze && playerActive() }
        clickAlert("Dismiss")
        await("dismissed player") { !playerActive() }
        assertNull(repository.getAlarmById(alarm.id)!!.snoozedUntil)
    }

    @Test fun locationRuleWithUnknownLocationStillRings() = runBlocking {
        LocationPreferences().clearHome()
        val (alarm, trigger) = schedule(true)
        await("location alarm delivery", 90_000) { LocationPreferences().getProgress(alarm.id).lastTrigger == trigger && playerActive() }
        clickAlert("Dismiss")
        await("dismissed location player") { !playerActive() }
        assertFalse(repository.getAlarmById(alarm.id)!!.enabled)
    }
}
