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
    const val ACTION_STATUS_CHANGED = "com.twitchalarm.action.HOME_AGENT_STATUS_CHANGED"

    private const val PREFS = "home_agent_watchdog"
    private const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"
    private const val KEY_LAST_HEARTBEAT_RECEIVED_AT = "last_heartbeat_received_at"
    private const val KEY_LAST_HEARTBEAT_RESULT = "last_heartbeat_result"
    private const val KEY_LAST_FALLBACK_AT = "last_fallback_at"
    private const val KEY_LAST_FALLBACK_REASON = "last_fallback_reason"
    private const val KEY_LAST_HEARTBEAT_SESSION_ID = "last_heartbeat_session_id"
    private const val KEY_LAST_HEARTBEAT_SEQUENCE = "last_heartbeat_sequence"
    private const val KEY_LAST_HEARTBEAT_ID = "last_heartbeat_id"
    private const val KEY_SESSION_STARTED_AT = "session_started_at"
    private const val KEY_FALLBACK_ACTIVE = "fallback_active"
    private const val KEY_RECOVERY_HEARTBEATS = "recovery_heartbeats"
    private const val REQUEST_CODE = 4301
    private const val RECOVERY_HEARTBEATS_REQUIRED = 2
    // The PC sends heartbeat on a fixed cadence, while FCM delivery and Android wakeups have
    // small independent jitter. Keep a bounded grace period to avoid false fallback exactly
    // on the timeout boundary without weakening the configurable missed-heartbeat threshold.
    private val FCM_DELIVERY_GRACE_MILLIS = TimeUnit.SECONDS.toMillis(90)
    private val MAX_CLOCK_SKEW_MILLIS = TimeUnit.MINUTES.toMillis(5)

    const val DEFAULT_WATCHDOG_INTERVAL_MINUTES = 5
    const val DEFAULT_MISSED_HEARTBEATS = 2
    const val DEFAULT_AUTO_RETURN = true

    fun beginSession(context: Context) {
        preferences(context).edit()
            .remove(KEY_LAST_HEARTBEAT_AT)
            .remove(KEY_LAST_HEARTBEAT_RECEIVED_AT)
            .remove(KEY_LAST_HEARTBEAT_RESULT)
            .remove(KEY_LAST_FALLBACK_AT)
            .remove(KEY_LAST_FALLBACK_REASON)
            .remove(KEY_LAST_HEARTBEAT_SESSION_ID)
            .remove(KEY_LAST_HEARTBEAT_SEQUENCE)
            .remove(KEY_LAST_HEARTBEAT_ID)
            .putLong(KEY_SESSION_STARTED_AT, System.currentTimeMillis())
            .putBoolean(KEY_FALLBACK_ACTIVE, false)
            .putInt(KEY_RECOVERY_HEARTBEATS, 0)
            .apply()
        MonitoringController.stopHomeAgentFallback(context)
        schedule(context)
        publishStatus(context)
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
            .remove(KEY_LAST_HEARTBEAT_RECEIVED_AT)
            .remove(KEY_LAST_HEARTBEAT_RESULT)
            .remove(KEY_LAST_FALLBACK_AT)
            .remove(KEY_LAST_FALLBACK_REASON)
            .remove(KEY_LAST_HEARTBEAT_SESSION_ID)
            .remove(KEY_LAST_HEARTBEAT_SEQUENCE)
            .remove(KEY_LAST_HEARTBEAT_ID)
            .remove(KEY_SESSION_STARTED_AT)
            .putBoolean(KEY_FALLBACK_ACTIVE, false)
            .putInt(KEY_RECOVERY_HEARTBEATS, 0)
            .apply()
        publishStatus(context)
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

    /**
     * Accepts only a fresh heartbeat from the newest agent session. Version 2 heartbeat carries
     * the successful PC-check time, an agent session ID and a monotonically increasing sequence.
     * Legacy heartbeat is accepted by receipt time so already-installed agents keep working until
     * they are updated.
     */
    suspend fun onHeartbeat(context: Context, heartbeat: Heartbeat): Boolean {
        val appContext = context.applicationContext
        if (MonitoringController.selectedStrategy(appContext) != MonitoringStrategy.HOME_AGENT) return false
        if (AppDatabase.getInstance(appContext).streamerDao().getEnabled().isEmpty()) {
            endSession(appContext)
            return false
        }

        val prefs = preferences(appContext)
        val receivedAt = System.currentTimeMillis()
        val accepted = acceptHeartbeat(prefs, heartbeat, receivedAt)
        if (!accepted.accepted) {
            prefs.edit()
                .putLong(KEY_LAST_HEARTBEAT_RECEIVED_AT, receivedAt)
                .putString(KEY_LAST_HEARTBEAT_RESULT, "Отклонён: ${accepted.reason}")
                .apply()
            publishStatus(appContext)
            return false
        }

        val editor = prefs.edit()
            .putLong(KEY_LAST_HEARTBEAT_AT, accepted.sentAtMillis)
            .putLong(KEY_LAST_HEARTBEAT_RECEIVED_AT, receivedAt)
            .putString(KEY_LAST_HEARTBEAT_RESULT, accepted.reason)
        if (heartbeat.isVersion2) {
            editor
                .putString(KEY_LAST_HEARTBEAT_SESSION_ID, heartbeat.sessionId)
                .putLong(KEY_LAST_HEARTBEAT_SEQUENCE, heartbeat.sequence)
                .putString(KEY_LAST_HEARTBEAT_ID, heartbeat.id)
        }
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
        publishStatus(appContext)
        return true
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
            val reason = if (lastHeartbeatAt == 0L) {
                "Не получен первый heartbeat"
            } else {
                "Нет свежего heartbeat дольше заданного порога"
            }
            preferences(appContext).edit()
                .putBoolean(KEY_FALLBACK_ACTIVE, true)
                .putLong(KEY_LAST_FALLBACK_AT, System.currentTimeMillis())
                .putString(KEY_LAST_FALLBACK_REASON, reason)
                .putInt(KEY_RECOVERY_HEARTBEATS, 0)
                .apply()
            MonitoringController.startHomeAgentFallback(appContext, fallbackStrategy(appContext))
        }
        schedule(appContext)
        publishStatus(appContext)
    }

    fun returnToHomeAgent(context: Context) {
        val appContext = context.applicationContext
        preferences(appContext).edit()
            .putBoolean(KEY_FALLBACK_ACTIVE, false)
            .putInt(KEY_RECOVERY_HEARTBEATS, 0)
            .putLong(KEY_LAST_HEARTBEAT_AT, System.currentTimeMillis())
            .remove(KEY_LAST_HEARTBEAT_SESSION_ID)
            .remove(KEY_LAST_HEARTBEAT_SEQUENCE)
            .remove(KEY_LAST_HEARTBEAT_ID)
            .putLong(KEY_SESSION_STARTED_AT, System.currentTimeMillis())
            .apply()
        MonitoringController.stopHomeAgentFallback(appContext)
        schedule(appContext)
        publishStatus(appContext)
    }

    private fun publishStatus(context: Context) {
        val appContext = context.applicationContext
        appContext.sendBroadcast(
            Intent(ACTION_STATUS_CHANGED).setPackage(appContext.packageName)
        )
    }

    private fun acceptHeartbeat(
        prefs: android.content.SharedPreferences,
        heartbeat: Heartbeat,
        receivedAt: Long
    ): AcceptedHeartbeat {
        if (!heartbeat.isVersion2) return AcceptedHeartbeat(true, receivedAt, "Принят legacy heartbeat")

        val sentAt = heartbeat.sentAtMillis ?: return AcceptedHeartbeat(false, 0L, "нет времени ПК")
        val sessionId = heartbeat.sessionId ?: return AcceptedHeartbeat(false, 0L, "нет ID сессии")
        val id = heartbeat.id ?: return AcceptedHeartbeat(false, 0L, "нет ID heartbeat")
        if (heartbeat.sequence < 1L) return AcceptedHeartbeat(false, 0L, "некорректный номер")
        if (sentAt > receivedAt + MAX_CLOCK_SKEW_MILLIS) return AcceptedHeartbeat(false, 0L, "время ПК слишком далеко в будущем")
        // A long-delayed old message must never make a stopped PC look healthy.
        if (receivedAt - sentAt >= timeoutMillisFromPreferences(prefs)) {
            return AcceptedHeartbeat(false, 0L, "пульс слишком старый при доставке")
        }

        val previousSentAt = prefs.getLong(KEY_LAST_HEARTBEAT_AT, 0L)
        val previousSessionId = prefs.getString(KEY_LAST_HEARTBEAT_SESSION_ID, null)
        val previousSequence = prefs.getLong(KEY_LAST_HEARTBEAT_SEQUENCE, 0L)
        val previousId = prefs.getString(KEY_LAST_HEARTBEAT_ID, null)
        if (id == previousId) return AcceptedHeartbeat(false, 0L, "дубликат")
        if (previousSessionId != null && sentAt < previousSentAt) {
            return AcceptedHeartbeat(false, 0L, "старее уже принятого heartbeat")
        }
        if (sessionId == previousSessionId && heartbeat.sequence <= previousSequence) {
            return AcceptedHeartbeat(false, 0L, "номер не новее предыдущего")
        }
        val deliveryDelay = ((receivedAt - sentAt).coerceAtLeast(0L) / 1_000L)
        return AcceptedHeartbeat(true, sentAt, "Принят #${heartbeat.sequence}; доставка ${deliveryDelay} сек")
    }

    fun isFallbackActive(context: Context): Boolean = preferences(context)
        .getBoolean(KEY_FALLBACK_ACTIVE, false)

    fun lastHeartbeatAt(context: Context): Long = preferences(context)
        .getLong(KEY_LAST_HEARTBEAT_AT, 0L)

    fun lastHeartbeatReceivedAt(context: Context): Long = preferences(context)
        .getLong(KEY_LAST_HEARTBEAT_RECEIVED_AT, 0L)

    fun lastHeartbeatResult(context: Context): String? = preferences(context)
        .getString(KEY_LAST_HEARTBEAT_RESULT, null)

    fun lastFallbackAt(context: Context): Long = preferences(context)
        .getLong(KEY_LAST_FALLBACK_AT, 0L)

    fun lastFallbackReason(context: Context): String? = preferences(context)
        .getString(KEY_LAST_FALLBACK_REASON, null)

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

    fun timeoutMillis(context: Context): Long = timeoutMillisFromPreferences(preferences(context))

    private fun timeoutMillisFromPreferences(prefs: android.content.SharedPreferences): Long {
        val interval = prefs.getInt(SettingsActivity.KEY_HOME_AGENT_WATCHDOG_INTERVAL, DEFAULT_WATCHDOG_INTERVAL_MINUTES)
            .coerceIn(5, 25)
        val missed = prefs.getInt(SettingsActivity.KEY_HOME_AGENT_MISSED_HEARTBEATS, DEFAULT_MISSED_HEARTBEATS)
            .coerceIn(1, 9)
        return TimeUnit.MINUTES.toMillis(interval.toLong()) * missed + FCM_DELIVERY_GRACE_MILLIS
    }

    fun intervalMillis(context: Context): Long = TimeUnit.MINUTES.toMillis(intervalMinutes(context).toLong())

    data class Heartbeat(
        val version: Int?,
        val id: String?,
        val sessionId: String?,
        val sequence: Long,
        val sentAtMillis: Long?
    ) {
        val isVersion2: Boolean get() = version != null && version >= 2
    }

    private data class AcceptedHeartbeat(
        val accepted: Boolean,
        val sentAtMillis: Long,
        val reason: String
    )

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, HomeAgentWatchdogReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
