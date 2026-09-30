package com.scrollmeter.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.scrollmeter.app.data.AppDatabase
import com.scrollmeter.app.data.ReelRecord
import com.scrollmeter.app.data.ReelSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InstagramAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var dwellJob: Job? = null
    private lateinit var database: AppDatabase

    // Session tracking
    private var currentSessionId: Long = 0L
    private var lastReelActivityTimestamp: Long = 0L

    // Active reel tracking
    private var activeFingerprint: String = ""
    private var isCurrentReelCounted: Boolean = false
    private var activeReelStartTime: Long = 0L
    private var activeRecordId: Long = 0L
    private var lastScanTimestamp: Long = 0L

    // Deduplication cache
    private val recentlyCountedReels = ArrayDeque<String>(20)

    companion object {
        private const val TAG = "ScrollMeterService"
        const val INSTAGRAM_PKG = "com.instagram.android"
        
        // 1.0s dwell threshold
        const val MIN_DWELL_TIME_MS = 1000L
        
        // Cap single Reel watch time to 120s
        const val MAX_REEL_WATCH_CAP_MS = 120_000L
        
        // If user is away from Reels for > 90 seconds, close session
        const val SESSION_TIMEOUT_MS = 90_000L

        private const val SCAN_THROTTLE_MS = 150L

        fun getTodayDateString(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(applicationContext)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.notificationTimeout = 80
            serviceInfo = info
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring serviceInfo", e)
        }

        Log.i(TAG, "ScrollMeter Accessibility Service Connected")
        ReelTrackerState.setServiceRunning(true)
        ReelTrackerState.updateStatus("Accessibility Service active and monitoring Instagram")
    }

    override fun onDestroy() {
        super.onDestroy()
        commitActiveReelTime()
        closeCurrentSessionIfActive()
        dwellJob?.cancel()
        ReelTrackerState.setServiceRunning(false)
        ReelTrackerState.updateStatus("Accessibility Service stopped")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventPkg = event.packageName?.toString() ?: ""
        if (eventPkg != INSTAGRAM_PKG) {
            commitActiveReelTime()
            if (!isCurrentReelCounted) {
                dwellJob?.cancel()
            }
            activeFingerprint = ""
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastScanTimestamp < SCAN_THROTTLE_MS) {
            return
        }
        lastScanTimestamp = currentTime

        val rootNode = rootInActiveWindow ?: return
        try {
            val rootPkg = rootNode.packageName?.toString() ?: ""
            if (rootPkg != INSTAGRAM_PKG) {
                commitActiveReelTime()
                activeFingerprint = ""
                return
            }

            inspectInstagramTree(rootNode, currentTime)
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting node tree", e)
        }
    }

    private fun inspectInstagramTree(root: AccessibilityNodeInfo, currentTime: Long) {
        var detectedCreator = ""
        var detectedAudio = ""
        var detectedCaption = ""
        var isReelsSurface = false
        val candidateTexts = mutableListOf<String>()

        var scannedNodes = 0
        val maxNodesToScan = 350

        fun scan(node: AccessibilityNodeInfo?) {
            if (node == null || scannedNodes >= maxNodesToScan) return
            scannedNodes++

            val desc = node.contentDescription?.toString()?.trim()
            val text = node.text?.toString()?.trim()
            val viewId = node.viewIdResourceName ?: ""

            // Detect Reels viewer surface
            if (viewId.contains("clips", ignoreCase = true) ||
                viewId.contains("reel", ignoreCase = true) ||
                desc?.startsWith("Reel by", ignoreCase = true) == true
            ) {
                isReelsSurface = true
            }

            // Extract Creator
            if (!desc.isNullOrEmpty()) {
                if (desc.startsWith("Reel by ", ignoreCase = true)) {
                    isReelsSurface = true
                    val raw = desc.removePrefix("Reel by ").removePrefix("reel by ")
                    val creator = raw.substringBefore(".").trim()
                    if (creator.isNotEmpty()) {
                        detectedCreator = creator
                    }
                } else if (desc.startsWith("Profile picture of ", ignoreCase = true)) {
                    val creator = desc.removePrefix("Profile picture of ").trim()
                    if (creator.isNotEmpty() && detectedCreator.isEmpty()) {
                        detectedCreator = creator
                    }
                } else if (desc.contains("Original audio", ignoreCase = true) || desc.contains("Audio", ignoreCase = true)) {
                    detectedAudio = desc.take(60)
                }
            }

            // Extract caption & audio text
            if (!text.isNullOrEmpty()) {
                if (text.contains("Original audio", ignoreCase = true)) {
                    detectedAudio = text.take(60)
                } else if (text !in setOf("Follow", "Following", "Reels", "Audio", "Liked by", "Share", "Comment", "Ad", "Sponsored", "More") &&
                    !text.startsWith("Like number", ignoreCase = true) &&
                    !text.startsWith("Comment number", ignoreCase = true) &&
                    !text.startsWith("See translation", ignoreCase = true) &&
                    !text.all { it.isDigit() || it == ',' || it == '.' || it == 'K' || it == 'M' }
                ) {
                    if (text.length > 20 && detectedCaption.isEmpty()) {
                        detectedCaption = text.take(120)
                    } else if (text.length < 80) {
                        candidateTexts.add(text)
                    }
                }
            }

            for (i in 0 until node.childCount) {
                scan(node.getChild(i))
            }
        }

        scan(root)

        if (!isReelsSurface && detectedCreator.isEmpty()) {
            commitActiveReelTime()
            activeFingerprint = ""
            return
        }

        val pageName = when {
            detectedCreator.isNotEmpty() -> detectedCreator
            candidateTexts.isNotEmpty() -> candidateTexts.first()
            else -> ""
        }

        if (pageName.isBlank()) return

        val fingerprint = if (detectedAudio.isNotEmpty()) "$pageName | $detectedAudio" else pageName

        if (fingerprint != activeFingerprint) {
            handleReelTransition(
                pageName = pageName,
                audioName = detectedAudio,
                caption = detectedCaption,
                fingerprint = fingerprint,
                transitionTime = currentTime
            )
        }
    }

    private fun handleReelTransition(
        pageName: String,
        audioName: String,
        caption: String,
        fingerprint: String,
        transitionTime: Long
    ) {
        commitActiveReelTime()
        dwellJob?.cancel()

        activeFingerprint = fingerprint
        activeReelStartTime = transitionTime
        activeRecordId = 0L
        ReelTrackerState.updateCurrentCandidate(fingerprint)

        if (recentlyCountedReels.contains(fingerprint)) {
            isCurrentReelCounted = true
            return
        }

        isCurrentReelCounted = false

        dwellJob = serviceScope.launch {
            delay(MIN_DWELL_TIME_MS)
            if (!isCurrentReelCounted && activeFingerprint == fingerprint) {
                isCurrentReelCounted = true

                if (recentlyCountedReels.size >= 15) {
                    recentlyCountedReels.removeFirst()
                }
                recentlyCountedReels.addLast(fingerprint)

                // Ensure active session exists or create a new session
                ensureActiveSession(transitionTime) { sessionId ->
                    val record = ReelRecord(
                        sessionId = sessionId,
                        dateString = getTodayDateString(),
                        timestamp = System.currentTimeMillis(),
                        creator = pageName,
                        caption = caption,
                        audioTrack = audioName,
                        dwellTimeMs = MIN_DWELL_TIME_MS
                    )
                    launch(Dispatchers.IO) {
                        try {
                            val rowId = database.reelDao().insert(record)
                            activeRecordId = rowId
                            database.reelDao().refreshSessionStats(sessionId, System.currentTimeMillis())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error inserting ReelRecord", e)
                        }
                    }
                }

                ReelTrackerState.incrementCount(fingerprint)
                Log.i(TAG, "Reel Recorded in Session #$currentSessionId: $pageName")
            }
        }
    }

    private fun ensureActiveSession(currentTime: Long, onReady: (Long) -> Unit) {
        // If away for more than 90 seconds, start a brand new session
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
                    totalReels = 1,
                    totalDurationMs = MIN_DWELL_TIME_MS
                )
                val newId = database.reelDao().insertSession(newSession)
                currentSessionId = newId
                launch(Dispatchers.Main) {
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
                    Log.e(TAG, "Error updating dwell time", e)
                }
            }
            activeRecordId = 0L
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

    override fun onInterrupt() {
        commitActiveReelTime()
        closeCurrentSessionIfActive()
        Log.w(TAG, "ScrollMeter Accessibility Service Interrupted")
    }
}
