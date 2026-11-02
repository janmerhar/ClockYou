package com.bnyro.clock.util.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.location.LocationAlarmCoordinator
import com.bnyro.clock.util.location.alarmWork

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getLongExtra(AlarmHelper.EXTRA_ID, -1)?.takeIf { it != -1L } ?: return
        val trigger = intent.getLongExtra(AlarmHelper.EXTRA_TRIGGER_TIME, -1).takeIf { it >= 0 } ?: return
        alarmWork(context) { LocationAlarmCoordinator.deliver(context, id, trigger) }
    }
}
