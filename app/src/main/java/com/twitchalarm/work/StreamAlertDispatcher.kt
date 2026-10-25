package com.twitchalarm.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.twitchalarm.App
import com.twitchalarm.R
import com.twitchalarm.ui.MainActivity
import com.twitchalarm.ui.SettingsActivity

object StreamAlertDispatcher {
    fun dispatch(context: Context, displayName: String, login: String, title: String, game: String, viewers: Int) {
        val appContext = context.applicationContext
        val notificationOnly = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getBoolean(SettingsActivity.KEY_STREAM_NOTIFICATION_ONLY, false)
        if (notificationOnly) showNotification(appContext, displayName, login, title, game, viewers)
        else AlarmPlaybackService.start(appContext, displayName, title, game, viewers)
    }

    private fun showNotification(context: Context, displayName: String, login: String, title: String, game: String, viewers: Int) {
        val openIntent = PendingIntent.getActivity(
            context, login.hashCode(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val details = buildString {
            if (title.isNotBlank()) append(title)
            if (game.isNotBlank()) { if (isNotEmpty()) append(" · "); append(game) }
            if (viewers > 0) { if (isNotEmpty()) append(" · "); append("$viewers зрителей") }
        }.ifBlank { "Трансляция уже началась" }
        NotificationManagerCompat.from(context).notify(
            "stream_$login".hashCode(),
            NotificationCompat.Builder(context, App.CHANNEL_STREAM_NOTIFICATION)
                .setSmallIcon(R.drawable.ic_twitch)
                .setContentTitle("$displayName в эфире")
                .setContentText(details)
                .setStyle(NotificationCompat.BigTextStyle().bigText(details))
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }
}
