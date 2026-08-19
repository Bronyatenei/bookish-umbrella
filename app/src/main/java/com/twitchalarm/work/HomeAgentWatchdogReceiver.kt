package com.twitchalarm.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/** Performs one short heartbeat freshness evaluation when Android wakes the app. */
class HomeAgentWatchdogReceiver : BroadcastReceiver() {
    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != HomeAgentWatchdog.ACTION_CHECK) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        executor.execute {
            try {
                runBlocking { HomeAgentWatchdog.evaluate(appContext) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
