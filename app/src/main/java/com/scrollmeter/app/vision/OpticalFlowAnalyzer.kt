package com.scrollmeter.app.vision

import com.scrollmeter.app.model.FlowVector
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Computes fast sparse block-matching Optical Flow over the central Region of Interest (ROI).
 * 
 * In Instagram Reels, vertical page swipes produce a massive, synchronized vertical translation 
 * across the central screen area. In contrast, in-reel animations (talking heads, dancing, subtitles)
 * produce either localized or divergent vectors with low vertical consensus.
 */
class OpticalFlowAnalyzer {

    companion object {
        // Central ROI margins (exclude status bar, top headers, and bottom nav/comment bar)
        private const val ROI_Y_START_RATIO = 0.20f
        private const val ROI_Y_END_RATIO = 0.80f
        private const val ROI_X_START_RATIO = 0.15f
        private const val ROI_X_END_RATIO = 0.85f

        // Grid of sampling blocks
        private const val GRID_COLS = 4
        private const val GRID_ROWS = 5
        private const val BLOCK_SIZE = 16

        // Search windows in pixels (downscaled space)
        private const val SEARCH_Y_RANGE = 14
        private const val SEARCH_X_RANGE = 4
        private const val SEARCH_STEP = 2
    }

    /**
     * Estimates optical flow vectors between previous frame and current frame.
     */
    fun computeFlow(
        prev: ByteArray,
        curr: ByteArray,
        width: Int,
        height: Int
    ): FlowVector {
        if (prev.size != curr.size || width <= 0 || height <= 0) {
            return FlowVector()
        }

        val roiStartX = (width * ROI_X_START_RATIO).toInt()
        val roiEndX = (width * ROI_X_END_RATIO).toInt() - BLOCK_SIZE
        val roiStartY = (height * ROI_Y_START_RATIO).toInt()
        val roiEndY = (height * ROI_Y_END_RATIO).toInt() - BLOCK_SIZE

        if (roiEndX <= roiStartX || roiEndY <= roiStartY) {
            return FlowVector()
        }

        val stepX = (roiEndX - roiStartX) / GRID_COLS
        val stepY = (roiEndY - roiStartY) / GRID_ROWS

        var sumVx = 0f
        var sumVy = 0f
        var totalBlocks = 0
        val blockDisplacementsY = mutableListOf<Float>()

        for (row in 0 until GRID_ROWS) {
            val blockY = roiStartY + row * stepY
            for (col in 0 until GRID_COLS) {
                val blockX = roiStartX + col * stepX

                val (dx, dy) = findBestMatch(
                    prev = prev,
                    curr = curr,
                    width = width,
                    height = height,
                    bx = blockX,
                    by = blockY
                )

                sumVx += dx
                sumVy += dy
                blockDisplacementsY.add(dy.toFloat())
                totalBlocks++
            }
        }

        if (totalBlocks == 0) return FlowVector()

        val avgVx = sumVx / totalBlocks
        val avgVy = sumVy / totalBlocks
        val magnitude = sqrt((avgVx * avgVx + avgVy * avgVy).toDouble()).toFloat()

        // Calculate vertical consensus: what percentage of blocks agree on the sign of movement?
        val positiveYCount = blockDisplacementsY.count { it > 1.5f }
        val negativeYCount = blockDisplacementsY.count { it < -1.5f }
        val dominantCount = maxOf(positiveYCount, negativeYCount)
        val consensus = dominantCount.toFloat() / totalBlocks

        val coherenceRatio = if (abs(avgVx) < 0.1f) abs(avgVy) / 0.1f else abs(avgVy) / (abs(avgVx) + 0.1f)

        return FlowVector(
            vx = avgVx,
            vy = avgVy,
            magnitude = magnitude,
            consensus = consensus,
            coherenceRatio = coherenceRatio
        )
    }

    /**
     * Fast Sum of Absolute Differences (SAD) block matching for a single block.
     * Compares block in current frame against candidate regions in the previous frame.
     */
    private fun findBestMatch(
        prev: ByteArray,
        curr: ByteArray,
        width: Int,
        height: Int,
        bx: Int,
        by: Int
    ): Pair<Int, Int> {
        var bestDx = 0
        var bestDy = 0
        var minSad = Long.MAX_VALUE

        // Test candidate displacements
        for (dy in -SEARCH_Y_RANGE..SEARCH_Y_RANGE step SEARCH_STEP) {
            val targetY = by + dy
            if (targetY < 0 || targetY + BLOCK_SIZE >= height) continue

            for (dx in -SEARCH_X_RANGE..SEARCH_X_RANGE step SEARCH_STEP) {
                val targetX = bx + dx
                if (targetX < 0 || targetX + BLOCK_SIZE >= width) continue

                var sad = 0L
                // Evaluate pixel differences within block (subsampled by 2 for speed)
                for (py in 0 until BLOCK_SIZE step 2) {
                    val currIndexRow = (by + py) * width
                    val prevIndexRow = (targetY + py) * width

                    for (px in 0 until BLOCK_SIZE step 2) {
                        val c = curr[currIndexRow + (bx + px)].toInt() and 0xFF
                        val p = prev[prevIndexRow + (targetX + px)].toInt() and 0xFF
                        sad += abs(c - p)
                    }
                }

                if (sad < minSad) {
                    minSad = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        return Pair(bestDx, bestDy)
    }
}
