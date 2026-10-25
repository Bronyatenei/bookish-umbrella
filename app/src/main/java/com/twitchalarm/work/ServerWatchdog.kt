package com.twitchalarm.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.runBlocking

object ServerWatchdog {
    private const val TAG = "ServerWatchdog"
    private const val REQUEST_CODE = 4201
    private const val ACTION_CHECK = "com.twitchalarm.action.SERVER_HEALTH_CHECK"
    private const val PREFS = "server_watchdog"
    private const val KEY_LAST_HEALTH = "last_health_at"
    private const val KEY_FALLBACK = "fallback_active"
    private const val PERIOD_MILLIS = 24L * 60L * 60L * 1000L

    fun scheduleIfEnabled(context: Context) {
        if (!fallbackEnabled(context)) { cancel(context); return }
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PERIOD_MILLIS, pendingIntent(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    fun runCheck(context: Context) {
        if (MonitoringController.selectedStrategy(context) != MonitoringStrategy.SERVER || !fallbackEnabled(context)) return
        val appContext = context.applicationContext
        val healthy = runBlocking { RelayApi.health(appContext) }
        preferences(appContext).edit().putLong(KEY_LAST_HEALTH, System.currentTimeMillis()).apply()
        if (healthy) {
            clearFallback(appContext)
            MonitoringController.stopHomeAgentFallback(appContext)
        } else {
            Log.e(TAG, "Relay health check failed")
            preferences(appContext).edit().putBoolean(KEY_FALLBACK, true).apply()
            MonitoringController.startHomeAgentFallback(appContext, MonitoringStrategy.ECONOMY)
        }
        scheduleIfEnabled(appContext)
    }

    fun fallbackEnabled(context: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(context)
        .getBoolean(com.twitchalarm.ui.SettingsActivity.KEY_SERVER_FALLBACK_ENABLED, false)

    fun setFallbackEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(com.twitchalarm.ui.SettingsActivity.KEY_SERVER_FALLBACK_ENABLED, enabled).apply()
        if (enabled && MonitoringController.selectedStrategy(context) == MonitoringStrategy.SERVER) scheduleIfEnabled(context) else if (!enabled) {
            cancel(context); clearFallback(context)
            if (MonitoringController.selectedStrategy(context) == MonitoringStrategy.SERVER) MonitoringController.stopHomeAgentFallback(context)
        }
    }

    fun isFallbackActive(context: Context): Boolean = preferences(context).getBoolean(KEY_FALLBACK, false)
    fun lastHealthAt(context: Context): Long = preferences(context).getLong(KEY_LAST_HEALTH, 0L)
    fun clearFallback(context: Context) { preferences(context).edit().putBoolean(KEY_FALLBACK, false).apply() }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, ServerWatchdogReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    class ReceiverMarker
    val action: String get() = ACTION_CHECK
}
