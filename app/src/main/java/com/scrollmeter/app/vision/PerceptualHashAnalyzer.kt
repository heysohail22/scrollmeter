package com.scrollmeter.app.vision

/**
 * Computes 64-bit difference perceptual hash (dHash) on downsampled luminance frames.
 * dHash operates on structural gradients and is highly resistant to minor video compression,
 * minor lighting fluctuations, and small animations while reliably differentiating distinct scenes/reels.
 */
class PerceptualHashAnalyzer {

    companion object {
        private const val HASH_ROWS = 8
        private const val HASH_COLS = 9 // 9 columns give 8 horizontal difference comparisons per row
    }

    /**
     * Computes a 64-bit dHash representation of the frame.
     */
    fun computeDHash(luminance: ByteArray, width: Int, height: Int): Long {
        if (width <= 0 || height <= 0 || luminance.size < width * height) return 0L

        // Downsample luminance to an 8x9 grid of average cell values
        val cellW = width / HASH_COLS
        val cellH = height / HASH_ROWS
        if (cellW <= 0 || cellH <= 0) return 0L

        val cellGrid = IntArray(HASH_ROWS * HASH_COLS)

        for (r in 0 until HASH_ROWS) {
            val startY = r * cellH
            val endY = minOf(startY + cellH, height)

            for (c in 0 until HASH_COLS) {
                val startX = c * cellW
                val endX = minOf(startX + cellW, width)

                var sum = 0L
                var count = 0

                for (y in startY until endY step 2) {
                    val rowOffset = y * width
                    for (x in startX until endX step 2) {
                        sum += luminance[rowOffset + x].toInt() and 0xFF
                        count++
                    }
                }

                val avg = if (count > 0) (sum / count).toInt() else 0
                cellGrid[r * HASH_COLS + c] = avg
            }
        }

        // Build 64-bit hash from horizontal gradient comparisons
        var hash = 0L
        var bitIndex = 0

        for (r in 0 until HASH_ROWS) {
            val rowOffset = r * HASH_COLS
            for (c in 0 until HASH_COLS - 1) {
                val left = cellGrid[rowOffset + c]
                val right = cellGrid[rowOffset + c + 1]

                if (right > left) {
                    hash = hash or (1L shl bitIndex)
                }
                bitIndex++
            }
        }

        return hash
    }

    /**
     * Computes the Hamming distance (number of differing bits) between two 64-bit dHashes.
     * Returns an integer from 0 (identical) to 64 (maximum difference).
     */
    fun hammingDistance(hashA: Long, hashB: Long): Int {
        return java.lang.Long.bitCount(hashA xor hashB)
    }

    /**
     * Converts Hamming distance to a human-readable similarity percentage (0% to 100%).
     */
    fun similarityPercentage(hammingDist: Int): Float {
        val clamped = hammingDist.coerceIn(0, 64)
        return ((64 - clamped).toFloat() / 64f) * 100f
    }
}
