package com.bnyro.clock.presentation.screens.alarm

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.presentation.screens.ringing.RingingActivity
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.AlarmService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.time.Instant
import java.time.ZoneId
import com.bnyro.clock.util.location.LocationAlarmCoordinator

class AlarmActivity : RingingActivity() {
    private var alarm by mutableStateOf<Alarm?>(null)
    private var ringTime = 0L
    private var loadJob: Job? = null

    override val closeAction = ALARM_ALERT_CLOSE_ACTION

    override val volumeButtonActionKey = Preferences.volumeButtonActionKey

    override val snoozeAvailable get() = alarm?.snoozeEnabled == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            alarm?.let { loaded -> key(loaded.id, ringTime) { AlarmAlertScreen(
                onDismiss = this@AlarmActivity::dismiss,
                onSnooze = this@AlarmActivity::snooze,
                label = loaded.label,
                snoozeEnabled = loaded.snoozeEnabled,
                snoozeTime = loaded.snoozeMinutes,
                alarmTimeMillis = loaded.time
            ) } }
        }

        handleIntent(intent)
    }
    override fun dismiss() {
        sendBroadcast(actionIntent(AlarmService.DISMISS_ACTION))
        this@AlarmActivity.finish()
    }

    override fun snooze() { alarm?.let { snooze(it.snoozeMinutes) } }

    private fun snooze(minutes: Int) {
        val id = alarm?.id ?: return
        val trigger = ringTime
        (application as App).applicationScope.launch {
            LocationAlarmCoordinator.snooze(this@AlarmActivity, id, trigger, minutes)
            sendBroadcast(Intent(AlarmService.ALARM_INTENT_ACTION).setPackage(packageName)
                .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.DISMISS_ACTION).putExtra(AlarmHelper.EXTRA_ID, id)
                .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, trigger))
        }
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        setIntent(intent)
        handleIntent(intent)
        super.onNewIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        loadJob?.cancel()
        alarm = null
        val id = intent.getLongExtra(AlarmHelper.EXTRA_ID, -1).takeIf { it != -1L } ?: return
        ringTime = intent.getLongExtra(AlarmHelper.EXTRA_TRIGGER_TIME, System.currentTimeMillis())
        val trigger = ringTime
        val alarmRepository = (application as App).container.alarmRepository
        loadJob = lifecycleScope.launch {
            val stored = alarmRepository.getAlarmById(id) ?: run { finish(); return@launch }
            alarm = stored.copy(time = Instant.ofEpochMilli(trigger).atZone(ZoneId.systemDefault()).toLocalTime().toNanoOfDay() / 1_000_000)
        }
    }

    private fun actionIntent(action: String) = Intent(AlarmService.ALARM_INTENT_ACTION)
        .setPackage(packageName).putExtra(AlarmService.ACTION_EXTRA_KEY, action)
        .putExtra(AlarmHelper.EXTRA_ID, intent.getLongExtra(AlarmHelper.EXTRA_ID, -1))
        .putExtra(AlarmHelper.EXTRA_TRIGGER_TIME, ringTime)

    override fun onStart() {
        super.onStart()
        sendBroadcast(
            Intent(AlarmService.ALARM_INTENT_ACTION)
                .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.ALERT_SHOWN_ACTION)
                .setPackage(packageName)
        )
    }

    override fun onStop() {
        sendBroadcast(
            Intent(AlarmService.ALARM_INTENT_ACTION)
                .putExtra(AlarmService.ACTION_EXTRA_KEY, AlarmService.ALERT_HIDDEN_ACTION)
                .setPackage(packageName)
        )
        super.onStop()
    }

    companion object {
        const val ALARM_ALERT_CLOSE_ACTION = "com.bnyro.clock.ALARM_ALERT_CLOSE_ACTION"
    }
}
