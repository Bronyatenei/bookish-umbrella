package com.twitchalarm.work

import android.content.Context

/**
 * Prevents the PC Agent and the optional local parallel check from alarming twice
 * for the same Twitch stream. The state survives a process restart but is scoped
 * to one channel and one Twitch stream ID.
 */
object StreamAlertDeduplicator {
    private const val PREFS = "stream_alert_deduplicator"
    private const val KEY_PREFIX = "last_alert_"

    @Synchronized
    fun shouldTrigger(context: Context, login: String, streamId: String?): Boolean {
        val normalizedLogin = login.trim().lowercase()
        if (normalizedLogin.isEmpty()) return false
        val key = "$KEY_PREFIX$normalizedLogin"
        val eventKey = "$normalizedLogin:${streamId?.takeIf { it.isNotBlank() } ?: "unknown"}"
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getString(key, null) == eventKey) return false
        preferences.edit().putString(key, eventKey).apply()
        return true
    }
}
