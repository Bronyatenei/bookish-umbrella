package com.twitchalarm.work

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.WorkManager
import com.twitchalarm.data.AppDatabase
import com.twitchalarm.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Единая точка выбора и управления стратегией мониторинга Twitch. */
object MonitoringController {
    private const val TAG = "MonitoringController"
    private const val LEGACY_WORK_NAME = "StreamCheck"

    fun start(context: Context) {
        val appContext = context.applicationContext
        cancelLegacyWork(appContext)
        when (selectedStrategy(appContext)) {
            MonitoringStrategy.RELIABLE -> {
                EconomyCheckReceiver.cancel(appContext)
                startReliableService(appContext, StreamCheckService.ACTION_REFRESH)
            }
            MonitoringStrategy.ECONOMY -> {
                appContext.stopService(Intent(appContext, StreamCheckService::class.java))
                EconomyCheckReceiver.schedule(appContext, immediately = true)
            }
            MonitoringStrategy.HOME_AGENT -> {
                appContext.stopService(Intent(appContext, StreamCheckService::class.java))
                EconomyCheckReceiver.cancel(appContext)
            }
        }
    }

    fun checkNow(context: Context) {
        val appContext = context.applicationContext
        when (selectedStrategy(appContext)) {
            MonitoringStrategy.RELIABLE ->
                startReliableService(appContext, StreamCheckService.ACTION_CHECK_NOW)
            MonitoringStrategy.ECONOMY -> EconomyCheckReceiver.requestCheckNow(appContext)
            MonitoringStrategy.HOME_AGENT -> Unit
        }
    }

    /** Применяет новый выбор из настроек, не оставляя активной предыдущую стратегию. */
    fun reconfigure(context: Context) {
        val appContext = context.applicationContext
        appContext.stopService(Intent(appContext, StreamCheckService::class.java))
        EconomyCheckReceiver.cancel(appContext)
        cancelLegacyWork(appContext)

        CoroutineScope(Dispatchers.IO).launch {
            if (AppDatabase.getInstance(appContext).streamerDao().getEnabled().isNotEmpty()) {
                start(appContext)
            }
        }
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        cancelLegacyWork(appContext)
        EconomyCheckReceiver.cancel(appContext)
        appContext.stopService(Intent(appContext, StreamCheckService::class.java))
    }

    fun selectedStrategy(context: Context): MonitoringStrategy = MonitoringStrategy.fromStoredValue(
        PreferenceManager.getDefaultSharedPreferences(context).getString(
            SettingsActivity.KEY_MONITORING_STRATEGY,
            MonitoringStrategy.RELIABLE.storedValue
        )
    )

    private fun cancelLegacyWork(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WORK_NAME)
    }

    private fun startReliableService(appContext: Context, action: String) {
        val intent = Intent(appContext, StreamCheckService::class.java).setAction(action)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Unable to start reliable monitoring service", error)
        }
    }
}
