package com.twitchalarm.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.twitchalarm.databinding.ActivityAlarmBinding
import com.twitchalarm.work.AlarmPlaybackService

/**
 * Полноэкранный интерфейс тревоги. Воспроизведение намеренно находится в
 * AlarmPlaybackService, поэтому закрытие или сворачивание Activity не выключает звук.
 */
class AlarmActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_STREAMER = "streamer_name"
        const val EXTRA_TITLE = "stream_title"
        const val EXTRA_GAME = "stream_game"
        const val EXTRA_VIEWERS = "viewer_count"
    }

    private lateinit var binding: ActivityAlarmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreen()
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val streamer = intent.getStringExtra(EXTRA_STREAMER) ?: "Стример"
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val game = intent.getStringExtra(EXTRA_GAME).orEmpty()
        val viewers = intent.getIntExtra(EXTRA_VIEWERS, 0)
        bindUi(streamer, title, game, viewers)
    }

    private fun configureLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
    }

    private fun bindUi(streamer: String, title: String, game: String, viewers: Int) {
        binding.tvStreamerName.text = streamer
        binding.tvLiveBadge.text = "В ЭФИРЕ"
        binding.tvStreamTitle.text = title.ifBlank { "Начался стрим!" }
        binding.tvMeta.text = listOfNotNull(
            game.takeIf { it.isNotBlank() },
            viewers.takeIf { it > 0 }?.let { formatViewers(it) }
        ).joinToString(" · ")

        binding.btnDismiss.setOnClickListener {
            AlarmPlaybackService.stop(this)
            finish()
        }
        binding.btnWatch.setOnClickListener {
            AlarmPlaybackService.stop(this)
            openTwitch(streamer)
            finish()
        }
    }

    private fun formatViewers(count: Int): String = when {
        count >= 1_000_000 -> "${count / 1_000_000}M зрителей"
        count >= 1_000 -> "${count / 1_000}K зрителей"
        else -> "$count зрителей"
    }

    private fun openTwitch(streamer: String) {
        val twitchIntent = packageManager.getLaunchIntentForPackage("tv.twitch.android.app")
        if (twitchIntent != null) {
            startActivity(twitchIntent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.twitch.tv/$streamer")))
        }
    }

    override fun onBackPressed() {
        // Тревогу можно завершить только явной кнопкой, чтобы не отключить её случайно.
    }
}
