package com.scrollmeter.app.vision

import kotlin.math.abs

/**
 * Computes fast Mean Absolute Difference (MAD) between consecutive downsampled frames.
 * Serves as an ultra-cheap gatekeeper: if the screen hasn't changed meaningfully,
 * we skip optical flow and perceptual hashing entirely to conserve mobile battery.
 */
class ImageDifferenceAnalyzer {

    /**
     * Computes the normalized difference between two luminance arrays of the same length.
     * Returns a float between 0.0 (identical) and 1.0 (completely inverted/white vs black).
     */
    fun computeDifference(prev: ByteArray, curr: ByteArray): Float {
        val size = minOf(prev.size, curr.size)
        if (size == 0) return 0f

        var totalDiff = 0L
        // Step by 2 or 4 pixels for even faster evaluation on mobile CPU
        val step = 2
        var sampledPixels = 0

        for (i in 0 until size step step) {
            val p = prev[i].toInt() and 0xFF
            val c = curr[i].toInt() and 0xFF
            totalDiff += abs(p - c)
            sampledPixels++
        }

        if (sampledPixels == 0) return 0f
        return (totalDiff.toDouble() / (sampledPixels * 255.0)).toFloat()
    }
}
