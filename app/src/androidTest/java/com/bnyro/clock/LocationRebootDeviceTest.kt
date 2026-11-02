package com.bnyro.clock

import android.app.AlarmManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.HomeLocation
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.location.LocationAlarmScheduler
import com.bnyro.clock.util.location.LocationPreferences
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationRebootDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as App
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun prepareForReboot() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rebootPhase") == "prepare")
        shell("appops set ${app.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        shell("pm grant ${app.packageName} android.permission.ACCESS_COARSE_LOCATION")
        shell("pm grant ${app.packageName} android.permission.ACCESS_FINE_LOCATION")
        shell("pm grant ${app.packageName} android.permission.ACCESS_BACKGROUND_LOCATION")
        shell("appops set com.android.shell android:mock_location allow")
        shell("cmd location providers add-test-provider network")
        try {
            shell("cmd location providers set-test-provider-enabled network true")
            shell("cmd location providers set-test-provider-location network --location 0.01,0 --accuracy 10")
            LocationPreferences().saveHome(HomeLocation(0.0, 0.0))
            val now = ZonedDateTime.now()
            val normal = now.plusMinutes(5).truncatedTo(ChronoUnit.MINUTES)
            val away = now.plusMinutes(20).truncatedTo(ChronoUnit.MINUTES)
            val alarm = CreateUpdateDeleteAlarmUseCase(app, app.container.alarmRepository).createAlarm(Alarm(
                time = normal.toLocalTime().toNanoOfDay() / 1_000_000,
                awayTime = away.toLocalTime().toNanoOfDay() / 1_000_000, enabled = true,
                startDate = away.toLocalDate().toEpochDay(), endOccurrences = 1, label = "Native POC reboot"))
            val expected = away.toInstant().toEpochMilli()
            assertEquals(expected, LocationAlarmScheduler.getCommittedTrigger(alarm))
            assertTrue(LocationPreferences().getOccurrence(alarm.id)!!.frozen)
            check(Preferences.instance.edit().putLong("location_runtime_reboot_id", alarm.id)
                .putLong("location_runtime_reboot_expected", expected).commit())
            println("REBOOT_EXPECTED id=${alarm.id} trigger=$expected")
        } finally { shell("cmd location providers remove-test-provider network") }
    }

    @Test fun verifyAfterReboot() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rebootPhase") == "verify")
        val id = Preferences.instance.getLong("location_runtime_reboot_id", -1)
        val expected = Preferences.instance.getLong("location_runtime_reboot_expected", -1)
        assertTrue(id >= 0 && expected > System.currentTimeMillis())
        val alarm = app.container.alarmRepository.getAlarmById(id)!!
        assertEquals(expected, LocationAlarmScheduler.getCommittedTrigger(alarm))
        assertEquals(expected, (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock.triggerTime)
        assertTrue(LocationPreferences().getOccurrence(id)!!.frozen)
        println("REBOOT_VERIFIED id=$id trigger=$expected")
        CreateUpdateDeleteAlarmUseCase(app, app.container.alarmRepository).deleteAlarm(alarm)
        LocationPreferences().clearHome()
        LocationPreferences().clearRuntimeState()
    }
}
