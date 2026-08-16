package com.twitchalarm.ui

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.twitchalarm.R
import com.twitchalarm.databinding.ActivitySettingsBinding
import com.twitchalarm.work.AlarmSoundPreferences
import com.twitchalarm.work.EconomyCheckReceiver
import com.twitchalarm.work.MonitoringController
import com.twitchalarm.work.MonitoringStrategy

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val playlistPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val readableUris = uris.filter { uri -> persistReadPermission(uri) }
        if (readableUris.isEmpty()) {
            Toast.makeText(this, "Не удалось сохранить доступ к выбранным трекам", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val previousUris = AlarmSoundPreferences.playlist(this)
        previousUris.filterNot { it in readableUris }.forEach { uri ->
            runCatching {
                contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putStringSet(KEY_ALARM_PLAYLIST, readableUris.map(Uri::toString).toSet())
            .apply()
        updatePlaylistSummary()
        Toast.makeText(this, "Плейлист обновлён: ${readableUris.size}", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val KEY_CHECK_INTERVAL = "check_interval_minutes"
        const val KEY_ALARM_VOLUME = "alarm_volume_percent"
        const val KEY_MONITORING_STRATEGY = "monitoring_strategy"
        const val KEY_ALARM_PLAYLIST = "alarm_playlist_uris"
        const val KEY_ALARM_FADE_SECONDS = "alarm_fade_seconds"
        const val DEFAULT_INTERVAL = 5
        const val DEFAULT_VOLUME = 100
        const val DEFAULT_FADE_SECONDS = 30
        const val MAX_FADE_SECONDS = 120
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadSettings()
        setupListeners()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Настройки"
    }

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val interval = prefs.getInt(KEY_CHECK_INTERVAL, DEFAULT_INTERVAL).coerceIn(1, 15)
        val volumePercent = prefs.getInt(KEY_ALARM_VOLUME, DEFAULT_VOLUME).coerceIn(0, 100)
        val fadeSeconds = prefs.getInt(KEY_ALARM_FADE_SECONDS, DEFAULT_FADE_SECONDS)
            .coerceIn(0, MAX_FADE_SECONDS)
        val strategy = MonitoringStrategy.fromStoredValue(
            prefs.getString(KEY_MONITORING_STRATEGY, MonitoringStrategy.RELIABLE.storedValue)
        )

        binding.seekBarInterval.progress = interval - 1
        binding.tvIntervalValue.text = "$interval мин"
        binding.seekBarVolume.progress = volumePercent
        binding.tvVolumeValue.text = "$volumePercent%"
        binding.seekBarFade.progress = fadeSeconds
        binding.tvFadeValue.text = formatFadeSeconds(fadeSeconds)
        binding.rgMonitoringStrategy.check(
            if (strategy == MonitoringStrategy.RELIABLE) R.id.rbReliable else R.id.rbEconomy
        )
        updatePlaylistSummary()
        updateMonitoringStatus(interval, strategy)
    }

    private fun setupListeners() {
        binding.seekBarInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = progress + 1
                binding.tvIntervalValue.text = "$minutes мин"
                if (fromUser) {
                    PreferenceManager.getDefaultSharedPreferences(this@SettingsActivity)
                        .edit()
                        .putInt(KEY_CHECK_INTERVAL, minutes)
                        .apply()
                    val strategy = selectedStrategy()
                    updateMonitoringStatus(minutes, strategy)
                    if (strategy == MonitoringStrategy.ECONOMY) {
                        MonitoringController.reconfigure(this@SettingsActivity)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.rgMonitoringStrategy.setOnCheckedChangeListener { _, checkedId ->
            val strategy = when (checkedId) {
                R.id.rbEconomy -> MonitoringStrategy.ECONOMY
                else -> MonitoringStrategy.RELIABLE
            }
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(KEY_MONITORING_STRATEGY, strategy.storedValue)
                .apply()
            updateMonitoringStatus(currentInterval(), strategy)
            MonitoringController.reconfigure(this)
        }

        binding.seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvVolumeValue.text = "$progress%"
                if (fromUser) {
                    PreferenceManager.getDefaultSharedPreferences(this@SettingsActivity)
                        .edit()
                        .putInt(KEY_ALARM_VOLUME, progress)
                        .apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.seekBarFade.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvFadeValue.text = formatFadeSeconds(progress)
                if (fromUser) {
                    PreferenceManager.getDefaultSharedPreferences(this@SettingsActivity)
                        .edit()
                        .putInt(KEY_ALARM_FADE_SECONDS, progress)
                        .apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.btnChooseTracks.setOnClickListener { playlistPicker.launch(arrayOf("audio/*")) }
        binding.btnClearTracks.setOnClickListener { clearPlaylist() }
        binding.btnTestSound.setOnClickListener { testAlarmSound() }
        binding.btnCheckNow.setOnClickListener {
            MonitoringController.checkNow(this)
            Toast.makeText(this, "Проверка запущена", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectedStrategy(): MonitoringStrategy = MonitoringStrategy.fromStoredValue(
        PreferenceManager.getDefaultSharedPreferences(this).getString(
            KEY_MONITORING_STRATEGY,
            MonitoringStrategy.RELIABLE.storedValue
        )
    )

    private fun currentInterval(): Int = PreferenceManager.getDefaultSharedPreferences(this)
        .getInt(KEY_CHECK_INTERVAL, DEFAULT_INTERVAL)
        .coerceIn(1, 15)

    private fun updateMonitoringStatus(interval: Int, strategy: MonitoringStrategy) {
        binding.tvStatus.text = when (strategy) {
            MonitoringStrategy.RELIABLE -> "Стабильный режим: проверка каждые $interval мин"
            MonitoringStrategy.ECONOMY -> {
                val effective = EconomyCheckReceiver.readEffectiveIntervalMinutes(this)
                "Экономичный режим: следующая проверка через $effective мин"
            }
        }
    }

    private fun persistReadPermission(uri: Uri): Boolean = try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    }

    private fun clearPlaylist() {
        val current = AlarmSoundPreferences.playlist(this)
        current.forEach { uri ->
            runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .remove(KEY_ALARM_PLAYLIST)
            .apply()
        updatePlaylistSummary()
    }

    private fun updatePlaylistSummary() {
        val tracks = AlarmSoundPreferences.playlist(this)
        binding.tvPlaylistSummary.text = when (tracks.size) {
            0 -> "Не выбран: будет использован системный звук будильника"
            1 -> "Выбран 1 трек: ${displayName(tracks.first())}"
            else -> "Выбрано ${tracks.size} трека: ${tracks.take(2).joinToString { displayName(it) }}…"
        }
        binding.btnClearTracks.isEnabled = tracks.isNotEmpty()
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)
            else null
        }
    }.getOrNull() ?: "аудиофайл"

    private fun formatFadeSeconds(seconds: Int): String = when (seconds) {
        0 -> "Сразу"
        1 -> "1 сек"
        else -> "$seconds сек"
    }

    private fun testAlarmSound() {
        val percent = PreferenceManager.getDefaultSharedPreferences(this)
            .getInt(KEY_ALARM_VOLUME, DEFAULT_VOLUME)
            .coerceIn(0, 100)
        val uri = AlarmSoundPreferences.randomPlaylistUri(this)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: run {
                Toast.makeText(this, "На устройстве не настроен звук будильника", Toast.LENGTH_SHORT).show()
                return
            }
        Toast.makeText(this, "Тест выбранной мелодии ($percent%)", Toast.LENGTH_SHORT).show()

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(audioAttrs)
                setDataSource(this@SettingsActivity, uri)
                setVolume(percent / 100f, percent / 100f)
                setOnPreparedListener { start() }
                prepareAsync()
            }
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { player.stop() }
                player.release()
            }, 2_000)
        } catch (error: Exception) {
            Toast.makeText(this, "Не удалось открыть трек: ${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
