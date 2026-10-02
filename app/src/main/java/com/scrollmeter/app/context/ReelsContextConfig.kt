package com.scrollmeter.app.context

/**
 * Tunable thresholds for the ReelsContextDetector.
 */
data class ReelsContextConfig(
    /** If true, bypasses auto-detection and forces REELS_ACTIVE for testing transition counting */
    var isForceReelsMode: Boolean = false,

    /** Minimum score for the right-side vertical icon column (0.0 .. 1.0) */
    var actionColumnMinScore: Float = 0.25f,

    /** Score above which the presence of top stories carousel or Home header rejects Reels */
    var storiesRejectionThreshold: Float = 0.60f,

    /** Confidence threshold to enter REELS_ACTIVE state */
    var activeConfidenceThreshold: Float = 0.40f,

    /** Confidence threshold below which context drops back to REELS_NOT_ACTIVE */
    var inactiveConfidenceThreshold: Float = 0.25f,

    /** Number of consecutive matching frames required to switch context mode (hysteresis) */
    var hysteresisFramesRequired: Int = 1
)
