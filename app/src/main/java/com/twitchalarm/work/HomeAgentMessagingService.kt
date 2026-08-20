package com.twitchalarm.work

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.twitchalarm.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

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
        when (data[KEY_TYPE]) {
            EVENT_HEARTBEAT -> {
                val heartbeat = HomeAgentWatchdog.Heartbeat(
                    version = data[KEY_HEARTBEAT_VERSION]?.toIntOrNull(),
                    id = data[KEY_HEARTBEAT_ID],
                    sessionId = data[KEY_HEARTBEAT_SESSION_ID],
                    sequence = data[KEY_HEARTBEAT_SEQUENCE]?.toLongOrNull() ?: 0L,
                    sentAtMillis = data[KEY_HEARTBEAT_SENT_AT]?.toLongOrNull()
                )
                val accepted = HomeAgentWatchdog.onHeartbeat(this, heartbeat)
                Log.d(TAG, "Heartbeat ${if (accepted) "accepted" else "ignored"}: ${heartbeat.id ?: "legacy"}")
                // A one-off stream event may have been lost during Doze. The Agent relays a
                // bounded pending alert inside heartbeat until it expires; dedupe handles repeats.
                handleRelayedStreamAlert(data[KEY_PENDING_STREAM_ALERT])
                return
            }
            EVENT_STREAM_ONLINE -> handleStreamOnline(data)
            else -> return
        }
    }

    private suspend fun handleRelayedStreamAlert(rawAlert: String?) {
        if (rawAlert.isNullOrBlank()) return
        val alert = runCatching { JSONObject(rawAlert) }.getOrElse {
            Log.w(TAG, "Ignored malformed relayed stream alert", it)
            return
        }
        handleStreamOnline(
            mapOf(
                KEY_TYPE to EVENT_STREAM_ONLINE,
                KEY_EVENT_ID to alert.optString("eventId"),
                KEY_SENT_AT to alert.optString("sentAt"),
                KEY_LOGIN to alert.optString("login"),
                KEY_DISPLAY_NAME to alert.optString("displayName"),
                KEY_TITLE to alert.optString("title"),
                KEY_GAME to alert.optString("game"),
                KEY_VIEWERS to alert.optString("viewers"),
                KEY_RELAYED to "true"
            )
        )
    }

    private suspend fun handleStreamOnline(data: Map<String, String>) {
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

        // The PC agent owns transition detection, but a parallel local check can discover
        // the same Twitch stream first. Both sources share one stream-specific alarm key.
        val streamId = eventId
            ?.removePrefix("$login:")
            ?.takeIf { it.isNotBlank() }
        if (StreamAlertDeduplicator.shouldTrigger(this, login, streamId)) {
            Log.i(TAG, "Starting stream alarm for $login from ${if (data.containsKey(KEY_RELAYED)) "heartbeat relay" else "direct FCM"}")
            AlarmPlaybackService.start(
                context = this,
                displayName = displayName,
                title = title,
                game = game,
                viewers = viewers
            )
        }
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
        private const val TAG = "HomeAgentMessaging"
        private const val KEY_TYPE = "type"
        private const val KEY_HEARTBEAT_VERSION = "version"
        private const val KEY_HEARTBEAT_ID = "heartbeat_id"
        private const val KEY_HEARTBEAT_SESSION_ID = "session_id"
        private const val KEY_HEARTBEAT_SEQUENCE = "sequence"
        private const val KEY_HEARTBEAT_SENT_AT = "sent_at"
        private const val KEY_PENDING_STREAM_ALERT = "pending_stream_alert"
        private const val KEY_RELAYED = "_relayed"
        private const val KEY_LOGIN = "login"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_TITLE = "title"
        private const val KEY_GAME = "game"
        private const val KEY_VIEWERS = "viewers"
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_SENT_AT = "sent_at"
        private const val EVENT_STREAM_ONLINE = "stream_online"
        private const val EVENT_HEARTBEAT = "home_agent_heartbeat"
        private val EVENT_LOCK = Any()
    }
}
