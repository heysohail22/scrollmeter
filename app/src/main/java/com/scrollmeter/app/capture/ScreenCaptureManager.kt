package com.scrollmeter.app.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import com.scrollmeter.app.detector.DetectionConfig
import com.scrollmeter.app.detector.ReelTransitionDetector
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton coordinator providing global access to the active detection engine
 * and managing service lifecycle intents.
 */
object ScreenCaptureManager {

    val config = DetectionConfig()
    val detector = ReelTransitionDetector(config)

    fun startService(context: Context, resultCode: Int, data: Intent) {
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopService(context: Context) {
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun resetCounter() {
        detector.resetCount()
    }
}
