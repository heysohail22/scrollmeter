package com.scrollmeter.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ReelRecord): Long

    @Query("UPDATE reel_records SET dwellTimeMs = :dwellTimeMs WHERE id = :id")
    suspend fun updateDwellTime(id: Long, dwellTimeMs: Long)

    @Query("SELECT COUNT(*) FROM reel_records WHERE dateString = :dateString")
    fun observeCountForDate(dateString: String): Flow<Int>

    @Query("SELECT COALESCE(SUM(dwellTimeMs), 0) FROM reel_records WHERE dateString = :dateString")
    fun observeTotalTimeForDate(dateString: String): Flow<Long>

    @Query("SELECT COALESCE(AVG(dwellTimeMs), 0) FROM reel_records WHERE dateString = :dateString")
    fun observeAvgTimeForDate(dateString: String): Flow<Double>

    @Query("SELECT COUNT(*) FROM reel_records")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT dateString as date, COUNT(*) as count, COALESCE(SUM(dwellTimeMs), 0) as totalDurationMs FROM reel_records GROUP BY dateString ORDER BY dateString DESC LIMIT 14")
    fun observeDailyStats(): Flow<List<DayStat>>

    @Query("SELECT * FROM reel_records WHERE dateString = :dateString ORDER BY timestamp DESC")
    fun observeReelsForDate(dateString: String): Flow<List<ReelRecord>>

    @Query("SELECT * FROM reel_records ORDER BY timestamp DESC LIMIT 25")
    fun observeRecentReels(): Flow<List<ReelRecord>>

    @Query("DELETE FROM reel_records")
    suspend fun clearAll()
}
