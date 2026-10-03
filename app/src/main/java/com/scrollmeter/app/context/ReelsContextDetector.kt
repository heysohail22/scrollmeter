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

    @Volatile
    var isInstagramPackageInForeground: Boolean = true

    /**
     * Evaluates the incoming screen frame and returns the current ReelsContextMode.
     */
    fun evaluate(frame: FrameData): ReelsContextMode {
        if (!isInstagramPackageInForeground) {
            currentMode = ReelsContextMode.REELS_NOT_ACTIVE
            lastSignals = ReelsContextSignals(
                hasRightActionColumn = false,
                confidence = 0f,
                reason = "Outside Instagram application"
            )
            return currentMode
        }

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

        // 1. Right Action Column (X: 84%..98%, Y: 45%..85%)
        val x1 = (w * 0.84f).toInt()
        val x2 = (w * 0.98f).toInt()
        val y1 = (h * 0.45f).toInt()
        val y2 = (h * 0.85f).toInt()
        var edgeSum = 0L
        var brightPixels = 0
        var pixelCount = 0
        for (y in y1 until y2 step 2) {
            val row = y * w
            for (x in x1 until x2 - 1) {
                val p1 = lum[row + x].toInt() and 0xFF
                val p2 = lum[row + x + 1].toInt() and 0xFF
                edgeSum += abs(p1 - p2)
                if (p1 > 160) brightPixels++
                pixelCount++
            }
        }
        val avgEdge = if (pixelCount > 0) edgeSum.toFloat() / pixelCount else 0f
        val brightRatio = if (pixelCount > 0) brightPixels.toFloat() / pixelCount else 0f
        val hasActionColumn = (avgEdge >= 3.0f && brightRatio >= 0.015f) || (avgEdge >= 5.5f)

        // 2. Bottom Nav (Y: 93%..99%)
        val navY1 = (h * 0.93f).toInt()
        val navY2 = (h * 0.99f).toInt()
        fun navAvg(rx1: Float, rx2: Float): Float {
            var tot = 0L
            var cnt = 0
            val sx = (w * rx1).toInt()
            val ex = (w * rx2).toInt()
            for (y in navY1 until navY2) {
                val row = y * w
                for (x in sx until ex) {
                    tot += lum[row + x].toInt() and 0xFF
                    cnt++
                }
            }
            return if (cnt > 0) tot.toFloat() / cnt else 0f
        }

        val homeVal = navAvg(0.04f, 0.16f)
        val reelsVal = navAvg(0.24f, 0.36f)
        val hasNav = homeVal > 20f && reelsVal > 20f
        val isHomeNav = hasNav && (homeVal > reelsVal * 1.15f)
        val isReelsNav = hasNav && (reelsVal > homeVal * 1.20f)

        // 3. Top Header: Back arrow & Posts feed check
        val topY1 = (h * 0.04f).toInt()
        val topY2 = (h * 0.09f).toInt()
        val arrowX1 = (w * 0.03f).toInt()
        val arrowX2 = (w * 0.14f).toInt()
        var backArrowPts = 0
        val arrowTotal = ((topY2 - topY1) * (arrowX2 - arrowX1)).coerceAtLeast(1)
        for (y in topY1 until topY2) {
            val row = y * w
            for (x in arrowX1 until arrowX2) {
                if ((lum[row + x].toInt() and 0xFF) > 180) backArrowPts++
            }
        }
        val hasBackArrow = (backArrowPts.toFloat() / arrowTotal) > 0.02f

        val postsX1 = (w * 0.16f).toInt()
        val postsX2 = (w * 0.40f).toInt()
        var postsPts = 0
        val postsTotal = ((topY2 - topY1) * (postsX2 - postsX1)).coerceAtLeast(1)
        for (y in topY1 until topY2) {
            val row = y * w
            for (x in postsX1 until postsX2) {
                if ((lum[row + x].toInt() and 0xFF) > 180) postsPts++
            }
        }
        val hasPostsTitle = hasBackArrow && ((postsPts.toFloat() / postsTotal) > 0.02f)

        // 4. Modal Sheet: Drag handle
        var hasModalHandle = false
        val modalY1 = (h * 0.25f).toInt()
        val modalY2 = (h * 0.45f).toInt()
        val modalWidth = ((w * 0.08f).toInt()).coerceAtLeast(1)
        val sideWidth = ((w * 0.07f).toInt()).coerceAtLeast(1)
        val cX1 = (w * 0.46f).toInt()
        val cX2 = (w * 0.54f).toInt()
        val lX1 = (w * 0.35f).toInt()
        val lX2 = (w * 0.42f).toInt()
        val rX1 = (w * 0.58f).toInt()
        val rX2 = (w * 0.65f).toInt()

        for (y in modalY1 until modalY2) {
            val row = y * w
            var cTot = 0L
            for (x in cX1 until cX2) { cTot += lum[row + x].toInt() and 0xFF }
            val cVal = cTot.toFloat() / modalWidth

            if (cVal > 100f) {
                var lTot = 0L
                for (x in lX1 until lX2) { lTot += lum[row + x].toInt() and 0xFF }
                val lVal = lTot.toFloat() / sideWidth

                var rTot = 0L
                for (x in rX1 until rX2) { rTot += lum[row + x].toInt() and 0xFF }
                val rVal = rTot.toFloat() / sideWidth

                if (cVal > lVal * 2.0f && cVal > rVal * 2.0f) {
                    hasModalHandle = true
                    break
                }
            }
        }

        // 5. Decision Tree (Deterministic, verified against ground truth)
        val instantMode: ReelsContextMode
        val reasonText: String

        if (hasPostsTitle) {
            instantMode = ReelsContextMode.REELS_NOT_ACTIVE
            reasonText = "Profile posts feed ('<- Posts') header detected"
        } else if (hasModalHandle && avgEdge < 12.0f) {
            instantMode = ReelsContextMode.REELS_MODAL_OPEN
            reasonText = "Comments / Share modal drag-handle detected"
        } else if (isHomeNav) {
            instantMode = ReelsContextMode.REELS_NOT_ACTIVE
            reasonText = "Home tab icon active in bottom navigation"
        } else if (isReelsNav) {
            instantMode = ReelsContextMode.REELS_ACTIVE
            reasonText = "Reels navigation tab active in bottom nav"
        } else if (hasActionColumn) {
            instantMode = ReelsContextMode.REELS_ACTIVE
            reasonText = "Reels action column verified"
        } else {
            instantMode = ReelsContextMode.REELS_NOT_ACTIVE
            reasonText = "No Reels action column or outside Reels"
        }

        // Hysteresis & Debounce: Hold REELS_ACTIVE across brief swipe transitions
        if (instantMode == ReelsContextMode.REELS_ACTIVE) {
            consecutiveActiveFrames++
            consecutiveInactiveFrames = 0
            currentMode = ReelsContextMode.REELS_ACTIVE
        } else if (instantMode == ReelsContextMode.REELS_MODAL_OPEN) {
            consecutiveInactiveFrames++
            if (consecutiveInactiveFrames >= 3) {
                currentMode = ReelsContextMode.REELS_MODAL_OPEN
            }
        } else {
            consecutiveActiveFrames = 0
            consecutiveInactiveFrames++
            if (isHomeNav || hasPostsTitle) {
                currentMode = ReelsContextMode.REELS_NOT_ACTIVE
            } else if (currentMode == ReelsContextMode.REELS_ACTIVE) {
                if (consecutiveInactiveFrames >= 10) {
                    currentMode = ReelsContextMode.REELS_NOT_ACTIVE
                }
            } else {
                currentMode = ReelsContextMode.REELS_NOT_ACTIVE
            }
        }

        val instantConfidence = when (currentMode) {
            ReelsContextMode.REELS_ACTIVE -> (0.70f + 0.30f * (avgEdge / 12f).coerceIn(0f, 1f)).coerceIn(0.7f, 1.0f)
            ReelsContextMode.REELS_MODAL_OPEN -> 0.85f
            ReelsContextMode.REELS_NOT_ACTIVE -> 0.10f
        }
        smoothedConfidence = (smoothedConfidence * 0.40f) + (instantConfidence * 0.60f)

        lastSignals = ReelsContextSignals(
            hasRightActionColumn = hasActionColumn,
            rightActionColumnScore = (avgEdge / 10f).coerceIn(0f, 1f),
            hasHomeFeedStoriesOrHeader = isHomeNav || hasPostsTitle,
            storiesHeaderScore = if (isHomeNav) 1.0f else 0.0f,
            isFullBleedVideo = currentMode == ReelsContextMode.REELS_ACTIVE,
            isModalSheetOpen = hasModalHandle,
            confidence = smoothedConfidence,
            reason = reasonText
        )

        return currentMode
    }

    fun reset() {
        currentMode = ReelsContextMode.REELS_NOT_ACTIVE
        smoothedConfidence = 0f
        consecutiveActiveFrames = 0
        consecutiveInactiveFrames = 0
        lastSignals = ReelsContextSignals()
    }
}
