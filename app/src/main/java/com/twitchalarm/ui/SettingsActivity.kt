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
import com.twitchalarm.work.EconomyCheckReceiver
import com.twitchalarm.work.MonitoringController
import com.twitchalarm.work.MonitoringStrategy

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    companion object {
        const val KEY_CHECK_INTERVAL = "check_interval_minutes"
        const val KEY_ALARM_VOLUME = "alarm_volume_percent"
        const val KEY_MONITORING_STRATEGY = "monitoring_strategy"
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
        val interval = prefs.getInt(KEY_CHECK_INTERVAL, DEFAULT_INTERVAL).coerceIn(1, 15)
        val volumePercent = prefs.getInt(KEY_ALARM_VOLUME, DEFAULT_VOLUME).coerceIn(0, 100)
        val strategy = MonitoringStrategy.fromStoredValue(
            prefs.getString(KEY_MONITORING_STRATEGY, MonitoringStrategy.RELIABLE.storedValue)
        )

        binding.seekBarInterval.progress = interval - 1
        binding.tvIntervalValue.text = "$interval мин"
        binding.seekBarVolume.progress = volumePercent
        binding.tvVolumeValue.text = "$volumePercent%"
        binding.rgMonitoringStrategy.check(
            if (strategy == MonitoringStrategy.RELIABLE) R.id.rbReliable else R.id.rbEconomy
        )
        updateMonitoringStatus(interval, strategy)
    }

    private fun setupListeners() {
        binding.seekBarInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = progress + 1
                binding.tvIntervalValue.text = "$minutes мин"
                if (fromUser) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this@SettingsActivity)
                    prefs.edit().putInt(KEY_CHECK_INTERVAL, minutes).apply()
                    val strategy = selectedStrategy()
                    updateMonitoringStatus(minutes, strategy)
                    // В непрерывном режиме служба пересчитает ожидание сама.
                    // Экономичному режиму нужно отменить прежний alarm и поставить новый.
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

    private fun testAlarmSound() {
        val percent = PreferenceManager.getDefaultSharedPreferences(this)
            .getInt(KEY_ALARM_VOLUME, DEFAULT_VOLUME)
            .coerceIn(0, 100)
        Toast.makeText(this, "Тест звука ($percent%)", Toast.LENGTH_SHORT).show()

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: throw IllegalStateException("На устройстве не настроен звук будильника")
            val volume = percent / 100f
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
        } catch (error: Exception) {
            Toast.makeText(this, "Ошибка: ${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
