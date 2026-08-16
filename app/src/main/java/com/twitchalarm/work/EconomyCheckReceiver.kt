package com.twitchalarm.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import com.twitchalarm.ui.SettingsActivity
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Экономичный мониторинг: Android будит приложение для одной проверки, после чего
 * receiver завершается и оставляет в системе ровно одно следующее пробуждение.
 *
 * Режим намеренно использует setAndAllowWhileIdle, а не точные alarm'ы: он не
 * требует специального разрешения, но при Doze допускает дополнительную задержку.
 */
class EconomyCheckReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ECONOMY_CHECK = "com.twitchalarm.action.ECONOMY_CHECK"
        private const val TAG = "EconomyCheckReceiver"
        private const val REQUEST_CODE = 4101

        private val executor = Executors.newSingleThreadExecutor()
        private val isChecking = AtomicBoolean(false)

        fun schedule(context: Context, immediately: Boolean = false) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
            val triggerAt = SystemClock.elapsedRealtime() + if (immediately) {
                0L
            } else {
                TimeUnit.MINUTES.toMillis(readEffectiveIntervalMinutes(appContext).toLong())
            }
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent(appContext)
            )
        }

        fun requestCheckNow(context: Context) {
            context.applicationContext.sendBroadcast(
                Intent(context.applicationContext, EconomyCheckReceiver::class.java)
                    .setAction(ACTION_ECONOMY_CHECK)
            )
        }

        fun cancel(context: Context) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(pendingIntent(appContext))
        }

        fun readEffectiveIntervalMinutes(context: Context): Int {
            val requested = PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(SettingsActivity.KEY_CHECK_INTERVAL, SettingsActivity.DEFAULT_INTERVAL)
                .coerceIn(1, 60)
            return requested
        }

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, EconomyCheckReceiver::class.java).setAction(ACTION_ECONOMY_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_ECONOMY_CHECK) return
        val pendingResult = goAsync()

        // Повторно доставленный broadcast не запускает параллельный HTTP-цикл.
        if (!isChecking.compareAndSet(false, true)) {
            pendingResult.finish()
            return
        }

        val appContext = context.applicationContext
        executor.execute {
            try {
                when (runBlocking { StreamStatusChecker.checkEnabledStreamers(appContext) }) {
                    StreamStatusChecker.Result.NO_ENABLED_STREAMERS -> cancel(appContext)
                    StreamStatusChecker.Result.NETWORK_FAILURE -> schedule(appContext)
                    StreamStatusChecker.Result.SUCCESS -> schedule(appContext)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Economy check failed", error)
                schedule(appContext)
            } finally {
                isChecking.set(false)
                pendingResult.finish()
            }
        }
    }

}
