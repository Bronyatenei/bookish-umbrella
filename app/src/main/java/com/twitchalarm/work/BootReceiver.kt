package com.twitchalarm.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Восстанавливает мониторинг после перезагрузки устройства. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            MonitoringController.start(context)
        }
    }
}
