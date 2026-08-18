package com.twitchalarm.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface StreamerDao {

    @Query("SELECT * FROM streamers ORDER BY addedAt DESC")
    fun getAllFlow(): Flow<List<Streamer>>

    @Query("SELECT * FROM streamers")
    suspend fun getAll(): List<Streamer>

    @Query("SELECT * FROM streamers WHERE notifyEnabled = 1")
    suspend fun getEnabled(): List<Streamer>

    @Query("SELECT * FROM streamers WHERE login = :login LIMIT 1")
    suspend fun getByLogin(login: String): Streamer?

    @Query("UPDATE streamers SET notifyEnabled = :enabled")
    suspend fun setAllNotifyEnabled(enabled: Boolean)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(streamer: Streamer)

    @Update
    suspend fun update(streamer: Streamer)

    @Delete
    suspend fun delete(streamer: Streamer)

    @Query("""
        UPDATE streamers
        SET isLive = :isLive,
            streamTitle = :title,
            viewerCount = :viewers,
            gameName = :game,
            displayName = :displayName
        WHERE login = :login
    """)
    suspend fun updateLiveStatus(
        login: String,
        isLive: Boolean,
        title: String,
        viewers: Int,
        game: String,
        displayName: String
    )
}
