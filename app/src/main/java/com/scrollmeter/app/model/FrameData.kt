package com.scrollmeter.app.model

/**
 * Encapsulates a sampled video frame represented as a 1D grayscale/luminance byte array.
 * Using 8-bit luminance values drastically reduces memory allocation and CPU cycles
 * while preserving all spatial and motion information required for computer vision.
 */
data class FrameData(
    val luminance: ByteArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FrameData

        if (width != other.width) return false
        if (height != other.height) return false
        if (timestampMs != other.timestampMs) return false
        if (!luminance.contentEquals(other.luminance)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + luminance.contentHashCode()
        return result
    }
}
