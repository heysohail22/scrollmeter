package com.scrollmeter.app.detector

/**
 * Tunable thresholds and timing parameters for the computer vision detection engine.
 * Made mutable / reactive so they can be benchmarked and adjusted directly from the debug UI.
 */
data class DetectionConfig(
    /** Target frame capture rate (FPS) */
    val targetFps: Int = 8,

    /** Downscaled virtual display width */
    val downsampleWidth: Int = 180,

    /** Downscaled virtual display height */
    val downsampleHeight: Int = 320,

    /** Minimum Mean Absolute Difference (0.01..0.20) to trigger optical flow calculation */
    var minMotionDifferenceThreshold: Float = 0.035f,

    /** Minimum vertical block flow magnitude (pixels in downscaled frame) to declare a swipe */
    var swipeFlowThreshold: Float = 3.2f,

    /** Minimum percentage of blocks (0.50..0.95) that must agree on the vertical direction */
    var motionConsensusThreshold: Float = 0.55f,

    /** Flow magnitude below which the screen is considered settling/stationary */
    var settlingFlowThreshold: Float = 1.5f,

    /** Minimum duration (ms) the new screen must remain stable before confirming verified reel */
    var settlingDwellMs: Long = 350L,

    /** Minimum Hamming distance (out of 64 bits) between dHash of previous and new Reel */
    var minDHashHammingDistance: Int = 14,

    /** Maximum allowed Hamming distance to consider an aborted swipe "the same Reel" */
    var sameReelBounceDistanceMax: Int = 6
)
