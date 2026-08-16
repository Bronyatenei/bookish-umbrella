package com.twitchalarm.work

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.twitchalarm.App
import com.twitchalarm.R
import com.twitchalarm.ui.AlarmActivity
import com.twitchalarm.ui.SettingsActivity

/**
 * Воспроизводит тревогу вне Activity. Благодаря foreground-режиму звук и вибрация
 * продолжаются, когда основное приложение закрыто, свёрнуто или экран выключен.
 */
class AlarmPlaybackService : Service() {
    companion object {
        const val ACTION_START = "com.twitchalarm.action.START_ALARM"
        const val ACTION_STOP = "com.twitchalarm.action.STOP_ALARM"
        const val EXTRA_STREAMER = "extra_streamer"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_GAME = "extra_game"
        const val EXTRA_VIEWERS = "extra_viewers"

        private const val TAG = "AlarmPlaybackService"
        private const val NOTIFICATION_ID = 2001

        fun start(
            context: Context,
            displayName: String,
            title: String,
            game: String,
            viewers: Int
        ) {
            val intent = Intent(context, AlarmPlaybackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STREAMER, displayName)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_GAME, game)
                putExtra(EXTRA_VIEWERS, viewers)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Android rejected background alarm service start", error)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmPlaybackService::class.java))
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val volumeHandler = Handler(Looper.getMainLooper())
    private var volumeRamp: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val streamer = intent?.getStringExtra(EXTRA_STREAMER) ?: "Стример"
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val game = intent?.getStringExtra(EXTRA_GAME).orEmpty()
        val viewers = intent?.getIntExtra(EXTRA_VIEWERS, 0) ?: 0

        startAsForeground(streamer, title, game, viewers)
        restartAlarmSound()
        startVibration()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(streamer: String, title: String, game: String, viewers: Int) {
        val alarmIntent = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmActivity.EXTRA_STREAMER, streamer)
            putExtra(AlarmActivity.EXTRA_TITLE, title)
            putExtra(AlarmActivity.EXTRA_GAME, game)
            putExtra(AlarmActivity.EXTRA_VIEWERS, viewers)
        }
        val fullScreenIntent = PendingIntent.getActivity(
            this,
            streamer.hashCode(),
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AlarmPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val description = buildString {
            append(title.ifBlank { "$streamer начал стрим" })
            if (game.isNotBlank()) append(" · $game")
        }
        val builder = NotificationCompat.Builder(this, App.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_twitch)
            .setContentTitle("$streamer в эфире")
            .setContentText(description)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, canUseFullScreenIntent())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", stopIntent)

        val notification: Notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val notificationManager = getSystemService(NotificationManager::class.java)
        return notificationManager.canUseFullScreenIntent()
    }

    private fun restartAlarmSound() {
        stopPlaybackOnly()
        audioManager = getSystemService(AudioManager::class.java)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        requestAudioFocus(audioAttributes)
        val uri = findAlarmUri() ?: run {
            Log.e(TAG, "No alarm sound URI is available")
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(this@AlarmPlaybackService, uri)
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener { player ->
                    player.start()
                    startVolumeRamp(player, readAlarmVolume(), readFadeDurationMillis())
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    true
                }
                prepareAsync()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start alarm playback", error)
        }
    }

    private fun findAlarmUri(): Uri? = AlarmSoundPreferences.randomPlaylistUri(this)
        ?: listOf(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            Settings.System.DEFAULT_RINGTONE_URI
        ).firstOrNull { it != null }

    /**
     * Slider controls this application's signal amplitude only. We deliberately
     * do not change the device-wide alarm volume selected by the user.
     */
    private fun readAlarmVolume(): Float {
        val percent = PreferenceManager.getDefaultSharedPreferences(this)
            .getInt(SettingsActivity.KEY_ALARM_VOLUME, SettingsActivity.DEFAULT_VOLUME)
            .coerceIn(0, 100)
        return percent / 100f
    }

    private fun readFadeDurationMillis(): Long = PreferenceManager
        .getDefaultSharedPreferences(this)
        .getInt(
            SettingsActivity.KEY_ALARM_FADE_SECONDS,
            SettingsActivity.DEFAULT_FADE_SECONDS
        )
        .coerceIn(0, 120)
        .times(1_000L)

    /** Starts just above silence, then raises volume to the configured target. */
    private fun startVolumeRamp(player: MediaPlayer, target: Float, durationMillis: Long) {
        cancelVolumeRamp()
        if (target <= 0f || durationMillis <= 0L) {
            player.setVolume(target, target)
            return
        }

        val initial = minOf(target, 0.02f)
        val startedAt = SystemClock.elapsedRealtime()
        player.setVolume(initial, initial)
        val runnable = object : Runnable {
            override fun run() {
                if (mediaPlayer !== player) return
                val progress = ((SystemClock.elapsedRealtime() - startedAt).toFloat() / durationMillis)
                    .coerceIn(0f, 1f)
                val volume = initial + (target - initial) * progress
                player.setVolume(volume, volume)
                if (progress < 1f) {
                    volumeHandler.postDelayed(this, 100L)
                }
            }
        }
        volumeRamp = runnable
        volumeHandler.post(runnable)
    }

    private fun cancelVolumeRamp() {
        volumeRamp?.let { volumeHandler.removeCallbacks(it) }
        volumeRamp = null
    }

    private fun requestAudioFocus(audioAttributes: AudioAttributes) {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .build()
            manager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 500, 300, 500, 300, 1_000, 500)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopPlaybackOnly() {
        cancelVolumeRamp()
        try {
            mediaPlayer?.run {
                if (isPlaying) stop()
                release()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to stop media player", error)
        }
        mediaPlayer = null
    }

    override fun onDestroy() {
        stopPlaybackOnly()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
        vibrator?.cancel()
        super.onDestroy()
    }
}
