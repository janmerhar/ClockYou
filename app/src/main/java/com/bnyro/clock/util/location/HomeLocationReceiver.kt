package com.bnyro.clock.util.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import androidx.core.content.IntentCompat

class HomeLocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        alarmWork(context) {
            IntentCompat.getParcelableExtra(intent, LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
                ?.toSample()?.let { LocationPreferences().saveSample(it) }
            LocationAlarmCoordinator.reconcile(context)
        }
    }
}
