package com.scrollmeter.app.context

/**
 * State of Instagram Reels context detection.
 */
enum class ReelsContextMode {
    /** Outside Instagram Reels (e.g. Instagram Home feed, Explore grid, Profile, DMs, or other apps) */
    REELS_NOT_ACTIVE,

    /** Active inside Instagram Reels viewer (full-bleed video with right-side action column) */
    REELS_ACTIVE,

    /** Inside Reels, but a modal sheet (Comments, Share, or 3-dots menu) is covering the screen */
    REELS_MODAL_OPEN
}

/**
 * Detailed breakdown of visual signals detected for the current frame.
 */
data class ReelsContextSignals(
    val hasRightActionColumn: Boolean = false,
    val rightActionColumnScore: Float = 0f,
    val hasHomeFeedStoriesOrHeader: Boolean = false,
    val storiesHeaderScore: Float = 0f,
    val isFullBleedVideo: Boolean = false,
    val isModalSheetOpen: Boolean = false,
    val confidence: Float = 0f,
    val reason: String = "Evaluating screen..."
)
