package com.twitchalarm.work

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.twitchalarm.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Receives high-priority stream-start events from the Windows Home Agent. */
class HomeAgentMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        FcmTokenStore.save(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Firebase invokes this callback on a worker thread; wait for the short Room update
        // so the process cannot be reclaimed before the alarm is started.
        runBlocking(Dispatchers.IO) {
            handle(message.data)
        }
    }

    private suspend fun handle(data: Map<String, String>) {
        if (MonitoringController.selectedStrategy(this) != MonitoringStrategy.HOME_AGENT) return
        if (data[KEY_TYPE] != EVENT_STREAM_ONLINE) return

        val login = data[KEY_LOGIN]?.trim()?.lowercase().orEmpty()
        if (login.isEmpty()) return

        val database = AppDatabase.getInstance(this)
        val streamer = database.streamerDao().getByLogin(login) ?: return
        if (!streamer.notifyEnabled) return

        val eventId = data[KEY_EVENT_ID]?.takeIf { it.isNotBlank() }
        if (eventId != null && !rememberEvent(login, eventId)) return

        val title = data[KEY_TITLE].orEmpty()
        val game = data[KEY_GAME].orEmpty()
        val displayName = data[KEY_DISPLAY_NAME]
            ?.takeIf { it.isNotBlank() }
            ?: streamer.displayName.ifBlank { login }
        val viewers = data[KEY_VIEWERS]?.toIntOrNull() ?: 0

        database.streamerDao().updateLiveStatus(
            login = login,
            isLive = true,
            title = title,
            viewers = viewers,
            game = game,
            displayName = displayName
        )

        // The PC agent owns transition detection. The phone only acts on a new event.
        AlarmPlaybackService.start(
            context = this,
            displayName = displayName,
            title = title,
            game = game,
            viewers = viewers
        )
    }

    private fun rememberEvent(login: String, eventId: String): Boolean {
        synchronized(EVENT_LOCK) {
            val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
            val key = "$KEY_LAST_EVENT_PREFIX$login"
            if (preferences.getString(key, null) == eventId) return false
            preferences.edit().putString(key, eventId).apply()
            return true
        }
    }


    companion object {
        private const val PREFS = "home_agent_events"
        private const val KEY_LAST_EVENT_PREFIX = "last_"
        private const val KEY_TYPE = "type"
        private const val KEY_LOGIN = "login"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_TITLE = "title"
        private const val KEY_GAME = "game"
        private const val KEY_VIEWERS = "viewers"
        private const val KEY_EVENT_ID = "event_id"
        private const val EVENT_STREAM_ONLINE = "stream_online"
        private val EVENT_LOCK = Any()
    }
}
