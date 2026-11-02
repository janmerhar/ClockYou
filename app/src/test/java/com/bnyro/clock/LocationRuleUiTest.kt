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
