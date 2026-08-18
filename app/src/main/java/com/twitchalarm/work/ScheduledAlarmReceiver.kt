package com.twitchalarm.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.twitchalarm.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receives exact AlarmManager events for normal and snoozed clock alarms. */
class ScheduledAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(ScheduledAlarmScheduler.EXTRA_ALARM_ID, -1L)
        if (alarmId < 0L) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).scheduledAlarmDao()
                val alarm = dao.getById(alarmId) ?: return@launch
                val isSnooze = intent.action == ScheduledAlarmScheduler.ACTION_SNOOZE
                if (!isSnooze && !alarm.enabled) return@launch

                if (!isSnooze) {
                    if (alarm.repeatDays != 0) {
                        ScheduledAlarmScheduler.schedule(context, alarm)
                    } else {
                        dao.update(alarm.copy(enabled = false))
                    }
                }

                AlarmPlaybackService.startScheduledAlarm(
                    context = context,
                    alarmId = alarm.id,
                    hour = alarm.hour,
                    minute = alarm.minute,
                    label = alarm.label
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
