package com.twitchalarm.ui

import android.content.ClipData
import android.app.AlarmManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.firebase.messaging.FirebaseMessaging
import com.twitchalarm.R
import com.twitchalarm.databinding.ActivitySettingsBinding
import com.twitchalarm.work.AlarmSoundPreferences
import com.twitchalarm.work.MonitoringController
import com.twitchalarm.work.FcmTokenStore
import com.twitchalarm.work.MonitoringStrategy
import com.twitchalarm.work.HomeAgentWatchdog

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
        val mergedUris = (previousUris + readableUris).distinct()
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putStringSet(KEY_ALARM_PLAYLIST, mergedUris.map(Uri::toString).toSet())
            .apply()
        updatePlaylistSummary()
        val addedCount = mergedUris.size - previousUris.size
        Toast.makeText(this, "Добавлено: $addedCount, всего: ${mergedUris.size}", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val KEY_CHECK_INTERVAL = "check_interval_minutes"
        const val KEY_ALARM_VOLUME = "alarm_volume_percent"
        const val KEY_MONITORING_STRATEGY = "monitoring_strategy"
        const val KEY_ALARM_PLAYLIST = "alarm_playlist_uris"
        const val KEY_ALARM_FADE_SECONDS = "alarm_fade_seconds"
        const val KEY_HOME_AGENT_WATCHDOG_INTERVAL = "home_agent_watchdog_interval_minutes"
        const val KEY_HOME_AGENT_MISSED_HEARTBEATS = "home_agent_missed_heartbeats"
        const val KEY_HOME_AGENT_FALLBACK_STRATEGY = "home_agent_fallback_strategy"
        const val KEY_HOME_AGENT_AUTO_RETURN = "home_agent_auto_return"
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
        refreshFcmToken()
    }

    override fun onResume() {
        super.onResume()
        updateHomeAgentControls()
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
            when (strategy) {
                MonitoringStrategy.RELIABLE -> R.id.rbReliable
                MonitoringStrategy.ECONOMY -> R.id.rbEconomy
                MonitoringStrategy.HOME_AGENT -> R.id.rbHomeAgent
            }
        )

        val watchdogMinutes = HomeAgentWatchdog.intervalMinutes(this)
        binding.seekBarHomeAgentWatchdog.progress = watchdogProgress(watchdogMinutes)
        binding.tvHomeAgentWatchdogValue.text = "$watchdogMinutes мин"
        binding.etMissedHeartbeats.setText(HomeAgentWatchdog.missedHeartbeats(this).toString())
        binding.spinnerHomeAgentFallback.setSelection(
            if (HomeAgentWatchdog.fallbackStrategy(this) == MonitoringStrategy.RELIABLE) 0 else 1
        )
        binding.switchHomeAgentAutoReturn.isChecked = HomeAgentWatchdog.autoReturnEnabled(this)
        updateHomeAgentControls(strategy)
        updatePlaylistSummary()
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
                R.id.rbHomeAgent -> MonitoringStrategy.HOME_AGENT
                else -> MonitoringStrategy.RELIABLE
            }
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(KEY_MONITORING_STRATEGY, strategy.storedValue)
                .apply()
            updateHomeAgentControls(strategy)
            MonitoringController.reconfigure(this)
        }

        binding.seekBarHomeAgentWatchdog.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = watchdogMinutesFromProgress(progress)
                binding.tvHomeAgentWatchdogValue.text = "$minutes мин"
                if (fromUser) {
                    PreferenceManager.getDefaultSharedPreferences(this@SettingsActivity)
                        .edit()
                        .putInt(KEY_HOME_AGENT_WATCHDOG_INTERVAL, minutes)
                        .apply()
                    if (selectedStrategy() == MonitoringStrategy.HOME_AGENT) HomeAgentWatchdog.schedule(this@SettingsActivity)
                    updateHomeAgentStatus()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.etMissedHeartbeats.doAfterTextChanged { text ->
            val count = text?.toString()?.toIntOrNull()?.coerceIn(1, 9) ?: return@doAfterTextChanged
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putInt(KEY_HOME_AGENT_MISSED_HEARTBEATS, count)
                .apply()
            if (selectedStrategy() == MonitoringStrategy.HOME_AGENT) HomeAgentWatchdog.schedule(this)
            updateHomeAgentStatus()
        }

        binding.spinnerHomeAgentFallback.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val fallback = if (position == 0) MonitoringStrategy.RELIABLE else MonitoringStrategy.ECONOMY
                PreferenceManager.getDefaultSharedPreferences(this@SettingsActivity)
                    .edit()
                    .putString(KEY_HOME_AGENT_FALLBACK_STRATEGY, fallback.storedValue)
                    .apply()
                if (selectedStrategy() == MonitoringStrategy.HOME_AGENT && HomeAgentWatchdog.isFallbackActive(this@SettingsActivity)) {
                    MonitoringController.startHomeAgentFallback(this@SettingsActivity, fallback)
                }
                updateHomeAgentStatus()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.switchHomeAgentAutoReturn.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean(KEY_HOME_AGENT_AUTO_RETURN, checked)
                .apply()
            updateHomeAgentStatus()
        }

        binding.btnReturnToHomeAgent.setOnClickListener {
            HomeAgentWatchdog.returnToHomeAgent(this)
            updateHomeAgentStatus()
        }

        binding.btnGrantHomeAgentExactAlarm.setOnClickListener { requestExactAlarmAccess() }

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
        binding.btnCopyFcmToken.setOnClickListener { copyFcmToken() }
    }

    private fun refreshFcmToken() {
        val cached = FcmTokenStore.get(this)
        if (cached != null) updateFcmTokenSummary(cached)
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                FcmTokenStore.save(this, token)
                updateFcmTokenSummary(token)
            }
            .addOnFailureListener {
                if (cached == null) binding.tvFcmTokenSummary.text = "FCM-токен пока недоступен"
            }
    }

    private fun updateFcmTokenSummary(token: String) {
        binding.tvFcmTokenSummary.text = "FCM-токен готов: ${token.take(8)}…${token.takeLast(6)}"
    }

    private fun copyFcmToken() {
        val token = FcmTokenStore.get(this)
        if (token == null) {
            Toast.makeText(this, "FCM-токен ещё не получен", Toast.LENGTH_SHORT).show()
            refreshFcmToken()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FCM token", token))
        Toast.makeText(this, "FCM-токен скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun updateHomeAgentControls(strategy: MonitoringStrategy = selectedStrategy()) {
        val visible = strategy == MonitoringStrategy.HOME_AGENT
        binding.homeAgentSettingsContainer.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnGrantHomeAgentExactAlarm.visibility = if (visible && !canScheduleExactAlarms()) View.VISIBLE else View.GONE
        if (visible) updateHomeAgentStatus()
    }

    private fun updateHomeAgentStatus() {
        if (selectedStrategy() != MonitoringStrategy.HOME_AGENT) return
        val fallback = HomeAgentWatchdog.isFallbackActive(this)
        val lastHeartbeat = HomeAgentWatchdog.lastHeartbeatAt(this)
        val strategyName = when (HomeAgentWatchdog.fallbackStrategy(this)) {
            MonitoringStrategy.RELIABLE -> "Надёжный режим"
            else -> "Экономия"
        }
        binding.tvHomeAgentStatus.text = when {
            fallback && HomeAgentWatchdog.autoReturnEnabled(this) ->
                "Резервный режим: $strategyName. Ожидание двух heartbeat для возврата."
            fallback -> "Резервный режим: $strategyName. Вернитесь вручную после восстановления ПК."
            lastHeartbeat == 0L -> "Ожидание первого heartbeat от ПК"
            else -> "Home Agent на связи: heartbeat ${formatHeartbeatAge(lastHeartbeat)}"
        }
        binding.btnReturnToHomeAgent.visibility = if (fallback && !HomeAgentWatchdog.autoReturnEnabled(this)) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun formatHeartbeatAge(timestamp: Long): String {
        val seconds = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 1_000L)
        return when {
            seconds < 60L -> "только что"
            seconds < 120L -> "1 минуту назад"
            else -> "${seconds / 60L} мин назад"
        }
    }

    private fun watchdogMinutesFromProgress(progress: Int): Int = (progress.coerceIn(0, 4) + 1) * 5

    private fun watchdogProgress(minutes: Int): Int = ((minutes.coerceIn(5, 25) / 5) - 1)

    private fun selectedStrategy(): MonitoringStrategy = MonitoringStrategy.fromStoredValue(
        PreferenceManager.getDefaultSharedPreferences(this).getString(
            KEY_MONITORING_STRATEGY,
            MonitoringStrategy.RELIABLE.storedValue
        )
    )

    private fun currentInterval(): Int = PreferenceManager.getDefaultSharedPreferences(this)
        .getInt(KEY_CHECK_INTERVAL, DEFAULT_INTERVAL)
        .coerceIn(1, 15)


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


    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
