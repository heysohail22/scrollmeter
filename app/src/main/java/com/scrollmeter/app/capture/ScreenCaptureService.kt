package com.scrollmeter.app.capture

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
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
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.scrollmeter.app.MainActivity
import com.scrollmeter.app.NotchOverlayManager
import com.scrollmeter.app.ReelTrackerState
import com.scrollmeter.app.data.AppDatabase
import com.scrollmeter.app.data.ReelRecord
import com.scrollmeter.app.data.ReelSession
import com.scrollmeter.app.detector.ReelDetectionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modern Android 14+ compatible Foreground Service for Screen Capture & Computer Vision.
 * Strictly adheres to Android 14 requirements:
 * 1. Declares FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION in manifest and code.
 * 2. Invokes startForeground() prior to obtaining MediaProjection.
 * 3. Registers mandatory MediaProjection.Callback.
 *
 * Coordinates:
 * - Real-time screen capture via VirtualDisplay & ImageReader
 * - Pure Computer Vision detection (optical flow, dHash, and context detection)
 * - Room database persistence for ReelRecords and ReelSessions
 * - Live dwell-time ticker and floating Notch pill overlay
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID = "scrollmeter_capture_channel"
        const val NOTIFICATION_ID = 8801

        const val ACTION_START = "com.scrollmeter.app.action.START_CAPTURE"
        const val ACTION_STOP = "com.scrollmeter.app.action.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val MIN_DWELL_TIME_MS = 1000L
        private const val MAX_REEL_WATCH_CAP_MS = 300_000L // 5 minutes max
        private const val SESSION_TIMEOUT_MS = 90_000L // 90 seconds idle starts new session

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameSampler: FrameSampler? = null

    private lateinit var database: AppDatabase
    private lateinit var notchOverlayManager: NotchOverlayManager

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var notificationJob: Job? = null
    private var eventCollectorJob: Job? = null
    private var liveTickerJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentSessionId: Long = 0L
    private var activeRecordId: Long = 0L
    private var activeReelStartTime: Long = 0L
    private var lastReelActivityTimestamp: Long = 0L
    private var isReelsActive: Boolean = false

    private var lastKnownForegroundPackage: String? = null
    private var lastPackageCheckTime: Long = 0L

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isInstagramInForeground(): Boolean {
        if (!hasUsageStatsPermission()) {
            return true
        }

        val now = System.currentTimeMillis()
        if (now - lastPackageCheckTime < 800L && lastKnownForegroundPackage != null) {
            return lastKnownForegroundPackage == "com.instagram.android"
        }
        lastPackageCheckTime = now

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return true

        try {
            // Look back up to 10 minutes for the most recent Activity transition
            val events = usm.queryEvents(now - 600_000L, now)
            val event = UsageEvents.Event()
            var latestEventTime = 0L
            var latestPackage: String? = null

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // Filter out ScrollMeter itself
                if (event.packageName == packageName) {
                    continue
                }

                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.timeStamp >= latestEventTime) {
                        latestEventTime = event.timeStamp
                        latestPackage = event.packageName
                    }
                }
            }

            // ONLY update if an explicit Activity transition was found.
            // Never overwrite with null or fall back to inaccurate usage intervals.
            if (latestPackage != null) {
                lastKnownForegroundPackage = latestPackage
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking foreground app via UsageStats", e)
        }

        return lastKnownForegroundPackage == "com.instagram.android"
    }

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
        database = AppDatabase.getInstance(applicationContext)
        notchOverlayManager = NotchOverlayManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        val notification = buildNotification(0, "Initializing Computer Vision...")
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

        // Scale proportionately to match phone's exact aspect ratio
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
            val inInstagram = isInstagramInForeground()
            ScreenCaptureManager.detector.contextDetector.isInstagramPackageInForeground = inInstagram
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
        ReelTrackerState.setServiceRunning(true)
        ReelTrackerState.updateStatus("Computer Vision active & monitoring screen")
        ScreenCaptureManager.detector.startCapture()

        // 6. Observe telemetry to update notification shade
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

        // 7. Observe detector events for Reel transitions, Room persistence, and live HUD updates
        eventCollectorJob = serviceScope.launch {
            ScreenCaptureManager.detector.events.collect { event ->
                when (event) {
                    is ReelDetectionEvent.ReelVerified -> onReelVerified(event)
                    is ReelDetectionEvent.ContextChanged -> onContextChanged(event)
                    else -> {}
                }
            }
        }

        // 8. Reset counter and immediately ensure active session & start live ticker HUD
        ReelTrackerState.resetCount()
        ScreenCaptureManager.detector.resetCount()

        val now = System.currentTimeMillis()
        ensureActiveSession(now) { sessionId ->
            activeReelStartTime = 0L
            lastReelActivityTimestamp = now
            isReelsActive = false
            startLiveTicker(sessionId)
        }
        mainHandler.post {
            notchOverlayManager.showOrUpdate(0L, 0, isInstagramActive = false, isReelsActive = false)
        }

        Log.i(TAG, "ScreenCaptureService started successfully ($width x $height @ ${config.targetFps} FPS).")
    }

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun onReelVerified(event: ReelDetectionEvent.ReelVerified) {
        val now = System.currentTimeMillis()
        commitActiveReelTime()

        activeReelStartTime = now
        lastReelActivityTimestamp = now
        isReelsActive = true

        val reelTitle = "Reel #${event.newTotal}"
        ReelTrackerState.incrementCount(reelTitle)

        ensureActiveSession(now) { sessionId ->
            val record = ReelRecord(
                sessionId = sessionId,
                dateString = getTodayDateString(),
                timestamp = now,
                creator = reelTitle,
                caption = "Verified via Optical Flow & dHash (${event.dHashDistance} bits diff)",
                audioTrack = "Instagram Reel",
                dwellTimeMs = MIN_DWELL_TIME_MS
            )

            serviceScope.launch(Dispatchers.IO) {
                try {
                    val rowId = database.reelDao().insert(record)
                    activeRecordId = rowId
                    database.reelDao().refreshSessionStats(sessionId, now)

                    withContext(Dispatchers.Main) {
                        startLiveTicker(sessionId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving ReelRecord to database", e)
                }
            }
        }
    }

    private fun onContextChanged(event: ReelDetectionEvent.ContextChanged) {
        isReelsActive = event.isReelsActive
        if (event.isReelsActive) {
            val now = System.currentTimeMillis()
            if (activeReelStartTime == 0L) {
                activeReelStartTime = now
            }
            lastReelActivityTimestamp = now
        } else {
            commitActiveReelTime()
        }
    }

    private fun startLiveTicker(sessionId: Long) {
        if (liveTickerJob?.isActive == true) return

        liveTickerJob = serviceScope.launch {
            while (isActive && isRunning) {
                val now = System.currentTimeMillis()
                val inInstagram = isInstagramInForeground()
                val isCurrentlyActive = isReelsActive && inInstagram

                val currentRecId = activeRecordId
                val currentStartTime = activeReelStartTime

                if (isCurrentlyActive && currentRecId > 0L && currentStartTime > 0L) {
                    val elapsedMs = (now - currentStartTime).coerceIn(MIN_DWELL_TIME_MS, MAX_REEL_WATCH_CAP_MS)
                    withContext(Dispatchers.IO) {
                        try {
                            database.reelDao().updateDwellTime(currentRecId, elapsedMs)
                            database.reelDao().refreshSessionStats(sessionId, now)
                        } catch (_: Exception) {}
                    }
                }

                var sessionDuration = 0L
                var sessionReels = ReelTrackerState.reelCount.value

                withContext(Dispatchers.IO) {
                    try {
                        val sess = database.reelDao().getSessionById(sessionId)
                        if (sess != null) {
                            val activeExtra = if (isCurrentlyActive && activeReelStartTime > 0) {
                                (now - activeReelStartTime).coerceAtLeast(0L)
                            } else 0L
                            sessionDuration = (sess.totalDurationMs + activeExtra).coerceAtLeast(sess.totalDurationMs)
                            sessionReels = sess.totalReels.coerceAtLeast(ReelTrackerState.reelCount.value)
                        }
                    } catch (_: Exception) {}
                }

                withContext(Dispatchers.Main) {
                    notchOverlayManager.showOrUpdate(
                        durationMs = sessionDuration,
                        reelCount = sessionReels,
                        isInstagramActive = inInstagram,
                        isReelsActive = isCurrentlyActive
                    )
                }

                delay(1000L)
            }
        }
    }

    private fun ensureActiveSession(currentTime: Long, onReady: (Long) -> Unit) {
        val isNewSessionNeeded = currentSessionId == 0L ||
                (currentTime - lastReelActivityTimestamp > SESSION_TIMEOUT_MS)

        lastReelActivityTimestamp = currentTime

        if (isNewSessionNeeded) {
            val oldSessionId = currentSessionId
            serviceScope.launch(Dispatchers.IO) {
                if (oldSessionId > 0) {
                    database.reelDao().refreshSessionStats(oldSessionId, currentTime)
                }

                val newSession = ReelSession(
                    dateString = getTodayDateString(),
                    startTime = currentTime,
                    endTime = currentTime,
                    totalReels = 0,
                    totalDurationMs = 0L
                )
                val newId = database.reelDao().insertSession(newSession)
                currentSessionId = newId
                withContext(Dispatchers.Main) {
                    onReady(newId)
                }
            }
        } else {
            onReady(currentSessionId)
        }
    }

    private fun commitActiveReelTime() {
        if (activeRecordId > 0 && activeReelStartTime > 0) {
            val now = System.currentTimeMillis()
            val elapsed = now - activeReelStartTime
            val finalDwellMs = elapsed.coerceIn(MIN_DWELL_TIME_MS, MAX_REEL_WATCH_CAP_MS)
            val idToUpdate = activeRecordId
            val sessId = currentSessionId

            serviceScope.launch(Dispatchers.IO) {
                try {
                    database.reelDao().updateDwellTime(idToUpdate, finalDwellMs)
                    if (sessId > 0) {
                        database.reelDao().refreshSessionStats(sessId, now)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error committing dwell time", e)
                }
            }
            activeRecordId = 0L
            activeReelStartTime = 0L
        }
    }

    private fun closeCurrentSessionIfActive() {
        val sessId = currentSessionId
        if (sessId > 0) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    database.reelDao().refreshSessionStats(sessId, System.currentTimeMillis())
                } catch (_: Exception) {}
            }
        }
    }

    private fun stopCapture() {
        if (!isRunning) return
        isRunning = false

        notificationJob?.cancel()
        notificationJob = null

        eventCollectorJob?.cancel()
        eventCollectorJob = null

        liveTickerJob?.cancel()
        liveTickerJob = null

        commitActiveReelTime()
        closeCurrentSessionIfActive()

        try {
            notchOverlayManager.destroy()
        } catch (_: Exception) {}

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
        ReelTrackerState.setServiceRunning(false)
        ReelTrackerState.updateStatus("Computer Vision stopped")
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
            .setContentTitle("ScrollMeter CV: $reelCount Reels Counted")
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
                "ScrollMeter Computer Vision",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live Instagram Reels count and computer-vision detector status."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
