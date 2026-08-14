package com.twitchalarm.work

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.WorkManager

/**
 * Единая точка управления мониторингом Twitch.
 *
 * Старая версия приложения использовала PeriodicWorkRequest. WorkManager не
 * предназначен для частых точных проверок и может запускать их с задержкой.
 * Здесь он используется только для отмены задания, оставшегося после обновления
 * старой версии; новые проверки выполняет [StreamCheckService].
 */
object MonitoringController {
    private const val TAG = "MonitoringController"
    private const val LEGACY_WORK_NAME = "StreamCheck"
    fun start(context: Context) {
        val appContext = context.applicationContext
        // После обновления не даём старому PeriodicWorkRequest дублировать проверки.
        WorkManager.getInstance(appContext).cancelUniqueWork(LEGACY_WORK_NAME)
        val intent = Intent(appContext, StreamCheckService::class.java)
            .setAction(StreamCheckService.ACTION_REFRESH)
        startService(appContext, intent)
    }

    fun checkNow(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, StreamCheckService::class.java)
            .setAction(StreamCheckService.ACTION_CHECK_NOW)
        startService(appContext, intent)
    }

    private fun startService(appContext: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        } catch (error: IllegalStateException) {
            // Возможен только при системном запрете старта из фона. При следующем
            // открытии приложения мониторинг будет восстановлен автоматически.
            Log.e(TAG, "Unable to start monitoring service", error)
        }
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext).cancelUniqueWork(LEGACY_WORK_NAME)
        appContext.stopService(Intent(appContext, StreamCheckService::class.java))
    }
}
