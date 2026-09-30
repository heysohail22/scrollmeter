package com.scrollmeter.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class InstagramAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var dwellJob: Job? = null

    private var activeFingerprint: String = ""
    private var isCurrentReelCounted: Boolean = false
    private var lastScanTimestamp: Long = 0L

    companion object {
        private const val TAG = "ScrollMeterService"
        private const val INSTAGRAM_PKG = "com.instagram.android"
        
        // 1.0s dwell threshold: fast flickers/swipes (< 1000ms) are skipped,
        // watching a reel for >= 1s counts as a verified view.
        const val MIN_DWELL_TIME_MS = 1000L
        
        // Throttle full scans to avoid high CPU usage
        private const val SCAN_THROTTLE_MS = 150L
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

        val pkg = event.packageName?.toString() ?: ""
        if (pkg != INSTAGRAM_PKG) {
            if (activeFingerprint.isNotEmpty() && !isCurrentReelCounted) {
                dwellJob?.cancel()
                activeFingerprint = ""
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
            inspectInstagramTree(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting node tree", e)
        }
    }

    private fun inspectInstagramTree(root: AccessibilityNodeInfo) {
        var detectedCreator = ""
        var detectedAudioOrCaption = ""
        val candidateTexts = mutableListOf<String>()

        var scannedNodes = 0
        val maxNodesToScan = 350

        // Traverse the tree to extract Reel identifiers
        fun scan(node: AccessibilityNodeInfo?) {
            if (node == null || scannedNodes >= maxNodesToScan) return
            scannedNodes++

            val desc = node.contentDescription?.toString()?.trim()
            val text = node.text?.toString()?.trim()

            // 1. High-accuracy check: Instagram video container description
            // Example: "Reel by querysurge. Double tap to play or pause."
            if (!desc.isNullOrEmpty()) {
                if (desc.startsWith("Reel by ", ignoreCase = true)) {
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

            // 2. Collect visible texts for fallback identification
            if (!text.isNullOrEmpty() && text.length < 80) {
                if (text !in setOf("Follow", "Following", "Reels", "Audio", "Liked by", "Share", "Comment", "Ad", "Sponsored") &&
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

        // Construct stable fingerprint
        val fingerprint = when {
            detectedCreator.isNotEmpty() -> {
                if (detectedAudioOrCaption.isNotEmpty()) "$detectedCreator | $detectedAudioOrCaption" else detectedCreator
            }
            candidateTexts.isNotEmpty() -> {
                val first = candidateTexts.first()
                val second = candidateTexts.getOrNull(1)?.take(30) ?: ""
                if (second.isNotEmpty()) "$first | $second" else first
            }
            else -> ""
        }

        if (fingerprint.isBlank()) return

        if (fingerprint != activeFingerprint) {
            handleReelTransition(fingerprint)
        }
    }

    private fun handleReelTransition(newFingerprint: String) {
        Log.i(TAG, "Reel Candidate Detected: $newFingerprint")
        dwellJob?.cancel()

        activeFingerprint = newFingerprint
        isCurrentReelCounted = false
        ReelTrackerState.updateCurrentCandidate(newFingerprint)

        dwellJob = serviceScope.launch {
            delay(MIN_DWELL_TIME_MS)
            if (!isCurrentReelCounted && activeFingerprint == newFingerprint) {
                isCurrentReelCounted = true
                ReelTrackerState.incrementCount(newFingerprint)
                Log.i(TAG, "Reel Counted (#${ReelTrackerState.reelCount.value}): $newFingerprint")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ScrollMeter Accessibility Service Interrupted")
    }
}
