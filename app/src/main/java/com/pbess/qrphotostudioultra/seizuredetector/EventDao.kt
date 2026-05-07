package com.pbess.qrphotostudioultra.seizuredetector

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: EventEntity): Long

    @Update
    suspend fun updateEvent(event: EventEntity): Int

    @Query("SELECT * FROM events WHERE eventId = :eventId")
    suspend fun getEventById(eventId: String): EventEntity?

    @Query("SELECT * FROM events ORDER BY detectedAt DESC")
    fun observeAllEvents(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events ORDER BY detectedAt DESC LIMIT :limit")
    fun observeRecentEvents(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun getRowCount(): Int

    @Query("SELECT * FROM events ORDER BY detectedAt DESC LIMIT 1")
    suspend fun getLatestEvent(): EventEntity?
}
