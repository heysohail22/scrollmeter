package com.scrollmeter.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.scrollmeter.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Modern Android 14+ compatible Foreground Service for Screen Capture.
 * Strictly adheres to Android 14 requirements:
 * 1. Declares FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION in manifest and code.
 * 2. Invokes startForeground() prior to obtaining MediaProjection.
 * 3. Registers mandatory MediaProjection.Callback.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID = "scrollmeter_capture_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.scrollmeter.app.action.START_CAPTURE"
        const val ACTION_STOP = "com.scrollmeter.app.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameSampler: FrameSampler? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var notificationJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system or user.")
            stopCapture()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != 0 && resultData != null) {
                    startCapture(resultCode, resultData)
                } else {
                    Log.e(TAG, "Invalid resultCode or data provided for screen capture.")
                    stopSelf()
                }
            }

            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (isRunning) return

        // 1. Post initial foreground notification BEFORE calling getMediaProjection
        val notification = buildNotification(0, "Initializing detector...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Obtain MediaProjection instance
        val projection = mediaProjectionManager?.getMediaProjection(resultCode, data)
        if (projection == null) {
            Log.e(TAG, "MediaProjectionManager returned null projection.")
            stopSelf()
            return
        }
        mediaProjection = projection

        // 3. Register mandatory callback (enforced on Android 14)
        projection.registerCallback(mediaProjectionCallback, mainHandler)

        // 4. Setup ImageReader and FrameSampler matching device's real aspect ratio
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }

        val realW = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
        val realH = if (metrics.heightPixels > 0) metrics.heightPixels else 2400

        // Scale proportionately to match phone's exact aspect ratio (eliminates black bars/distortion!)
        val width = 180
        val height = ((width.toFloat() / realW) * realH).toInt().coerceIn(320, 480)

        val config = ScreenCaptureManager.config
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val sampler = FrameSampler(
            width = width,
            height = height,
            targetFps = config.targetFps
        ) { frameData ->
            ScreenCaptureManager.detector.onNewFrame(frameData)
        }
        frameSampler = sampler

        val imageHandler = Handler(Looper.getMainLooper())
        reader.setOnImageAvailableListener(sampler, imageHandler)

        // 5. Create hardware-accelerated VirtualDisplay
        try {
            virtualDisplay = projection.createVirtualDisplay(
                "ScrollMeterVirtualDisplay",
                width,
                height,
                DisplayMetrics.DENSITY_LOW,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDisplay", e)
            stopCapture()
            stopSelf()
            return
        }

        isRunning = true
        ScreenCaptureManager.detector.startCapture()

        // 6. Observe telemetry to update notification shade (throttled to avoid notification rate-limiting)
        notificationJob = serviceScope.launch {
            var lastCount = -1
            var lastUpdateMs = 0L
            ScreenCaptureManager.detector.telemetry.collect { tel ->
                val now = System.currentTimeMillis()
                if (tel.verifiedReelCount != lastCount || now - lastUpdateMs >= 1500L) {
                    lastCount = tel.verifiedReelCount
                    lastUpdateMs = now
                    val statusText = "${tel.state} • ${tel.candidateStatus}"
                    val updatedNotification = buildNotification(tel.verifiedReelCount, statusText)
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, updatedNotification)
                }
            }
        }

        Log.i(TAG, "ScreenCaptureService started successfully ($width x $height @ ${config.targetFps} FPS).")
    }

    private fun stopCapture() {
        if (!isRunning) return
        isRunning = false

        notificationJob?.cancel()
        notificationJob = null

        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up screen capture resources", e)
        }

        ScreenCaptureManager.detector.stopCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "ScreenCaptureService stopped and resources released.")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
    }

    private fun buildNotification(reelCount: Int, status: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScrollMeter: $reelCount Reels Counted")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ScrollMeter Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live Instagram Reels count and computer-vision detector status."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
