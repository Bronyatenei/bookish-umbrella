package com.twitchalarm.ui

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twitchalarm.R
import com.twitchalarm.data.AppDatabase
import com.twitchalarm.data.ScheduledAlarm
import com.twitchalarm.databinding.ActivityScheduledAlarmsBinding
import com.twitchalarm.databinding.DialogEditScheduledAlarmBinding
import com.twitchalarm.work.ScheduledAlarmDays
import com.twitchalarm.work.ScheduledAlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class ScheduledAlarmsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityScheduledAlarmsBinding
    private lateinit var database: AppDatabase
    private lateinit var adapter: ScheduledAlarmAdapter
    private var currentAlarms: List<ScheduledAlarm> = emptyList()
    private var pendingScrollRestore: ScrollRestore? = null
    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownUpdater = object : Runnable {
        override fun run() {
            updateNextAlarm(currentAlarms)
            countdownHandler.postDelayed(this, 30_000L)
        }
    }

    private data class ScrollRestore(val alarmId: Long, val topOffset: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduledAlarmsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        database = AppDatabase.getInstance(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = "Обычные будильники"

        adapter = ScheduledAlarmAdapter(
            onToggle = ::updateEnabled,
            onDelete = ::confirmDelete,
            onEdit = ::showEditDialog
        )
        binding.recyclerAlarms.layoutManager = LinearLayoutManager(this)
        binding.recyclerAlarms.adapter = adapter
        binding.btnAddTimeAlarm.setOnClickListener { showEditDialog(null) }
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_twitch) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
                true
            } else if (item.itemId == R.id.nav_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                finish()
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                true
            } else true
        }
        binding.bottomNavigation.selectedItemId = R.id.nav_alarms
        binding.btnGrantExactAlarm.setOnClickListener { requestExactAlarmAccess() }

        observeAlarms()
    }

    override fun onResume() {
        super.onResume()
        refreshExactAlarmAccess()
        countdownHandler.removeCallbacks(countdownUpdater)
        countdownHandler.post(countdownUpdater)
    }

    override fun onPause() {
        countdownHandler.removeCallbacks(countdownUpdater)
        super.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun observeAlarms() {
        lifecycleScope.launch {
            database.scheduledAlarmDao().getAllFlow().collect { alarms ->
                currentAlarms = alarms
                val restore = pendingScrollRestore
                pendingScrollRestore = null
                adapter.submitList(alarms) {
                    if (restore != null) {
                        val position = alarms.indexOfFirst { it.id == restore.alarmId }
                        if (position >= 0) {
                            (binding.recyclerAlarms.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(position, restore.topOffset)
                        }
                    }
                }
                binding.tvEmptyAlarms.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE
                updateNextAlarm(alarms)
            }
        }
    }

    private fun updateNextAlarm(alarms: List<ScheduledAlarm>) {
        val next = alarms.filter { it.enabled }
            .map { it to ScheduledAlarmScheduler.nextTriggerAt(it) }
            .minByOrNull { it.second }
        binding.tvNextAlarm.text = if (next == null) {
            "Нет включённых будильников"
        } else {
            val calendar = Calendar.getInstance().apply { timeInMillis = next.second }
            val now = Calendar.getInstance()
            val dayPrefix = when {
                calendar.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    calendar.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) -> "Сегодня"
                calendar.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    calendar.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) + 1 -> "Завтра"
                else -> ScheduledAlarmDays.ordered.firstOrNull { it.calendarDay == calendar.get(Calendar.DAY_OF_WEEK) }
                    ?.fullName ?: "Следующий"
            }
            "$dayPrefix в ${String.format("%02d:%02d", next.first.hour, next.first.minute)}, ${countdownText(next.second - System.currentTimeMillis())}"
        }
    }

    private fun countdownText(millis: Long): String {
        val totalMinutes = (millis.coerceAtLeast(0L) / 60_000L)
        if (totalMinutes < 1) return "менее минуты"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "через $hours ${hourWord(hours)} $minutes ${minuteWord(minutes)}"
            hours > 0 -> "через $hours ${hourWord(hours)}"
            else -> "через $minutes ${minuteWord(minutes)}"
        }
    }

    private fun hourWord(value: Long): String = when {
        value % 10 == 1L && value % 100 != 11L -> "час"
        value % 10 in 2..4 && value % 100 !in 12..14 -> "часа"
        else -> "часов"
    }

    private fun minuteWord(value: Long): String = when {
        value % 10 == 1L && value % 100 != 11L -> "минута"
        value % 10 in 2..4 && value % 100 !in 12..14 -> "минуты"
        else -> "минут"
    }

    private fun showEditDialog(existing: ScheduledAlarm?) {
        val dialogBinding = DialogEditScheduledAlarmBinding.inflate(layoutInflater)
        dialogBinding.timePicker.setIs24HourView(true)
        dialogBinding.timePicker.hour = existing?.hour ?: 7
        dialogBinding.timePicker.minute = existing?.minute ?: 0
        dialogBinding.etAlarmLabel.setText(existing?.label.orEmpty())

        val dayChipIds = listOf(
            R.id.chipMonday,
            R.id.chipTuesday,
            R.id.chipWednesday,
            R.id.chipThursday,
            R.id.chipFriday,
            R.id.chipSaturday,
            R.id.chipSunday
        )
        ScheduledAlarmDays.ordered.zip(dayChipIds).forEach { (day, chipId) ->
            dialogBinding.root.findViewById<com.google.android.material.chip.Chip>(chipId).isChecked =
                existing?.repeatDays?.and(ScheduledAlarmDays.bit(day.calendarDay)) != 0
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Новый будильник" else "Изменить будильник")
            .setView(dialogBinding.root)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val repeatDays = ScheduledAlarmDays.ordered.zip(dayChipIds).fold(0) { mask, (day, chipId) ->
                    if (dialogBinding.root.findViewById<com.google.android.material.chip.Chip>(chipId).isChecked) {
                        mask or ScheduledAlarmDays.bit(day.calendarDay)
                    } else {
                        mask
                    }
                }
                val edited = (existing ?: ScheduledAlarm(
                    hour = dialogBinding.timePicker.hour,
                    minute = dialogBinding.timePicker.minute
                )).copy(
                    hour = dialogBinding.timePicker.hour,
                    minute = dialogBinding.timePicker.minute,
                    label = dialogBinding.etAlarmLabel.text?.toString()?.trim().orEmpty(),
                    repeatDays = repeatDays
                )
                saveAlarm(existing, edited)
            }
            .show()
    }

    private fun saveAlarm(previous: ScheduledAlarm?, edited: ScheduledAlarm) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (previous != null) ScheduledAlarmScheduler.cancel(this@ScheduledAlarmsActivity, previous.id)
            val id = if (previous == null) {
                database.scheduledAlarmDao().insert(edited)
            } else {
                database.scheduledAlarmDao().update(edited)
                edited.id
            }
            ScheduledAlarmScheduler.schedule(this@ScheduledAlarmsActivity, edited.copy(id = id))
        }
    }

    private fun updateEnabled(alarm: ScheduledAlarm, enabled: Boolean) {
        if (!enabled) captureScrollBeforeDisable(alarm)
        lifecycleScope.launch(Dispatchers.IO) {
            val updated = alarm.copy(enabled = enabled)
            database.scheduledAlarmDao().update(updated)
            if (enabled) ScheduledAlarmScheduler.schedule(this@ScheduledAlarmsActivity, updated)
            else ScheduledAlarmScheduler.cancel(this@ScheduledAlarmsActivity, alarm.id)
        }
    }

    private fun captureScrollBeforeDisable(disablingAlarm: ScheduledAlarm) {
        val layoutManager = binding.recyclerAlarms.layoutManager as? LinearLayoutManager ?: return
        val firstPosition = layoutManager.findFirstVisibleItemPosition()
        if (firstPosition == RecyclerView.NO_POSITION) return
        val anchorPosition = if (currentAlarms.getOrNull(firstPosition)?.id == disablingAlarm.id) {
            firstPosition + 1
        } else {
            firstPosition
        }
        val anchor = currentAlarms.getOrNull(anchorPosition) ?: return
        pendingScrollRestore = ScrollRestore(
            alarmId = anchor.id,
            topOffset = layoutManager.findViewByPosition(anchorPosition)?.top
                ?: binding.recyclerAlarms.paddingTop
        )
    }

    private fun confirmDelete(alarm: ScheduledAlarm) {
        AlertDialog.Builder(this)
            .setTitle("Удалить будильник?")
            .setMessage("${String.format("%02d:%02d", alarm.hour, alarm.minute)} будет удалён.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    ScheduledAlarmScheduler.cancel(this@ScheduledAlarmsActivity, alarm.id)
                    database.scheduledAlarmDao().delete(alarm)
                }
            }
            .show()
    }

    private fun refreshExactAlarmAccess() {
        val granted = hasExactAlarmAccess()
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> {
                binding.tvExactAlarmStatus.text = "Точные будильники: отдельный доступ не нужен"
                binding.tvExactAlarmDescription.text =
                    "На этой версии Android будильники запускаются по системному расписанию без этого специального переключателя."
                binding.btnGrantExactAlarm.visibility = View.GONE
            }
            granted -> {
                binding.tvExactAlarmStatus.text = "Точные будильники: разрешены"
                binding.tvExactAlarmDescription.text =
                    "Android подтвердил доступ. Включённые будильники будут перепланированы на точное время."
                binding.btnGrantExactAlarm.visibility = View.GONE
            }
            else -> {
                binding.tvExactAlarmStatus.text = "Точные будильники: доступ не выдан"
                binding.tvExactAlarmDescription.text =
                    "Откройте системный доступ «Будильники и напоминания». Без него Android может отложить обычный будильник в режиме сна."
                binding.btnGrantExactAlarm.visibility = View.VISIBLE
            }
        }
        if (granted) {
            lifecycleScope.launch(Dispatchers.IO) {
                ScheduledAlarmScheduler.rescheduleAllEnabled(this@ScheduledAlarmsActivity)
            }
        }
    }

    private fun hasExactAlarmAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:$packageName")
        })
    }
}
