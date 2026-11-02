package com.bnyro.clock.util.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.IntentCompat
import com.bnyro.clock.App
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class HomeLocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        val pending = goAsync()
        app.applicationScope.launch {
            try {
                withTimeout(8_000) {
                    IntentCompat.getParcelableExtra(intent, LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
                        ?.toSample()?.let { LocationPreferences().saveSample(it) }
                    app.locationTracker.refresh()
                    app.locationTracker.updateRegistration()
                }
            } catch (error: Exception) {
                Log.w("HomeLocationReceiver", "Location refresh did not complete", error)
            } finally { pending.finish() }
        }
    }
}
