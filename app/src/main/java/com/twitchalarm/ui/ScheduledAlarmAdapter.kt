package com.twitchalarm.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.twitchalarm.data.ScheduledAlarm
import com.twitchalarm.databinding.ItemScheduledAlarmBinding
import com.twitchalarm.work.ScheduledAlarmDays

class ScheduledAlarmAdapter(
    private val onToggle: (ScheduledAlarm, Boolean) -> Unit,
    private val onDelete: (ScheduledAlarm) -> Unit,
    private val onEdit: (ScheduledAlarm) -> Unit
) : ListAdapter<ScheduledAlarm, ScheduledAlarmAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemScheduledAlarmBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemScheduledAlarmBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(alarm: ScheduledAlarm) {
            binding.tvAlarmTime.text = String.format("%02d:%02d", alarm.hour, alarm.minute)
            binding.tvAlarmLabel.text = alarm.label.ifBlank { "Будильник" }
            binding.tvAlarmDays.text = ScheduledAlarmDays.format(alarm.repeatDays)
            binding.switchAlarmEnabled.setOnCheckedChangeListener(null)
            binding.switchAlarmEnabled.isChecked = alarm.enabled
            binding.switchAlarmEnabled.setOnCheckedChangeListener { _, enabled ->
                if (enabled != alarm.enabled) onToggle(alarm, enabled)
            }
            binding.btnDeleteAlarm.setOnClickListener { onDelete(alarm) }
            binding.root.setOnClickListener { onEdit(alarm) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScheduledAlarm>() {
            override fun areItemsTheSame(oldItem: ScheduledAlarm, newItem: ScheduledAlarm) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ScheduledAlarm, newItem: ScheduledAlarm) =
                oldItem == newItem
        }
    }
}
