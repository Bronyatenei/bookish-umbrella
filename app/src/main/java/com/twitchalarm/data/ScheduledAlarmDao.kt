package com.twitchalarm.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledAlarmDao {
    @Query("SELECT * FROM scheduled_alarms ORDER BY hour ASC, minute ASC, id ASC")
    fun getAllFlow(): Flow<List<ScheduledAlarm>>

    @Query("SELECT * FROM scheduled_alarms WHERE enabled = 1")
    suspend fun getEnabled(): List<ScheduledAlarm>

    @Query("SELECT * FROM scheduled_alarms WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ScheduledAlarm?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alarm: ScheduledAlarm): Long

    @Update
    suspend fun update(alarm: ScheduledAlarm)

    @Delete
    suspend fun delete(alarm: ScheduledAlarm)
}
