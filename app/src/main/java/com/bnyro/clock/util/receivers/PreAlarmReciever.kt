package com.bnyro.clock.util.receivers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import com.bnyro.clock.App
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.Permission
import com.bnyro.clock.ui.MainActivity
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.TimeHelper
import com.bnyro.clock.util.location.LocationAlarmCoordinator
import com.bnyro.clock.util.location.alarmWork
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

class PreAlarmReceiver : BroadcastReceiver() {
    companion object { const val CHANNEL_ID = "upcoming_alarm_channel" }

    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getLongExtra(AlarmHelper.EXTRA_ID, -1)?.takeIf { it != -1L } ?: return
        alarmWork(context) {
            LocationAlarmCoordinator.serialized {
                val repository = (context.applicationContext as App).container.alarmRepository
                val alarm = repository.getAlarmById(id) ?: return@serialized
                val trigger = AlarmHelper.getAlarmTime(alarm) ?: return@serialized
                if ((!alarm.enabled && alarm.snoozedUntil == null) || !Permission.NotificationPermission.hasPermission(context)) return@serialized
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Upcoming Alarms", NotificationManager.IMPORTANCE_DEFAULT))
                val dismiss = PendingIntent.getBroadcast(context, id.toInt() + AlarmHelper.PRE_ALARM_ID_OFFSET,
                    Intent(context, DismissUpcomingReceiver::class.java).putExtra(AlarmHelper.EXTRA_ID, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val content = PendingIntent.getActivity(context, id.toInt() + AlarmHelper.PRE_ALARM_ID_OFFSET,
                    Intent(context, MainActivity::class.java).setAction(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val dateTime = Instant.ofEpochMilli(trigger).atZone(ZoneId.systemDefault())
                val day = dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                val label = alarm.label?.takeIf { it.isNotBlank() }
                val title = when {
                    alarm.snoozedUntil != null && label != null -> context.getString(R.string.snoozed_named_alarm, label)
                    alarm.snoozedUntil != null -> context.getString(R.string.snoozed_alarm)
                    label != null -> context.getString(R.string.upcoming_named_alarm, label)
                    else -> context.getString(R.string.upcoming_alarm)
                }
                manager.notify(id.toInt() + AlarmHelper.PRE_ALARM_ID_OFFSET, NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentTitle(title).setContentText("$day ${TimeHelper.formatTime(context, dateTime)}")
                    .setContentIntent(content).setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .addAction(R.drawable.ic_notification, context.getString(R.string.dismiss), dismiss)
                    .setOngoing(true).setAutoCancel(true).build())
            }
        }
    }
}
