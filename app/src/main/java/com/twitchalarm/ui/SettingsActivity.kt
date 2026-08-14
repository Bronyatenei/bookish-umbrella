package com.twitchalarm.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.twitchalarm.R
import com.twitchalarm.databinding.ActivitySettingsBinding
import com.twitchalarm.work.MonitoringController

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    companion object {
        const val KEY_CHECK_INTERVAL = "check_interval_minutes"
        const val KEY_ALARM_VOLUME = "alarm_volume_percent"
        const val DEFAULT_INTERVAL = 5
        const val DEFAULT_VOLUME = 100
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

        // Интервал проверки
        val interval = prefs.getInt(KEY_CHECK_INTERVAL, DEFAULT_INTERVAL)
        binding.seekBarInterval.progress = interval - 1
        binding.tvIntervalValue.text = "$interval мин"

        // Громкость будильника
        val volumePercent = prefs.getInt(KEY_ALARM_VOLUME, DEFAULT_VOLUME)
        binding.seekBarVolume.progress = volumePercent
        binding.tvVolumeValue.text = "$volumePercent%"

        binding.tvStatus.text = "Проверка каждые $interval минут"
    }

    private fun setupListeners() {
        // Интервал проверки
        binding.seekBarInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = progress + 1
                binding.tvIntervalValue.text = "$minutes мин"
                if (fromUser) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsActivity)
                    prefs.edit().putInt(KEY_CHECK_INTERVAL, minutes).apply()
                    binding.tvStatus.text = "Проверка каждые $minutes минут"
                    // Активная служба увидит изменение настройки и пересчитает
                    // момент следующей проверки без запуска второго цикла.
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Громкость будильника
        binding.seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvVolumeValue.text = "$progress%"
                if (fromUser) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsActivity)
                    prefs.edit().putInt(KEY_ALARM_VOLUME, progress).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Кнопка "Тест звука"
        binding.btnTestSound.setOnClickListener {
            testAlarmSound()
        }

        // Кнопка "Проверить сейчас"
        binding.btnCheckNow.setOnClickListener {
            MonitoringController.checkNow(this)
            Toast.makeText(this, "🔄 Проверка запущена", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testAlarmSound() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val percent = prefs.getInt(KEY_ALARM_VOLUME, DEFAULT_VOLUME)

        // Показываем Toast только при тесте
        Toast.makeText(this, "🔊 Тест звука ($percent%)", Toast.LENGTH_SHORT).show()

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: throw IllegalStateException("На устройстве не настроен звук будильника")
            val volume = percent.coerceIn(0, 100) / 100f
            val player = MediaPlayer().apply {
                setAudioAttributes(audioAttrs)
                setDataSource(this@SettingsActivity, uri)
                setVolume(volume, volume)
                setOnPreparedListener { start() }
                prepareAsync()
            }
            Handler(Looper.getMainLooper()).postDelayed({
                player.stop()
                player.release()
            }, 2000)
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}