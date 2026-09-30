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
        
        // Minimum time (in milliseconds) a Reel must be viewed to count as a real view
        // Fast swipes (< 1200ms) will be ignored as skipped/doomscroll flickers.
        const val MIN_DWELL_TIME_MS = 1200L
        
        // Throttle node inspection to avoid CPU overhead
        private const val SCAN_THROTTLE_MS = 200L
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
            info.notificationTimeout = 100
            serviceInfo = info
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring serviceInfo", e)
        }

        Log.d(TAG, "ScrollMeter Accessibility Service Connected")
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
            // Left Instagram: cancel any unverified dwell job
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
            inspectInstagramTree(rootNode, currentTime)
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting node tree", e)
        }
    }

    private fun inspectInstagramTree(root: AccessibilityNodeInfo, eventTime: Long) {
        // Collect visible text elements and identifiable anchors
        val texts = mutableListOf<String>()
        var hasReelsIndicators = false

        traverseNodes(root, depth = 0, maxDepth = 25) { node ->
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val viewId = node.viewIdResourceName ?: ""

            if (!text.isNullOrEmpty() && text.length < 100) {
                texts.add(text)
            }

            // Detect indicators that user is on a Reels surface
            if (viewId.contains("clips", ignoreCase = true) ||
                viewId.contains("reel", ignoreCase = true) ||
                desc?.contains("like", ignoreCase = true) == true ||
                desc?.contains("comment", ignoreCase = true) == true ||
                desc?.contains("share", ignoreCase = true) == true ||
                desc?.contains("audio", ignoreCase = true) == true ||
                text?.contains("Original audio", ignoreCase = true) == true ||
                text?.equals("Follow", ignoreCase = true) == true ||
                text?.equals("Reels", ignoreCase = true) == true
            ) {
                hasReelsIndicators = true
            }
        }

        if (!hasReelsIndicators || texts.isEmpty()) {
            return
        }

        // Generate a fingerprint representing the current Reel in view.
        val candidateFingerprint = deriveReelFingerprint(texts)

        if (candidateFingerprint.isBlank()) {
            return
        }

        // Check if transition to a new Reel occurred
        if (candidateFingerprint != activeFingerprint) {
            handleReelTransition(candidateFingerprint)
        }
    }

    private fun deriveReelFingerprint(texts: List<String>): String {
        // Filter out generic UI labels
        val filtered = texts.filter { item ->
            item !in setOf("Follow", "Following", "Reels", "Audio", "Liked by", "Share", "Comment") &&
            !item.startsWith("See translation", ignoreCase = true) &&
            !item.all { it.isDigit() || it == ',' || it == '.' || it == 'K' || it == 'M' }
        }

        if (filtered.isEmpty()) return ""

        val primary = filtered.firstOrNull() ?: ""
        val secondary = filtered.getOrNull(1)?.take(30) ?: ""
        
        return if (secondary.isNotEmpty()) "$primary | $secondary" else primary
    }

    private fun handleReelTransition(newFingerprint: String) {
        Log.d(TAG, "Reel Candidate Detected: $newFingerprint")
        dwellJob?.cancel()

        activeFingerprint = newFingerprint
        isCurrentReelCounted = false
        ReelTrackerState.updateCurrentCandidate(newFingerprint)

        // Start dwell timer. Only if the user remains on this Reel for >= MIN_DWELL_TIME_MS
        // will it register as an actual viewed Reel.
        dwellJob = serviceScope.launch {
            delay(MIN_DWELL_TIME_MS)
            if (!isCurrentReelCounted && activeFingerprint == newFingerprint) {
                isCurrentReelCounted = true
                ReelTrackerState.incrementCount(newFingerprint)
                Log.d(TAG, "Reel Counted! Fingerprint: $newFingerprint")
            }
        }
    }

    private fun traverseNodes(
        node: AccessibilityNodeInfo?,
        depth: Int,
        maxDepth: Int,
        onNode: (AccessibilityNodeInfo) -> Unit
    ) {
        if (node == null || depth > maxDepth) return
        onNode(node)

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNodes(child, depth + 1, maxDepth, onNode)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ScrollMeter Accessibility Service Interrupted")
    }
}
