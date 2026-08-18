package com.twitchalarm.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-configured local alarm. repeatDays uses Calendar day-of-week bits; 0 means one-time. */
@Entity(tableName = "scheduled_alarms")
data class ScheduledAlarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val repeatDays: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
