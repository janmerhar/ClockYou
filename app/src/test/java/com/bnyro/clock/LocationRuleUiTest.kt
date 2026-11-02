package com.bnyro.clock

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.presentation.screens.alarmpicker.components.AlarmPicker
import com.bnyro.clock.presentation.screens.alarmpicker.components.LocationRuleSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationRuleUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun timezoneRecoveryRefreshesCommittedCountdown() {
        val oldZone = java.util.TimeZone.getDefault()
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<App>()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            android.provider.Settings.System.putString(app.contentResolver, android.provider.Settings.System.TIME_12_24, "24")
            val now = java.time.ZonedDateTime.parse("2026-11-02T07:45:00Z")
            val definition = Alarm(time = 28_800_000, awayTime = 36_000_000, enabled = true, startDate = now.toLocalDate().toEpochDay())
            val alarm = kotlinx.coroutines.runBlocking { definition.copy(id = app.container.alarmRepository.addAlarm(definition)) }
            com.bnyro.clock.util.location.LocationPreferences().saveOccurrence(alarm.id,
                com.bnyro.clock.util.location.LocationOccurrence(now.toLocalDate(), 36_000_000,
                    com.bnyro.clock.util.location.LocationAlarmScheduler.configKey(alarm)))
            compose.setContent { MaterialTheme {
                com.bnyro.clock.presentation.screens.alarm.components.AlarmCard(alarm, {}, true, {}, false, {}, now.toInstant().toEpochMilli())
            } }
            compose.onNodeWithText("In 2 hours 15 minutes").assertExists()
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Paris"))
            kotlinx.coroutines.runBlocking {
                com.bnyro.clock.util.location.LocationAlarmCoordinator.reconcile(app,
                    com.bnyro.clock.util.location.ReconcileReason.RECOVERY, now.withZoneSameInstant(java.time.ZoneId.of("Europe/Paris")))
            }
            compose.onNodeWithText("In 1 hour 15 minutes").assertExists()
            compose.onNodeWithText("10:00").assertExists()
        } finally {
            java.util.TimeZone.setDefault(oldZone)
            android.provider.Settings.System.putString(app.contentResolver, android.provider.Settings.System.TIME_12_24, null)
        }
    }

    @Test fun enableSuggestsTenAndDoneOnlyUpdatesParentDraft() {
        var result: Long? = null
        compose.setContent { MaterialTheme { LocationRuleSheet(null, { result = it }, {}) } }
        compose.onNodeWithContentDescription("Enable location rule").performClick()
        assertNull(result)
        compose.onNodeWithText("Done").performScrollTo().performClick()
        assertEquals(36_000_000L, result)
    }

    @Test fun cancelDiscardsSheetDraft() {
        var result: Long? = 7_200_000L
        var cancelled = false
        compose.setContent { MaterialTheme { LocationRuleSheet(result, { result = it }, { cancelled = true }) } }
        compose.onNodeWithContentDescription("Enable location rule").performClick()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertTrue(cancelled)
        assertEquals(7_200_000L, result)
    }

    @Test fun outsideDismissDiscardsDraft() {
        var cancelled = false
        compose.setContent { MaterialTheme { LocationRuleSheet(null, { fail("Dismiss must not apply") }, { cancelled = true }) } }
        compose.onNode(isRoot() and hasAnyDescendant(hasText("Location rule"))).performTouchInput { click(Offset(2f, 2f)) }
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun ruleDraftSurvivesStateRestoration() {
        var result: Long? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { LocationRuleSheet(null, { result = it }, {}) } }
        compose.onNodeWithContentDescription("Enable location rule").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Enable location rule").assertIsOn()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        assertEquals(36_000_000L, result)
    }

    @Test fun parentCancelSavesNothingAndNewFieldSurvivesRotation() {
        var saved: Alarm? = null
        var cancelled = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { AlarmPicker(Alarm(time = 28_800_000), false, { saved = it }, onCancel = { cancelled = true }) } }
        compose.onNodeWithText("Location rule: Off").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Enable location rule").performClick()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Away:", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)
        assertNull(saved)
    }

    @Test fun parentSaveIncludesAwayTimeAndOffClearsIt() {
        var saved: Alarm? = null
        compose.setContent { MaterialTheme { AlarmPicker(Alarm(time = 28_800_000, awayTime = 36_000_000), false, { saved = it }, onCancel = {}) } }
        compose.onNodeWithText("Away:", substring = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Enable location rule").performClick()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        assertNotNull(saved)
        assertNull(saved!!.awayTime)
        assertEquals(28_800_000L, saved!!.time)
    }
}
