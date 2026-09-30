package com.scrollmeter.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reel_records",
    indices = [Index(value = ["dateString"]), Index(value = ["timestamp"])]
)
data class ReelRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val dateString: String, // e.g. "2026-09-30"
    val timestamp: Long,    // epoch ms
    val creator: String,
    val dwellTimeMs: Long = 1000L // Time specifically spent watching this reel
)

data class DayStat(
    val date: String,
    val count: Int,
    val totalDurationMs: Long = 0L
)
