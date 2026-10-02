package com.scrollmeter.app.detector

/**
 * States of the Reel transition detection state machine.
 * Strictly enforces that Reel transitions are only detected when REELS_ACTIVE.
 */
enum class DetectorState {
    /** Screen capture inactive or app paused */
    INACTIVE,

    /** User is outside Reels (Home feed, Explore, Profile, DMs, outside Instagram) */
    REELS_NOT_ACTIVE,

    /** Inside Reels viewer; preparing transition detector */
    REELS_ACTIVE,

    /** Current Reel is visible, stable, and user is watching */
    REEL_VISIBLE,

    /** Page swipe transition in progress between Reels */
    TRANSITIONING,

    /** Visual swipe has settled; candidate Reel layout captured */
    CANDIDATE_REEL,

    /** Dwell timer verifying candidate stability */
    VERIFYING,

    /** New Reel verified! Count incremented. */
    VERIFIED_REEL
}

/**
 * Event emitted when a transition occurs or completes.
 */
sealed class ReelDetectionEvent {
    data class ContextChanged(val isReelsActive: Boolean, val reason: String) : ReelDetectionEvent()
    data class MotionDetected(val vy: Float, val consensus: Float) : ReelDetectionEvent()
    data class CandidateFound(val dHashDistance: Int, val similarity: Float) : ReelDetectionEvent()
    data class CandidateRejected(val reason: String) : ReelDetectionEvent()
    data class ReelVerified(val newTotal: Int, val dHashDistance: Int, val durationMs: Long) : ReelDetectionEvent()
}
