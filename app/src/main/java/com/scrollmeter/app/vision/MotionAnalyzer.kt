package com.scrollmeter.app.vision

import com.scrollmeter.app.detector.DetectionConfig
import com.scrollmeter.app.model.FlowVector
import com.scrollmeter.app.model.FrameData

/**
 * Result of analyzing a single frame against the previous frame and reference stable reel.
 */
data class FrameAnalysisResult(
    val frameDifferenceMad: Float,
    val flow: FlowVector,
    val currentDHash: Long,
    val dHashDistanceVsStable: Int,
    val similarityScore: Float,
    val isSignificantChange: Boolean
)

/**
 * High-level computer vision analyzer coordinating difference detection,
 * optical flow vector estimation, and perceptual hashing.
 */
class MotionAnalyzer(
    private val config: DetectionConfig
) {
    private val diffAnalyzer = ImageDifferenceAnalyzer()
    private val flowAnalyzer = OpticalFlowAnalyzer()
    val hashAnalyzer = PerceptualHashAnalyzer()

    private var previousFrame: FrameData? = null

    /**
     * Analyzes incoming frame against previous frame and reference stable hash.
     */
    fun analyze(
        currentFrame: FrameData,
        referenceStableHash: Long
    ): FrameAnalysisResult {
        val prev = previousFrame
        val currentHash = hashAnalyzer.computeDHash(
            currentFrame.luminance,
            currentFrame.width,
            currentFrame.height
        )

        val hashDistVsStable = if (referenceStableHash != 0L) {
            hashAnalyzer.hammingDistance(currentHash, referenceStableHash)
        } else {
            0
        }

        val similarity = hashAnalyzer.similarityPercentage(hashDistVsStable)

        if (prev == null) {
            previousFrame = currentFrame
            return FrameAnalysisResult(
                frameDifferenceMad = 0f,
                flow = FlowVector(),
                currentDHash = currentHash,
                dHashDistanceVsStable = hashDistVsStable,
                similarityScore = similarity,
                isSignificantChange = false
            )
        }

        // 1. Fast diff gatekeeper
        val diffMad = diffAnalyzer.computeDifference(prev.luminance, currentFrame.luminance)
        val isSignificant = diffMad >= config.minMotionDifferenceThreshold

        // 2. Optical Flow (only run if diff is above gatekeeper threshold to save battery)
        val flow = if (isSignificant) {
            flowAnalyzer.computeFlow(
                prev = prev.luminance,
                curr = currentFrame.luminance,
                width = currentFrame.width,
                height = currentFrame.height
            )
        } else {
            FlowVector()
        }

        previousFrame = currentFrame

        return FrameAnalysisResult(
            frameDifferenceMad = diffMad,
            flow = flow,
            currentDHash = currentHash,
            dHashDistanceVsStable = hashDistVsStable,
            similarityScore = similarity,
            isSignificantChange = isSignificant
        )
    }

    fun reset() {
        previousFrame = null
    }
}
