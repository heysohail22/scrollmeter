package com.scrollmeter.app.detector

import android.util.Log
import com.scrollmeter.app.context.ReelsContextMode
import com.scrollmeter.app.model.FlowVector
import com.scrollmeter.app.vision.FrameAnalysisResult
import com.scrollmeter.app.vision.PerceptualHashAnalyzer
import kotlin.math.abs

/**
 * Robust state machine that enforces Reels-only transition verification.
 * 
 * Guarantees that:
 * 1. Scrolling on Instagram Home feed NEVER increments the Reel counter.
 * 2. Opening comments, share sheets, or profile does NOT trigger transitions.
 * 3. Only verified transitions between two distinct Reels in REELS_ACTIVE increment the count.
 */
class ReelDetectionStateMachine(
    private val config: DetectionConfig,
    private val hashAnalyzer: PerceptualHashAnalyzer,
    private val onEvent: (ReelDetectionEvent) -> Unit
) {
    companion object {
        private const val TAG = "ReelStateMachine"
        private const val MIN_REEL_COOLDOWN_MS = 1500L // Minimum 1.5 seconds between verified reels
    }

    var currentState: DetectorState = DetectorState.REELS_NOT_ACTIVE
        private set

    var totalReelCount: Int = 0
        private set

    var referenceStableHash: Long = 0L
        private set

    private var preTransitionHash: Long = 0L
    private var candidateHash: Long = 0L
    private var candidateTimestamp: Long = 0L
    private var transitionStartTime: Long = 0L
    private var lastCandidateStatus: String = "Outside Reels"
    private var lastVerifiedReelTimestamp: Long = 0L

    fun getCandidateStatus(): String = lastCandidateStatus

    /**
     * Feeds visual frame analysis and current Reels context into the state machine.
     */
    fun processFrame(
        analysis: FrameAnalysisResult,
        contextMode: ReelsContextMode,
        timestampMs: Long
    ) {
        val flow = analysis.flow
        val currentHash = analysis.currentDHash

        // RULE 1: If outside Reels (Home feed, Explore, Profile, DMs, other apps)
        // Transition counting MUST NEVER RUN outside Reels.
        if (contextMode == ReelsContextMode.REELS_NOT_ACTIVE) {
            // If actively transitioning, allow the swipe up to 1200ms to complete and settle
            if (currentState == DetectorState.TRANSITIONING || currentState == DetectorState.CANDIDATE_REEL || currentState == DetectorState.VERIFYING) {
                if (timestampMs - transitionStartTime >= 1200L) {
                    Log.i(TAG, "Transition timed out outside Reels -> REELS_NOT_ACTIVE")
                    currentState = DetectorState.REELS_NOT_ACTIVE
                    lastCandidateStatus = "Outside Reels"
                    onEvent(ReelDetectionEvent.ContextChanged(false, "Exited Reels viewer"))
                    return
                }
            } else {
                if (currentState != DetectorState.REELS_NOT_ACTIVE && currentState != DetectorState.INACTIVE) {
                    Log.i(TAG, "Exited Reels viewer -> REELS_NOT_ACTIVE (Transition tracking halted)")
                    currentState = DetectorState.REELS_NOT_ACTIVE
                    lastCandidateStatus = "Outside Reels (Home feed / other)"
                    onEvent(ReelDetectionEvent.ContextChanged(false, "Exited Reels viewer"))
                }
                return
            }
        }

        // RULE 2: If inside Reels, but Comment or Share modal sheet is open
        // Inhibit transition counting and cancel any candidate.
        if (contextMode == ReelsContextMode.REELS_MODAL_OPEN) {
            if (currentState == DetectorState.TRANSITIONING || currentState == DetectorState.CANDIDATE_REEL) {
                Log.i(TAG, "Comment/Share modal opened -> Invalidate candidate and remain on current Reel")
                currentState = DetectorState.REEL_VISIBLE
                lastCandidateStatus = "Comments / Share open (transitions inhibited)"
            }
            return
        }

        // RULE 3: User is in REELS_ACTIVE
        // Transition from NOT_ACTIVE to REELS_ACTIVE -> REEL_VISIBLE
        if (currentState == DetectorState.REELS_NOT_ACTIVE || currentState == DetectorState.INACTIVE) {
            Log.i(TAG, "Entered Reels viewer -> REELS_ACTIVE")
            currentState = DetectorState.REELS_ACTIVE
            onEvent(ReelDetectionEvent.ContextChanged(true, "Entered Reels viewer"))

            // Initialize active reel reference
            referenceStableHash = currentHash
            preTransitionHash = currentHash
            currentState = DetectorState.REEL_VISIBLE
            lastCandidateStatus = "Watching Reel (Reference initialized)"
            return
        }

        when (currentState) {
            DetectorState.INACTIVE, DetectorState.REELS_NOT_ACTIVE -> {
                // Handled above
            }

            DetectorState.REELS_ACTIVE -> {
                referenceStableHash = currentHash
                preTransitionHash = currentHash
                currentState = DetectorState.REEL_VISIBLE
                lastCandidateStatus = "Watching Reel"
            }

            DetectorState.REEL_VISIBLE -> {
                // Monitor for coherent vertical swipe transition
                if (isCoherentVerticalSwipe(flow)) {
                    preTransitionHash = if (referenceStableHash != 0L) referenceStableHash else currentHash
                    transitionStartTime = timestampMs
                    currentState = DetectorState.TRANSITIONING
                    lastCandidateStatus = "Reel swipe started (vy=${String.format("%.1f", flow.vy)})"

                    Log.i(TAG, "Reel swipe detected: vy=${flow.vy}, consensus=${flow.consensus} -> TRANSITIONING")
                    onEvent(ReelDetectionEvent.MotionDetected(flow.vy, flow.consensus))
                }
            }

            DetectorState.TRANSITIONING -> {
                // Check if screen motion has settled
                val isMotionSettled = abs(flow.vy) < config.settlingFlowThreshold &&
                        analysis.frameDifferenceMad < config.minMotionDifferenceThreshold * 1.5f

                if (isMotionSettled) {
                    // Motion stopped; capture candidate frame
                    candidateHash = currentHash
                    candidateTimestamp = timestampMs
                    val distVsPre = hashAnalyzer.hammingDistance(candidateHash, preTransitionHash)
                    val similarity = hashAnalyzer.similarityPercentage(distVsPre)

                    Log.d(TAG, "Swipe settled. Dist vs pre-transition: $distVsPre bits (sim=${String.format("%.1f", similarity)}%)")

                    if (distVsPre <= config.sameReelBounceDistanceMax) {
                        // User pulled up slightly and released, snapping back to the same Reel
                        val reason = "Aborted swipe / bounce back to same Reel ($distVsPre bits diff)"
                        Log.i(TAG, "Candidate rejected: $reason -> REEL_VISIBLE")
                        lastCandidateStatus = "Rejected: $reason"
                        currentState = DetectorState.REEL_VISIBLE
                        onEvent(ReelDetectionEvent.CandidateRejected(reason))
                    } else if (distVsPre >= config.minDHashHammingDistance) {
                        // Candidate is visually distinct! Move to CANDIDATE_REEL then VERIFYING
                        currentState = DetectorState.CANDIDATE_REEL
                        lastCandidateStatus = "Candidate found ($distVsPre bits diff, sim=${String.format("%.1f", similarity)}%)"
                        Log.i(TAG, "Candidate accepted ($distVsPre bits) -> VERIFYING stability...")
                        onEvent(ReelDetectionEvent.CandidateFound(distVsPre, similarity))
                    } else {
                        // Ambiguous change
                        currentState = DetectorState.CANDIDATE_REEL
                        lastCandidateStatus = "Verifying ambiguous change ($distVsPre bits diff)"
                    }
                } else {
                    // Check for transition timeout
                    if (timestampMs - transitionStartTime > 3000L) {
                        Log.w(TAG, "Transition timed out (>3s). Returning to REEL_VISIBLE.")
                        currentState = DetectorState.REEL_VISIBLE
                        lastCandidateStatus = "Transition timed out"
                    }
                }
            }

            DetectorState.CANDIDATE_REEL -> {
                if (isCoherentVerticalSwipe(flow)) {
                    transitionStartTime = timestampMs
                    currentState = DetectorState.TRANSITIONING
                    lastCandidateStatus = "New swipe during candidate"
                } else {
                    currentState = DetectorState.VERIFYING
                }
            }

            DetectorState.VERIFYING -> {
                // If a new swipe begins while verifying, restart transition
                if (isCoherentVerticalSwipe(flow)) {
                    Log.d(TAG, "New swipe initiated during verification. Restarting TRANSITIONING.")
                    transitionStartTime = timestampMs
                    currentState = DetectorState.TRANSITIONING
                    lastCandidateStatus = "New swipe during verify"
                    return
                }

                // Verify stability for the dwell duration
                val dwellTime = timestampMs - candidateTimestamp
                if (dwellTime >= config.settlingDwellMs) {
                    if (timestampMs - lastVerifiedReelTimestamp < MIN_REEL_COOLDOWN_MS) {
                        // Ignore intra-video motion / scene cuts within cooldown window
                        currentState = DetectorState.REEL_VISIBLE
                        lastCandidateStatus = "Ignored change within cooldown window"
                        return
                    }

                    val finalDist = hashAnalyzer.hammingDistance(currentHash, preTransitionHash)
                    if (finalDist >= config.minDHashHammingDistance) {
                        // VERIFIED NEW REEL!
                        totalReelCount++
                        lastVerifiedReelTimestamp = timestampMs
                        referenceStableHash = currentHash
                        currentState = DetectorState.VERIFIED_REEL
                        lastCandidateStatus = "Verified Reel #$totalReelCount ($finalDist bits diff)"

                        Log.i(TAG, ">>> VERIFIED NEW REEL #$totalReelCount! (dHash dist=$finalDist bits) <<<")
                        onEvent(ReelDetectionEvent.ReelVerified(totalReelCount, finalDist, dwellTime))

                        // Return to REEL_VISIBLE for subsequent swipes
                        currentState = DetectorState.REEL_VISIBLE
                    } else {
                        val reason = "Failed final distinctness check ($finalDist bits vs ${config.minDHashHammingDistance})"
                        Log.i(TAG, "Candidate rejected: $reason")
                        lastCandidateStatus = "Rejected: $reason"
                        currentState = DetectorState.REEL_VISIBLE
                        onEvent(ReelDetectionEvent.CandidateRejected(reason))
                    }
                }
            }

            DetectorState.VERIFIED_REEL -> {
                currentState = DetectorState.REEL_VISIBLE
            }
        }
    }

    private fun isCoherentVerticalSwipe(flow: FlowVector): Boolean {
        return abs(flow.vy) >= config.swipeFlowThreshold &&
                flow.consensus >= config.motionConsensusThreshold &&
                flow.coherenceRatio >= 1.2f
    }

    fun resetCount() {
        totalReelCount = 0
        lastVerifiedReelTimestamp = 0L
        referenceStableHash = 0L
        preTransitionHash = 0L
        candidateHash = 0L
        currentState = DetectorState.REELS_NOT_ACTIVE
        lastCandidateStatus = "Counter reset to 0"
    }

    fun setInactive() {
        currentState = DetectorState.INACTIVE
        lastCandidateStatus = "Capture inactive"
    }

    fun setActive() {
        currentState = DetectorState.REELS_NOT_ACTIVE
        lastCandidateStatus = "Evaluating context (Waiting for Reels)..."
    }
}
