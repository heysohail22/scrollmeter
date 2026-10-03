package com.scrollmeter.app.context

/**
 * Tunable thresholds and independently configurable signals for the ReelsContextDetector.
 * Allows measuring the impact of individual signals on detection accuracy.
 */
data class ReelsContextConfig(
    /** If true, bypasses auto-detection and forces REELS_ACTIVE for transition counting */
    var isForceReelsMode: Boolean = true,

    // --- Configurable Signal Toggles ---
    /** Signal 1: Evaluate vertical right-side action pillar (Like, Comment, Share, Bookmark) */
    var useRightActionColumn: Boolean = true,

    /** Signal 2: Evaluate bottom navigation Reels tab selection (Slot 2) when bottom nav is visible */
    var useBottomNavReelsIcon: Boolean = true,

    /** Signal 3: Reject Reels if bottom nav Home tab (Slot 1) is active/dominant */
    var useBottomNavHomeRejection: Boolean = true,

    /** Signal 4: Detect modal drag handle & scrim (Comments, Share sheets) */
    var useModalSheetDetection: Boolean = true,

    /** Signal 5: Reject Reels if top bar contains back arrow with non-reels headers (DMs, Profile, Posts) */
    var useHeaderBackArrowCheck: Boolean = true,

    // --- Thresholds ---
    /** Minimum score for the right-side vertical icon column (0.0 .. 1.0) */
    var actionColumnMinScore: Float = 0.28f,

    /** Minimum fill ratio of Home icon over Reels icon to trigger Home rejection */
    var homeNavDominanceRatio: Float = 1.15f,

    /** Minimum drag-handle score (0.0 .. 1.0) to declare a modal sheet open */
    var modalHandleMinScore: Float = 0.45f,

    /** Score above which the presence of top stories carousel or Home header rejects Reels */
    var storiesRejectionThreshold: Float = 0.60f,

    /** Confidence threshold to enter REELS_ACTIVE state */
    var activeConfidenceThreshold: Float = 0.38f,

    /** Confidence threshold below which context drops back to REELS_NOT_ACTIVE */
    var inactiveConfidenceThreshold: Float = 0.24f,

    /** Number of consecutive matching frames required to switch context mode (hysteresis) */
    var hysteresisFramesRequired: Int = 1
)
