package com.scrollmeter.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReelDao {

    // Reel Record queries
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

    @Query("SELECT COUNT(*) FROM reel_records WHERE dateString LIKE :monthPrefix || '%'")
    fun observeCountForMonth(monthPrefix: String): Flow<Int>

    @Query("SELECT COALESCE(SUM(dwellTimeMs), 0) FROM reel_records WHERE dateString LIKE :monthPrefix || '%'")
    fun observeTotalTimeForMonth(monthPrefix: String): Flow<Long>

    @Query("SELECT COUNT(*) FROM reel_records")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT dateString as date, COUNT(*) as count, COALESCE(SUM(dwellTimeMs), 0) as totalDurationMs FROM reel_records GROUP BY dateString ORDER BY dateString DESC LIMIT 31")
    fun observeDailyStats(): Flow<List<DayStat>>

    @Query("SELECT * FROM reel_records WHERE dateString = :dateString ORDER BY timestamp DESC")
    fun observeReelsForDate(dateString: String): Flow<List<ReelRecord>>

    @Query("SELECT * FROM reel_records WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeReelsForSession(sessionId: Long): Flow<List<ReelRecord>>

    // Session queries
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ReelSession): Long

    @Update
    suspend fun updateSession(session: ReelSession)

    @Query("SELECT * FROM reel_sessions ORDER BY startTime DESC")
    fun observeAllSessions(): Flow<List<ReelSession>>

    @Query("SELECT * FROM reel_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: Long): ReelSession?

    @Query("UPDATE reel_records SET caption = CASE WHEN (caption IS NULL OR caption = '') THEN :caption ELSE caption END, audioTrack = CASE WHEN (audioTrack IS NULL OR audioTrack = '') THEN :audioTrack ELSE audioTrack END WHERE id = :id")
    suspend fun updateMetadataIfEmpty(id: Long, caption: String, audioTrack: String)

    @Query("SELECT COUNT(*) FROM reel_records WHERE sessionId = :sessionId")
    suspend fun getSessionReelCount(sessionId: Long): Int

    @Query("SELECT COALESCE(SUM(dwellTimeMs), 0) FROM reel_records WHERE sessionId = :sessionId")
    suspend fun getSessionTotalDwell(sessionId: Long): Long

    @Query("UPDATE reel_sessions SET endTime = :endTime, totalReels = (SELECT COUNT(*) FROM reel_records WHERE sessionId = :sessionId), totalDurationMs = MAX(:endTime - startTime, (SELECT COALESCE(SUM(dwellTimeMs), 0) FROM reel_records WHERE sessionId = :sessionId)) WHERE id = :sessionId")
    suspend fun refreshSessionStats(sessionId: Long, endTime: Long)

    @Query("DELETE FROM reel_records")
    suspend fun clearAll()

    @Query("DELETE FROM reel_sessions")
    suspend fun clearAllSessions()

    @androidx.room.Transaction
    suspend fun clearAllData() {
        clearAll()
        clearAllSessions()
    }
}
