package com.scrollmeter.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.scrollmeter.app.data.AppDatabase
import com.scrollmeter.app.data.ReelRecord
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

    // Track active reel state
    private var activeFingerprint: String = ""
    private var isCurrentReelCounted: Boolean = false
    private var lastScanTimestamp: Long = 0L

    // Cache of recently counted reels to prevent double-counting when app switching
    private val recentlyCountedReels = ArrayDeque<String>(20)

    companion object {
        private const val TAG = "ScrollMeterService"
        const val INSTAGRAM_PKG = "com.instagram.android"
        
        // 1.0s dwell threshold: rapid swipes (< 1000ms) are skipped,
        // watching for >= 1.0s confirms an intentional Reel view.
        const val MIN_DWELL_TIME_MS = 1000L
        
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
        dwellJob?.cancel()
        ReelTrackerState.setServiceRunning(false)
        ReelTrackerState.updateStatus("Accessibility Service stopped")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventPkg = event.packageName?.toString() ?: ""
        if (eventPkg != INSTAGRAM_PKG) {
            if (!isCurrentReelCounted) {
                dwellJob?.cancel()
            }
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
                return
            }

            inspectInstagramTree(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting node tree", e)
        }
    }

    private fun inspectInstagramTree(root: AccessibilityNodeInfo) {
        var detectedCreator = ""
        var detectedAudioOrCaption = ""
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

            // Check if user is currently inside Reels
            if (viewId.contains("clips", ignoreCase = true) ||
                viewId.contains("reel", ignoreCase = true) ||
                desc?.startsWith("Reel by", ignoreCase = true) == true
            ) {
                isReelsSurface = true
            }

            // Extract Creator from Instagram's Reels content descriptions
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
                    detectedAudioOrCaption = desc.take(40)
                }
            }

            // Extract candidate texts
            if (!text.isNullOrEmpty() && text.length < 80) {
                if (text !in setOf("Follow", "Following", "Reels", "Audio", "Liked by", "Share", "Comment", "Ad", "Sponsored", "More") &&
                    !text.startsWith("Like number", ignoreCase = true) &&
                    !text.startsWith("Comment number", ignoreCase = true) &&
                    !text.startsWith("See translation", ignoreCase = true) &&
                    !text.all { it.isDigit() || it == ',' || it == '.' || it == 'K' || it == 'M' }
                ) {
                    candidateTexts.add(text)
                }
            }

            for (i in 0 until node.childCount) {
                scan(node.getChild(i))
            }
        }

        scan(root)

        if (!isReelsSurface && detectedCreator.isEmpty()) {
            return
        }

        val fingerprint = when {
            detectedCreator.isNotEmpty() -> {
                if (detectedAudioOrCaption.isNotEmpty()) "$detectedCreator | $detectedAudioOrCaption" else detectedCreator
            }
            candidateTexts.isNotEmpty() -> candidateTexts.first()
            else -> ""
        }

        if (fingerprint.isBlank()) return

        if (fingerprint != activeFingerprint) {
            handleReelTransition(fingerprint)
        }
    }

    private fun handleReelTransition(newFingerprint: String) {
        dwellJob?.cancel()

        activeFingerprint = newFingerprint
        ReelTrackerState.updateCurrentCandidate(newFingerprint)

        if (recentlyCountedReels.contains(newFingerprint)) {
            isCurrentReelCounted = true
            Log.d(TAG, "Reel already counted previously: $newFingerprint (Skipping recount)")
            return
        }

        isCurrentReelCounted = false
        Log.i(TAG, "New Reel Candidate: $newFingerprint")

        dwellJob = serviceScope.launch {
            delay(MIN_DWELL_TIME_MS)
            if (!isCurrentReelCounted && activeFingerprint == newFingerprint) {
                isCurrentReelCounted = true
                
                if (recentlyCountedReels.size >= 15) {
                    recentlyCountedReels.removeFirst()
                }
                recentlyCountedReels.addLast(newFingerprint)

                // Persist confirmed Reel view into SQLite via Room
                val record = ReelRecord(
                    dateString = getTodayDateString(),
                    timestamp = System.currentTimeMillis(),
                    creator = newFingerprint,
                    dwellTimeMs = MIN_DWELL_TIME_MS
                )
                launch(Dispatchers.IO) {
                    try {
                        database.reelDao().insert(record)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error inserting into Room database", e)
                    }
                }

                ReelTrackerState.incrementCount(newFingerprint)
                Log.i(TAG, "Reel Saved to DB (#${ReelTrackerState.reelCount.value}): $newFingerprint")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ScrollMeter Accessibility Service Interrupted")
    }
}
