package com.twitchalarm.work

import android.content.Context

/** Stores the current device token locally; the token itself is never logged. */
object FcmTokenStore {
    private const val PREFS = "fcm"
    private const val KEY_TOKEN = "registration_token"

    fun save(context: Context, token: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun get(context: Context): String? = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_TOKEN, null)
}

