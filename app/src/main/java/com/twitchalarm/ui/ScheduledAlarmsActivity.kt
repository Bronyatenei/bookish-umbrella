package com.twitchalarm.ui

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduledAlarmsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        database = AppDatabase.getInstance(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Обычные будильники"

        adapter = ScheduledAlarmAdapter(
            onToggle = ::updateEnabled,
            onDelete = ::confirmDelete,
            onEdit = ::showEditDialog
        )
        binding.recyclerAlarms.layoutManager = LinearLayoutManager(this)
        binding.recyclerAlarms.adapter = adapter
        binding.btnAddTimeAlarm.setOnClickListener { showEditDialog(null) }
        binding.btnGrantExactAlarm.setOnClickListener { requestExactAlarmAccess() }

        observeAlarms()
    }

    override fun onResume() {
        super.onResume()
        refreshExactAlarmAccess()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun observeAlarms() {
        lifecycleScope.launch {
            database.scheduledAlarmDao().getAllFlow().collect { alarms ->
                adapter.submitList(alarms)
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
            "$dayPrefix в ${String.format("%02d:%02d", next.first.hour, next.first.minute)}"
        }
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
        lifecycleScope.launch(Dispatchers.IO) {
            val updated = alarm.copy(enabled = enabled)
            database.scheduledAlarmDao().update(updated)
            if (enabled) ScheduledAlarmScheduler.schedule(this@ScheduledAlarmsActivity, updated)
            else ScheduledAlarmScheduler.cancel(this@ScheduledAlarmsActivity, alarm.id)
        }
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
        binding.cardExactAlarmPermission.visibility = if (granted) View.GONE else View.VISIBLE
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
