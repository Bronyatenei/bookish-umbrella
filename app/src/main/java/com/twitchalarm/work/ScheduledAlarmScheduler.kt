package com.twitchalarm.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.twitchalarm.data.ScheduledAlarm
import java.util.Calendar

/** Schedules local clock alarms independently of Twitch monitoring and network access. */
object ScheduledAlarmScheduler {
    const val ACTION_TRIGGER = "com.twitchalarm.action.TIME_ALARM_TRIGGER"
    const val ACTION_SNOOZE = "com.twitchalarm.action.TIME_ALARM_SNOOZE"
    const val EXTRA_ALARM_ID = "scheduled_alarm_id"

    fun schedule(context: Context, alarm: ScheduledAlarm) {
        if (!alarm.enabled) {
            cancel(context, alarm.id)
            return
        }
        scheduleAt(context, alarm.id, nextTriggerAt(alarm), ACTION_TRIGGER)
    }

    fun scheduleSnooze(context: Context, alarmId: Long, minutes: Int) {
        val triggerAt = System.currentTimeMillis() + minutes.coerceIn(1, 60) * 60_000L
        scheduleAt(context, alarmId, triggerAt, ACTION_SNOOZE)
    }

    fun cancel(context: Context, alarmId: Long) {
        val manager = alarmManager(context)
        pendingIntent(context, alarmId, ACTION_TRIGGER, create = false)?.let(manager::cancel)
        pendingIntent(context, alarmId, ACTION_SNOOZE, create = false)?.let(manager::cancel)
    }

    suspend fun rescheduleAllEnabled(context: Context) {
        val alarms = com.twitchalarm.data.AppDatabase.getInstance(context)
            .scheduledAlarmDao()
            .getEnabled()
        alarms.forEach { schedule(context, it) }
    }

    fun nextTriggerAt(alarm: ScheduledAlarm, fromMillis: Long = System.currentTimeMillis()): Long {
        val candidate = Calendar.getInstance().apply {
            timeInMillis = fromMillis
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (alarm.repeatDays == 0) {
            if (candidate.timeInMillis <= fromMillis) candidate.add(Calendar.DAY_OF_YEAR, 1)
            return candidate.timeInMillis
        }

        repeat(8) {
            val dayBit = 1 shl candidate.get(Calendar.DAY_OF_WEEK)
            if ((alarm.repeatDays and dayBit) != 0 && candidate.timeInMillis > fromMillis) {
                return candidate.timeInMillis
            }
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        return candidate.timeInMillis
    }

    private fun scheduleAt(context: Context, alarmId: Long, triggerAt: Long, action: String) {
        val manager = alarmManager(context)
        val operation = pendingIntent(context, alarmId, action, create = true) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            return
        }

        val showIntent = PendingIntent.getActivity(
            context,
            alarmId.toInt(),
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, com.twitchalarm.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation)
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(
        context: Context,
        alarmId: Long,
        action: String,
        create: Boolean
    ): PendingIntent? {
        val requestCode = when (action) {
            ACTION_SNOOZE -> alarmId.toInt() xor 0x40000000
            else -> alarmId.toInt()
        }
        val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ScheduledAlarmReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_ALARM_ID, alarmId),
            flags
        )
    }
}
