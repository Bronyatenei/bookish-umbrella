package com.twitchalarm.work

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import com.twitchalarm.ui.SettingsActivity
import kotlin.random.Random

/** Хранит сохранённые через системный picker URI и выбирает один трек для тревоги. */
object AlarmSoundPreferences {
    fun playlist(context: Context): List<Uri> = PreferenceManager
        .getDefaultSharedPreferences(context)
        .getStringSet(SettingsActivity.KEY_ALARM_PLAYLIST, emptySet())
        .orEmpty()
        .mapNotNull { value -> runCatching { Uri.parse(value) }.getOrNull() }

    fun randomPlaylistUri(context: Context): Uri? {
        val tracks = playlist(context)
        return if (tracks.isEmpty()) null else tracks[Random.nextInt(tracks.size)]
    }
}
