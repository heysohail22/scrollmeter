package com.scrollmeter.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reel_sessions",
    indices = [Index(value = ["dateString"]), Index(value = ["startTime"])]
)
data class ReelSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val dateString: String,      // e.g. "2026-10-01"
    val startTime: Long,         // epoch ms
    val endTime: Long,           // epoch ms
    val totalReels: Int = 0,
    val totalDurationMs: Long = 0L
)

@Entity(
    tableName = "reel_records",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["dateString"]),
        Index(value = ["timestamp"])
    ]
)
data class ReelRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long = 0L,    // FK to ReelSession
    val dateString: String,      // e.g. "2026-10-01"
    val timestamp: Long,         // epoch ms
    val creator: String,         // Page / Creator name
    val caption: String = "",    // Caption description
    val audioTrack: String = "", // Song / Audio title
    val dwellTimeMs: Long = 1000L
)

data class DayStat(
    val date: String,
    val count: Int,
    val totalDurationMs: Long = 0L
)
