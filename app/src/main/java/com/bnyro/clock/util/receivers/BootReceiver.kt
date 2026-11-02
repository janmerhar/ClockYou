package com.bnyro.clock.util.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bnyro.clock.App
import com.bnyro.clock.util.location.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        alarmWork(context) {
            App.updateGeneratedWidgetPreviews(context)
            LocationAlarmCoordinator.reconcile(context, ReconcileReason.RECOVERY)
        }
    }
}
