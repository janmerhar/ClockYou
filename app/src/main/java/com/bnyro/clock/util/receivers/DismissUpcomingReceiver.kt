package com.bnyro.clock.util.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bnyro.clock.App
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.location.alarmWork

class DismissUpcomingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getLongExtra(AlarmHelper.EXTRA_ID, -1)?.takeIf { it != -1L } ?: return
        alarmWork(context) {
            val repository = (context.applicationContext as App).container.alarmRepository
            val alarm = repository.getAlarmById(id) ?: return@alarmWork
            CreateUpdateDeleteAlarmUseCase(context, repository).dismissUpcomingAlarm(alarm)
        }
    }
}
