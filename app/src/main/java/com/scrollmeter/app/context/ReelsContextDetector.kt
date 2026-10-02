package com.scrollmeter.app.context

import android.util.Log
import com.scrollmeter.app.model.FrameData
import kotlin.math.abs

/**
 * Dedicated visual context detector that determines whether the user is currently
 * inside Instagram's dedicated Reels viewer or elsewhere (Home feed, Profile, Explore, etc.).
 *
 * Operates purely on screen-capture frames without requiring AccessibilityService.
 */
class ReelsContextDetector(
    val config: ReelsContextConfig = ReelsContextConfig()
) {
    companion object {
        private const val TAG = "ReelsContext"
    }

    var currentMode: ReelsContextMode = ReelsContextMode.REELS_NOT_ACTIVE
        private set

    var lastSignals: ReelsContextSignals = ReelsContextSignals()
        private set

    private var smoothedConfidence: Float = 0f
    private var consecutiveActiveFrames: Int = 0
    private var consecutiveInactiveFrames: Int = 0

    /**
     * Evaluates the incoming screen frame and returns the current ReelsContextMode.
     */
    fun evaluate(frame: FrameData): ReelsContextMode {
        // Quick bypass if user enabled Force Reels Mode for testing transition counting
        if (config.isForceReelsMode) {
            currentMode = ReelsContextMode.REELS_ACTIVE
            lastSignals = ReelsContextSignals(
                hasRightActionColumn = true,
                rightActionColumnScore = 1.0f,
                hasHomeFeedStoriesOrHeader = false,
                storiesHeaderScore = 0.0f,
                isFullBleedVideo = true,
                isModalSheetOpen = false,
                confidence = 1.0f,
                reason = "Force Reels Active (testing mode enabled)"
            )
            return currentMode
        }

        val lum = frame.luminance
        val w = frame.width
        val h = frame.height

        if (w <= 0 || h <= 0 || lum.size < w * h) {
            return currentMode
        }

        // 1. Evaluate Right-Side Action Column (Reels signature: Like, Comment, Share, More/Audio)
        val actionColumnScore = evaluateRightActionColumn(lum, w, h)
        val hasActionColumn = actionColumnScore >= config.actionColumnMinScore

        // 2. Evaluate Top Stories Carousel / Home Feed Header (Home feed signature)
        val storiesHeaderScore = evaluateStoriesHeader(lum, w, h)
        val hasStoriesHeader = storiesHeaderScore >= config.storiesRejectionThreshold

        // 3. Evaluate Bottom Sheet / Modal (Comments, Share dialog)
        val isModalOpen = evaluateModalSheet(lum, w, h)

        // 4. Evaluate Full-Bleed Video layout
        val isFullBleed = !hasStoriesHeader && evaluateFullBleedLuminance(lum, w, h)

        // 5. Compute instant confidence score
        var frameScore = 0.20f // Base baseline
        if (hasActionColumn) {
            frameScore += 0.55f * (actionColumnScore / 0.50f).coerceIn(0f, 1f)
        }
        if (isFullBleed) {
            frameScore += 0.25f
        }
        if (hasStoriesHeader) {
            // Strong penalty if Home feed stories/header is detected
            frameScore -= 0.65f * (storiesHeaderScore / 0.70f).coerceIn(0f, 1f)
        }

        val instantConfidence = frameScore.coerceIn(0f, 1f)
        smoothedConfidence = (smoothedConfidence * 0.55f) + (instantConfidence * 0.45f)

        // 6. Temporal Hysteresis
        val decidedMode = when {
            smoothedConfidence >= config.activeConfidenceThreshold -> {
                consecutiveActiveFrames++
                consecutiveInactiveFrames = 0
                if (consecutiveActiveFrames >= config.hysteresisFramesRequired) {
                    if (isModalOpen) ReelsContextMode.REELS_MODAL_OPEN else ReelsContextMode.REELS_ACTIVE
                } else {
                    currentMode
                }
            }
            smoothedConfidence < config.inactiveConfidenceThreshold -> {
                consecutiveInactiveFrames++
                consecutiveActiveFrames = 0
                if (consecutiveInactiveFrames >= config.hysteresisFramesRequired) {
                    ReelsContextMode.REELS_NOT_ACTIVE
                } else {
                    currentMode
                }
            }
            else -> {
                // In deadband between thresholds: maintain current mode
                if (currentMode == ReelsContextMode.REELS_ACTIVE && isModalOpen) {
                    ReelsContextMode.REELS_MODAL_OPEN
                } else if (currentMode == ReelsContextMode.REELS_MODAL_OPEN && !isModalOpen) {
                    ReelsContextMode.REELS_ACTIVE
                } else {
                    currentMode
                }
            }
        }

        currentMode = decidedMode

        val reasonText = when (currentMode) {
            ReelsContextMode.REELS_ACTIVE ->
                "Reels confirmed: Right action icons present (${(actionColumnScore * 100).toInt()}%), full-bleed video."
            ReelsContextMode.REELS_MODAL_OPEN ->
                "In Reels, but Comment/Share sheet is open."
            ReelsContextMode.REELS_NOT_ACTIVE -> {
                if (hasStoriesHeader) {
                    "Instagram Home feed detected (Stories carousel / header active)."
                } else {
                    "Outside Reels (Action column score=${(actionColumnScore * 100).toInt()}%)."
                }
            }
        }

        lastSignals = ReelsContextSignals(
            hasRightActionColumn = hasActionColumn,
            rightActionColumnScore = actionColumnScore,
            hasHomeFeedStoriesOrHeader = hasStoriesHeader,
            storiesHeaderScore = storiesHeaderScore,
            isFullBleedVideo = isFullBleed,
            isModalSheetOpen = isModalOpen,
            confidence = smoothedConfidence,
            reason = reasonText
        )

        return currentMode
    }

    /**
     * Detects the vertical column of action icons on the right edge of Reels:
     * - Like, Comment, Share, Audio/More icons are vertically stacked in X: [84%..97%], Y: [38%..85%].
     */
    private fun evaluateRightActionColumn(lum: ByteArray, w: Int, h: Int): Float {
        val startX = (w * 0.84f).toInt()
        val endX = (w * 0.97f).toInt()
        val startY = (h * 0.38f).toInt()
        val endY = (h * 0.85f).toInt()

        if (endX <= startX || endY <= startY) return 0f

        // Divide vertical strip into 8 bins (~16-20 pixels tall each)
        val numBins = 8
        val binHeight = (endY - startY) / numBins
        if (binHeight <= 2) return 0f

        var binsWithIconEnergy = 0
        val binEnergies = FloatArray(numBins)

        for (bin in 0 until numBins) {
            val binStartY = startY + bin * binHeight
            val binEndY = binStartY + binHeight

            var edgeSum = 0L
            var brightPixels = 0
            var pixelCount = 0

            for (y in binStartY until binEndY step 2) {
                val rowOffset = y * w
                for (x in startX until endX - 1) {
                    val p1 = lum[rowOffset + x].toInt() and 0xFF
                    val p2 = lum[rowOffset + x + 1].toInt() and 0xFF
                    edgeSum += abs(p1 - p2)
                    if (p1 > 160) brightPixels++
                    pixelCount++
                }
            }

            val avgEdge = if (pixelCount > 0) (edgeSum.toFloat() / pixelCount) else 0f
            val brightRatio = if (pixelCount > 0) (brightPixels.toFloat() / pixelCount) else 0f
            binEnergies[bin] = avgEdge

            // Icons have bright pixels and localized sharp contrast
            if (avgEdge >= 9.0f || (brightRatio > 0.15f && avgEdge >= 6.0f)) {
                binsWithIconEnergy++
            }
        }

        // In Reels, typically 3 to 5 of the 8 bins contain the icons
        val iconClusterRatio = binsWithIconEnergy.toFloat() / numBins

        // Check for vertical alternating pattern (presence of peaks and valleys)
        var alternatingScore = 0f
        for (i in 1 until numBins) {
            val diff = abs(binEnergies[i] - binEnergies[i - 1])
            if (diff > 5.0f) alternatingScore += 0.20f
        }

        val finalScore = (iconClusterRatio * 0.65f + alternatingScore.coerceAtMost(0.35f)).coerceIn(0f, 1f)
        return finalScore
    }

    /**
     * Detects presence of the Instagram Home Feed top stories carousel:
     * - Only triggers if there is a distinct horizontal bar of circular stories.
     */
    private fun evaluateStoriesHeader(lum: ByteArray, w: Int, h: Int): Float {
        val startY = (h * 0.08f).toInt()
        val endY = (h * 0.20f).toInt()
        val startX = (w * 0.05f).toInt()
        val endX = (w * 0.95f).toInt()

        if (endX <= startX || endY <= startY) return 0f

        var horizontalTransitions = 0
        var totalSamples = 0
        var totalGradient = 0L

        // Sample across the stories band
        for (y in startY until endY step 3) {
            val rowOffset = y * w
            var prevVal = lum[rowOffset + startX].toInt() and 0xFF

            for (x in startX + 1 until endX step 2) {
                val currVal = lum[rowOffset + x].toInt() and 0xFF
                val diff = abs(currVal - prevVal)
                totalGradient += diff

                // Strict threshold for circular avatar borders
                if (diff > 35) {
                    horizontalTransitions++
                }
                prevVal = currVal
                totalSamples++
            }
        }

        if (totalSamples == 0) return 0f

        val transitionDensity = horizontalTransitions.toFloat() / totalSamples
        val avgGradient = totalGradient.toFloat() / totalSamples

        // Only declare stories header if transition density is consistently high across the entire width
        val score = if (transitionDensity > 0.18f && avgGradient > 22f) {
            ((transitionDensity - 0.18f) * 4f).coerceIn(0f, 1f)
        } else {
            0f
        }
        return score
    }

    /**
     * Detects if comments, share, or 3-dots sheet is open over Reels:
     * - Upper 25% is dimmed (low luminance/scrim).
     * - Lower 65% has a continuous horizontal top border (sheet header) at Y: [28%..42%].
     */
    private fun evaluateModalSheet(lum: ByteArray, w: Int, h: Int): Boolean {
        val scrimYEnd = (h * 0.24f).toInt()
        val sheetBorderYStart = (h * 0.28f).toInt()
        val sheetBorderYEnd = (h * 0.42f).toInt()

        if (sheetBorderYEnd <= sheetBorderYStart || scrimYEnd <= 0) return false

        // 1. Check if upper screen is dimmed (scrim)
        var scrimSum = 0L
        var scrimCount = 0
        for (y in 0 until scrimYEnd step 4) {
            val rowOffset = y * w
            for (x in (w * 0.15f).toInt() until (w * 0.85f).toInt() step 4) {
                scrimSum += lum[rowOffset + x].toInt() and 0xFF
                scrimCount++
            }
        }
        val avgScrimLuminance = if (scrimCount > 0) (scrimSum / scrimCount) else 255L
        val isScrimDimmed = avgScrimLuminance < 50 // Dark overlay scrim

        // 2. Check for sharp horizontal boundary of the bottom sheet across 70% of screen width
        var hasHorizontalEdge = false
        val testXStart = (w * 0.15f).toInt()
        val testXEnd = (w * 0.85f).toInt()

        for (y in sheetBorderYStart until sheetBorderYEnd step 2) {
            val topRow = y * w
            val bottomRow = (y + 4) * w
            var rowDiffSum = 0L
            var count = 0

            for (x in testXStart until testXEnd step 4) {
                val topPixel = lum[topRow + x].toInt() and 0xFF
                val bottomPixel = lum[bottomRow + x].toInt() and 0xFF
                rowDiffSum += abs(bottomPixel - topPixel)
                count++
            }

            val avgRowDiff = if (count > 0) (rowDiffSum.toFloat() / count) else 0f
            if (avgRowDiff > 35f) {
                hasHorizontalEdge = true
                break
            }
        }

        return isScrimDimmed && hasHorizontalEdge
    }

    /**
     * Checks if the frame has full-bleed continuous visual content.
     */
    private fun evaluateFullBleedLuminance(lum: ByteArray, w: Int, h: Int): Boolean {
        val topY = (h * 0.10f).toInt()
        val midY = (h * 0.50f).toInt()
        val botY = (h * 0.80f).toInt()

        val topRow = topY * w
        val midRow = midY * w
        val botRow = botY * w

        var nonZeroCount = 0
        for (x in (w * 0.2f).toInt() until (w * 0.8f).toInt() step 8) {
            if ((lum[topRow + x].toInt() and 0xFF) > 5) nonZeroCount++
            if ((lum[midRow + x].toInt() and 0xFF) > 5) nonZeroCount++
            if ((lum[botRow + x].toInt() and 0xFF) > 5) nonZeroCount++
        }
        return nonZeroCount > 10
    }

    fun reset() {
        currentMode = ReelsContextMode.REELS_NOT_ACTIVE
        smoothedConfidence = 0f
        consecutiveActiveFrames = 0
        consecutiveInactiveFrames = 0
        lastSignals = ReelsContextSignals()
    }
}
