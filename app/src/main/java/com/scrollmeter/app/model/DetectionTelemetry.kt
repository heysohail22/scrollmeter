package com.scrollmeter.app.model

import com.scrollmeter.app.context.ReelsContextMode
import com.scrollmeter.app.detector.DetectorState

/**
 * Optical flow and vector dynamics computed from the central Region of Interest.
 */
data class FlowVector(
    val vx: Float = 0f,
    val vy: Float = 0f,
    val magnitude: Float = 0f,
    val consensus: Float = 0f,       // Percentage (0..1) of blocks agreeing on vertical direction
    val coherenceRatio: Float = 0f  // Ratio of vertical movement relative to horizontal noise
) {
    val directionText: String
        get() = when {
            magnitude < 1.0f -> "STATIC"
            vy > 2.0f -> "SWIPE UP (↓ visual flow)"
            vy < -2.0f -> "SWIPE DOWN (↑ visual flow)"
            else -> "LATERAL / DRIFT"
        }
}

/**
 * Real-time telemetry snapshot exposed to the debug UI and notification shade.
 */
data class DetectionTelemetry(
    val isCaptureActive: Boolean = false,
    val state: DetectorState = DetectorState.REELS_NOT_ACTIVE,
    val reelsContextMode: ReelsContextMode = ReelsContextMode.REELS_NOT_ACTIVE,
    val reelsConfidence: Float = 0f,
    val contextReason: String = "Evaluating context...",
    val hasActionColumn: Boolean = false,
    val hasStoriesHeader: Boolean = false,
    val sampleRateFps: Float = 0f,
    val frameDifferenceMad: Float = 0f,    // Mean Absolute Difference (0..1)
    val flow: FlowVector = FlowVector(),
    val dHashDistance: Int = 0,            // Hamming distance vs previous stable Reel (0..64)
    val similarityScore: Float = 100f,     // Visual similarity % (0..100)
    val candidateStatus: String = "Outside Reels",
    val verifiedReelCount: Int = 0,
    val recentLogs: List<String> = emptyList()
)
