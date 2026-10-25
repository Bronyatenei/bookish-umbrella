package com.twitchalarm.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ServerWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ServerWatchdog.action) return
        ServerWatchdog.runCheck(context)
    }
}
