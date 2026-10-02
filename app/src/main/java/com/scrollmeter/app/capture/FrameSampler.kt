package com.scrollmeter.app.capture

import android.media.ImageReader
import android.util.Log
import com.scrollmeter.app.model.FrameData
import java.nio.ByteBuffer

/**
 * Manages frame extraction from ImageReader at a controlled sample rate.
 * Extracts 8-bit luminance directly from RGBA_8888 plane buffers,
 * correctly handling rowStride and pixelStride.
 */
class FrameSampler(
    private val width: Int,
    private val height: Int,
    private val targetFps: Int,
    private val onFrameSampled: (FrameData) -> Unit
) : ImageReader.OnImageAvailableListener {

    companion object {
        private const val TAG = "FrameSampler"
    }

    private val sampleIntervalMs = 1000L / targetFps
    private var lastSampleTimestamp = 0L

    // Reusable byte array buffer to avoid garbage collection churn
    private val luminanceBuffer = ByteArray(width * height)

    override fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire latest image", e)
            null
        } ?: return

        try {
            val now = System.currentTimeMillis()
            if (now - lastSampleTimestamp < sampleIntervalMs) {
                // Drop frame to maintain desired sample rate and conserve battery
                return
            }
            lastSampleTimestamp = now

            val planes = image.planes
            if (planes.isEmpty()) return

            val plane = planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val imageWidth = image.width
            val imageHeight = image.height

            val targetW = minOf(width, imageWidth)
            val targetH = minOf(height, imageHeight)

            // Extract grayscale luminance (Y = 0.299R + 0.587G + 0.114B)
            // Using fast integer arithmetic: (R*77 + G*150 + B*29) >> 8
            for (y in 0 until targetH) {
                val rowOffset = y * rowStride
                val targetRowOffset = y * width

                for (x in 0 until targetW) {
                    val pixelOffset = rowOffset + x * pixelStride
                    val r = buffer.get(pixelOffset).toInt() and 0xFF
                    val g = buffer.get(pixelOffset + 1).toInt() and 0xFF
                    val b = buffer.get(pixelOffset + 2).toInt() and 0xFF

                    val lum = (r * 77 + g * 150 + b * 29) shr 8
                    luminanceBuffer[targetRowOffset + x] = lum.toByte()
                }
            }

            // Create immutable copy of current frame for analysis pipeline
            val frameCopy = luminanceBuffer.copyOf()
            onFrameSampled(
                FrameData(
                    luminance = frameCopy,
                    width = width,
                    height = height,
                    timestampMs = now
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing screen frame", e)
        } finally {
            image.close()
        }
    }
}
