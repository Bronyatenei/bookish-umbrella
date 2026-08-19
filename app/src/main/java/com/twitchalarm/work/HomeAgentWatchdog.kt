package com.twitchalarm.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.preference.PreferenceManager
import com.twitchalarm.data.AppDatabase
import com.twitchalarm.ui.SettingsActivity
import java.util.concurrent.TimeUnit

/**
 * Watches the most recent successful heartbeat sent by the Windows Home Agent.
 * The selected strategy remains HOME_AGENT while fallback is active, so incoming
 * Home Agent stream events can still be accepted and recovery can be detected.
 */
object HomeAgentWatchdog {
    const val ACTION_CHECK = "com.twitchalarm.action.HOME_AGENT_WATCHDOG"

    private const val PREFS = "home_agent_watchdog"
    private const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"
    private const val KEY_SESSION_STARTED_AT = "session_started_at"
    private const val KEY_FALLBACK_ACTIVE = "fallback_active"
    private const val KEY_RECOVERY_HEARTBEATS = "recovery_heartbeats"
    private const val REQUEST_CODE = 4301
    private const val RECOVERY_HEARTBEATS_REQUIRED = 2

    const val DEFAULT_WATCHDOG_INTERVAL_MINUTES = 5
    const val DEFAULT_MISSED_HEARTBEATS = 2
    const val DEFAULT_AUTO_RETURN = true

    fun beginSession(context: Context) {
        preferences(context).edit()
            .remove(KEY_LAST_HEARTBEAT_AT)
            .putLong(KEY_SESSION_STARTED_AT, System.currentTimeMillis())
            .putBoolean(KEY_FALLBACK_ACTIVE, false)
            .putInt(KEY_RECOVERY_HEARTBEATS, 0)
            .apply()
        MonitoringController.stopHomeAgentFallback(context)
        schedule(context)
    }

    /** Keeps an existing Home Agent session intact, or starts a fresh session after all streamers were disabled. */
    fun ensureScheduled(context: Context) {
        if (!preferences(context).contains(KEY_SESSION_STARTED_AT)) {
            beginSession(context)
        } else {
            schedule(context)
        }
    }

    fun endSession(context: Context) {
        cancel(context)
        preferences(context).edit()
            .remove(KEY_LAST_HEARTBEAT_AT)
            .remove(KEY_SESSION_STARTED_AT)
            .putBoolean(KEY_FALLBACK_ACTIVE, false)
            .putInt(KEY_RECOVERY_HEARTBEATS, 0)
            .apply()
    }

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        if (MonitoringController.selectedStrategy(appContext) != MonitoringStrategy.HOME_AGENT) {
            cancel(appContext)
            return
        }

        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + intervalMillis(appContext)
        val pendingIntent = pendingIntent(appContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        appContext.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(appContext))
    }

    suspend fun onHeartbeat(context: Context) {
        val appContext = context.applicationContext
        if (MonitoringController.selectedStrategy(appContext) != MonitoringStrategy.HOME_AGENT) return
        if (AppDatabase.getInstance(appContext).streamerDao().getEnabled().isEmpty()) {
            endSession(appContext)
            return
        }

        val prefs = preferences(appContext)
        val editor = prefs.edit().putLong(KEY_LAST_HEARTBEAT_AT, System.currentTimeMillis())
        if (isFallbackActive(appContext) && autoReturnEnabled(appContext)) {
            val count = prefs.getInt(KEY_RECOVERY_HEARTBEATS, 0) + 1
            editor.putInt(KEY_RECOVERY_HEARTBEATS, count).apply()
            if (count >= RECOVERY_HEARTBEATS_REQUIRED) {
                preferences(appContext).edit()
                    .putBoolean(KEY_FALLBACK_ACTIVE, false)
                    .putInt(KEY_RECOVERY_HEARTBEATS, 0)
                    .apply()
                MonitoringController.stopHomeAgentFallback(appContext)
            }
        } else {
            editor.putInt(KEY_RECOVERY_HEARTBEATS, 0).apply()
        }
        schedule(appContext)
    }

    suspend fun evaluate(context: Context) {
        val appContext = context.applicationContext
        if (MonitoringController.selectedStrategy(appContext) != MonitoringStrategy.HOME_AGENT) {
            cancel(appContext)
            return
        }
        if (AppDatabase.getInstance(appContext).streamerDao().getEnabled().isEmpty()) {
            endSession(appContext)
            return
        }

        val prefs = preferences(appContext)
        val lastHeartbeatAt = prefs.getLong(KEY_LAST_HEARTBEAT_AT, 0L)
        val referenceAt = if (lastHeartbeatAt > 0L) {
            lastHeartbeatAt
        } else {
            prefs.getLong(KEY_SESSION_STARTED_AT, System.currentTimeMillis())
        }
        val stale = System.currentTimeMillis() - referenceAt >= timeoutMillis(appContext)
        if (stale && !isFallbackActive(appContext)) {
            preferences(appContext).edit()
                .putBoolean(KEY_FALLBACK_ACTIVE, true)
                .putInt(KEY_RECOVERY_HEARTBEATS, 0)
                .apply()
            MonitoringController.startHomeAgentFallback(appContext, fallbackStrategy(appContext))
        }
        schedule(appContext)
    }

    fun returnToHomeAgent(context: Context) {
        val appContext = context.applicationContext
        preferences(appContext).edit()
            .putBoolean(KEY_FALLBACK_ACTIVE, false)
            .putInt(KEY_RECOVERY_HEARTBEATS, 0)
            .putLong(KEY_LAST_HEARTBEAT_AT, System.currentTimeMillis())
            .putLong(KEY_SESSION_STARTED_AT, System.currentTimeMillis())
            .apply()
        MonitoringController.stopHomeAgentFallback(appContext)
        schedule(appContext)
    }

    fun isFallbackActive(context: Context): Boolean = preferences(context)
        .getBoolean(KEY_FALLBACK_ACTIVE, false)

    fun lastHeartbeatAt(context: Context): Long = preferences(context)
        .getLong(KEY_LAST_HEARTBEAT_AT, 0L)

    fun fallbackStrategy(context: Context): MonitoringStrategy = MonitoringStrategy.fromStoredValue(
        PreferenceManager.getDefaultSharedPreferences(context).getString(
            SettingsActivity.KEY_HOME_AGENT_FALLBACK_STRATEGY,
            MonitoringStrategy.ECONOMY.storedValue
        )
    ).let { if (it == MonitoringStrategy.HOME_AGENT) MonitoringStrategy.ECONOMY else it }

    fun autoReturnEnabled(context: Context): Boolean = PreferenceManager
        .getDefaultSharedPreferences(context)
        .getBoolean(SettingsActivity.KEY_HOME_AGENT_AUTO_RETURN, DEFAULT_AUTO_RETURN)

    fun intervalMinutes(context: Context): Int = PreferenceManager
        .getDefaultSharedPreferences(context)
        .getInt(SettingsActivity.KEY_HOME_AGENT_WATCHDOG_INTERVAL, DEFAULT_WATCHDOG_INTERVAL_MINUTES)
        .coerceIn(5, 25)

    fun missedHeartbeats(context: Context): Int = PreferenceManager
        .getDefaultSharedPreferences(context)
        .getInt(SettingsActivity.KEY_HOME_AGENT_MISSED_HEARTBEATS, DEFAULT_MISSED_HEARTBEATS)
        .coerceIn(1, 9)

    fun timeoutMillis(context: Context): Long = intervalMillis(context) * missedHeartbeats(context)

    fun intervalMillis(context: Context): Long = TimeUnit.MINUTES.toMillis(intervalMinutes(context).toLong())

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, HomeAgentWatchdogReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
