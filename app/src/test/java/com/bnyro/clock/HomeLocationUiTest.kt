package com.bnyro.clock

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.HomeLocation
import com.bnyro.clock.presentation.screens.settings.HomeLocationScreen
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.location.LocationPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeLocationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<App>()
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val storage get() = LocationPreferences()

    @Before fun clear() { Preferences.instance.edit().clear().commit() }
    private fun grant() { shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) }
    private fun fix(latitude: Double) {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitude; longitude = 14.0; accuracy = 20f; time = System.currentTimeMillis()
        })
    }

    @Test fun captureIsPreviewUntilSaveAndUpdateReplacesHome() {
        grant(); fix(46.0)
        compose.setContent { MaterialTheme { HomeLocationScreen {} } }
        compose.onNodeWithText("Use current location").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Save Home").fetchSemanticsNodes().isNotEmpty() }
        assertNull(storage.getHome())
        compose.onNodeWithText("Save Home").performScrollTo().performClick()
        compose.waitUntil(5_000) { storage.getHome() == HomeLocation(46.0, 14.0) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Use current location") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        fix(47.0)
        compose.onNodeWithText("Use current location").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Update Home").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Update Home").performScrollTo().performClick()
        compose.waitUntil(5_000) { storage.getHome() == HomeLocation(47.0, 14.0) }
    }

    @Test fun failedCaptureRetainsHomeAndRemoveRequiresConfirmation() {
        grant(); storage.saveHome(HomeLocation(46.0, 14.0))
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        compose.setContent { MaterialTheme { HomeLocationScreen {} } }
        compose.onNodeWithText("Use current location").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Could not get a recent, accurate location. Home was kept.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(HomeLocation(46.0, 14.0), storage.getHome())
        compose.onNodeWithText("Remove Home").performScrollTo().performClick()
        assertNotNull(storage.getHome())
        compose.onNodeWithText("Cancel").performClick()
        assertNotNull(storage.getHome())
        compose.onNodeWithText("Remove Home").performScrollTo().performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.waitUntil(5_000) { storage.getHome() == null }
    }

    @Test fun permissionDenialShowsUnknownWithoutChangingHome() {
        storage.saveHome(HomeLocation(46.0, 14.0))
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        compose.setContent { MaterialTheme { HomeLocationScreen {} } }
        compose.onNodeWithText("Status: Unknown").assertExists()
        assertEquals(HomeLocation(46.0, 14.0), storage.getHome())
    }
}
