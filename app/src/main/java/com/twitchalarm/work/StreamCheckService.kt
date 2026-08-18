package com.twitchalarm.work

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.twitchalarm.App
import com.twitchalarm.R
import com.twitchalarm.ui.MainActivity
import com.twitchalarm.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlin.math.max

/**
 * Постоянный foreground-процесс мониторинга Twitch.
 *
 * В отличие от PeriodicWorkRequest, он выполняет проверку через точный интервал,
 * заданный пользователем, пока включён хотя бы один стример. Пользователь всегда
 * видит постоянное системное уведомление, поэтому Android не считает работу скрытой.
 */
class StreamCheckService : Service() {
    companion object {
        const val ACTION_REFRESH = "com.twitchalarm.action.REFRESH_MONITORING"
        const val ACTION_CHECK_NOW = "com.twitchalarm.action.CHECK_NOW"
        private const val TAG = "StreamCheckService"
        private const val NOTIFICATION_ID = 1001
        private const val NETWORK_RETRY_MILLIS = 60_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduleChanges = Channel<WakeReason>(Channel.CONFLATED)
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var preferenceListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SettingsActivity.KEY_CHECK_INTERVAL) {
                scheduleChanges.trySend(WakeReason.SETTINGS_CHANGED)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        startAsForeground()
        monitorJob = serviceScope.launch { monitorLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CHECK_NOW) {
            scheduleChanges.trySend(WakeReason.MANUAL_CHECK)
        }
        // Повторные команды не создают второй цикл мониторинга.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun monitorLoop() {
        var lastCheckAt = 0L
        while (serviceScope.isActive) {
            val state = checkOnce()
            if (state == CheckState.NO_ENABLED_STREAMERS) {
                Log.i(TAG, "No enabled streamers; stopping monitor")
                stopSelf()
                return
            }

            lastCheckAt = SystemClock.elapsedRealtime()
            val intervalMillis = if (state == CheckState.NETWORK_FAILURE) {
                NETWORK_RETRY_MILLIS
            } else {
                readIntervalMillis()
            }
            var nextCheckAt = lastCheckAt + intervalMillis

            // Изменение интервала не создаёт второй цикл и не теряет текущую
            // проверку: пересчитывается только время следующей проверки.
            while (serviceScope.isActive) {
                val waitMillis = max(0L, nextCheckAt - SystemClock.elapsedRealtime())
                when (withTimeoutOrNull(waitMillis) { scheduleChanges.receive() }) {
                    null -> break
                    WakeReason.MANUAL_CHECK -> break
                    WakeReason.SETTINGS_CHANGED -> {
                        nextCheckAt = lastCheckAt + readIntervalMillis()
                        if (nextCheckAt <= SystemClock.elapsedRealtime()) break
                    }
                }
            }
        }
    }

    private suspend fun checkOnce(): CheckState = when (
        StreamStatusChecker.checkEnabledStreamers(applicationContext)
    ) {
        StreamStatusChecker.Result.SUCCESS -> CheckState.SUCCESS
        StreamStatusChecker.Result.NETWORK_FAILURE -> CheckState.NETWORK_FAILURE
        StreamStatusChecker.Result.NO_ENABLED_STREAMERS -> CheckState.NO_ENABLED_STREAMERS
    }

    private fun readIntervalMillis(): Long {
        val minutes = preferences
            .getInt(SettingsActivity.KEY_CHECK_INTERVAL, SettingsActivity.DEFAULT_INTERVAL)
            .coerceIn(1, 60)
        return minutes * 60_000L
    }

    private fun startAsForeground() {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_twitch)
            .setContentTitle("Мониторинг Twitch активен")
            .setContentText("Проверяем включённые каналы по заданному интервалу")
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        monitorJob?.cancel()
        scheduleChanges.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private enum class WakeReason {
        MANUAL_CHECK,
        SETTINGS_CHANGED
    }

    private enum class CheckState {
        SUCCESS,
        NETWORK_FAILURE,
        NO_ENABLED_STREAMERS
    }
}
