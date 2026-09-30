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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InstagramAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var dwellJob: Job? = null
    private var liveTickerJob: Job? = null
    private lateinit var database: AppDatabase
    private lateinit var notchOverlayManager: NotchOverlayManager

    // Session tracking
    private var currentSessionId: Long = 0L
    private var lastReelActivityTimestamp: Long = 0L

    // Active reel tracking
    private var activeCreator: String = ""
    private var activeCaption: String = ""
    private var activeAudio: String = ""
    private var activeReelStartTime: Long = 0L
    private var activeRecordId: Long = 0L
    private var lastScanTimestamp: Long = 0L

    // Deduplication cache
    private val recentlyCountedReels = ArrayDeque<String>(20)

    companion object {
        private const val TAG = "ScrollMeterService"
        const val INSTAGRAM_PKG = "com.instagram.android"
        
        // 1.0s dwell threshold to verify genuine viewing
        const val MIN_DWELL_TIME_MS = 1000L
        
        // Cap single Reel watch time to 180s (3 minutes) to prevent runaways
        const val MAX_REEL_WATCH_CAP_MS = 180_000L
        
        // If user is away from Reels for > 90 seconds, close session
        const val SESSION_TIMEOUT_MS = 90_000L

        private const val SCAN_THROTTLE_MS = 150L

        private val USERNAME_REGEX = Regex("^[a-zA-Z0-9._]{2,30}$")

        // Blacklist common UI buttons, actions, and system labels that must NEVER be treated as creators
        private val SYSTEM_BLACKLIST = setOf(
            "playback", "play", "pause", "liked", "like", "unlike",
            "comment", "comments", "share", "shares", "reshare", "remix",
            "audio", "original", "follow", "following", "more", "suggested",
            "sponsored", "ad", "watch", "reply", "replies", "report",
            "hide", "save", "saved", "translate", "translation", "send",
            "direct", "verified", "back", "done", "close", "reels", "reel",
            "feed", "search", "explore", "activity", "profile", "post", "posts",
            "threads", "meta", "shop", "tag", "tagged", "tagged products",
            "view", "views", "insights", "boost", "boost post", "home", "notifications",
            "interested", "about", "account", "manage", "link", "copy", "menu",
            "options", "transparency", "details", "control", "controls", "why",
            "sequence", "unfollow", "mute", "block", "restrict", "info", "settings", "not"
        )

        fun getTodayDateString(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(applicationContext)
        notchOverlayManager = NotchOverlayManager(this)
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
        liveTickerJob?.cancel()
        dwellJob?.cancel()
        notchOverlayManager.destroy()
        ReelTrackerState.setServiceRunning(false)
        ReelTrackerState.updateStatus("Accessibility Service stopped")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventPkg = event.packageName?.toString() ?: ""
        // Crucial: Ignore events from our own app and floating overlay so we never self-interrupt!
        if (eventPkg == packageName || eventPkg == "com.scrollmeter.app") {
            return
        }

        if (eventPkg != INSTAGRAM_PKG) {
            // Only hide and commit if the user actually navigated away to a different app window
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val activePkg = rootInActiveWindow?.packageName?.toString() ?: ""
                if (activePkg.isNotEmpty() && activePkg != INSTAGRAM_PKG && activePkg != packageName && activePkg != "com.scrollmeter.app") {
                    commitActiveReelTime()
                    liveTickerJob?.cancel()
                    dwellJob?.cancel()
                    activeCreator = ""
                    notchOverlayManager.hide()
                }
            }
            // Do NOT wipe activeCreator on transient non-Instagram events (status bar, notifications, keyboard, etc.)
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
            if (rootPkg == packageName || rootPkg == "com.scrollmeter.app") {
                return
            }
            if (rootPkg != INSTAGRAM_PKG) {
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
        var isModalOrSheetOpen = false
        val candidateTexts = mutableListOf<String>()

        var scannedNodes = 0
        val maxNodesToScan = 300

        fun scan(node: AccessibilityNodeInfo?) {
            if (node == null || scannedNodes >= maxNodesToScan) return
            scannedNodes++

            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            // 1. Detect if Comment sheet, 3-dots menu, or "About this reel" modal is open
            val isButton = viewId.contains("button", ignoreCase = true) ||
                    viewId.contains("btn", ignoreCase = true)
            if (!isButton && (
                viewId.contains("comment_composer", ignoreCase = true) ||
                viewId.contains("comments_recycler", ignoreCase = true) ||
                viewId.contains("layout_comment_thread", ignoreCase = true) ||
                viewId.contains("comment_sheet", ignoreCase = true) ||
                viewId.contains("bottom_sheet", ignoreCase = true) ||
                viewId.contains("action_sheet", ignoreCase = true) ||
                text.equals("Comments", ignoreCase = true) ||
                text.startsWith("Add a comment", ignoreCase = true) ||
                text.startsWith("Comment as", ignoreCase = true) ||
                desc.equals("Comments sheet", ignoreCase = true) ||
                text.contains("About this reel", ignoreCase = true) ||
                text.contains("About this account", ignoreCase = true) ||
                text.contains("Why you're seeing this", ignoreCase = true) ||
                text.contains("Not interested", ignoreCase = true) ||
                text.contains("Manage interested", ignoreCase = true) ||
                text.equals("Interested", ignoreCase = true) ||
                text.equals("Report", ignoreCase = true) ||
                text.equals("Copy link", ignoreCase = true) ||
                text.equals("Share to...", ignoreCase = true)
            )) {
                isModalOrSheetOpen = true
            }

            // 2. Detect Reels viewer surface
            if (viewId.contains("clips", ignoreCase = true) ||
                viewId.contains("reel", ignoreCase = true) ||
                desc.startsWith("Reel by", ignoreCase = true)
            ) {
                isReelsSurface = true
            }

            // 3. Extract Creator, Audio, and Caption from content descriptions
            if (desc.isNotEmpty()) {
                if (desc.startsWith("Reel by ", ignoreCase = true)) {
                    isReelsSurface = true
                    val raw = desc.removePrefix("Reel by ").removePrefix("reel by ")
                    val creator = raw.substringBefore("•").substringBefore(".").substringBefore(",").trim()
                    if (isValidUsername(creator)) {
                        detectedCreator = creator
                    }
                    if (raw.contains("•")) {
                        val audioPart = raw.substringAfter("•").substringBefore(".").substringBefore(",").trim()
                        if (audioPart.isNotEmpty() && !SYSTEM_BLACKLIST.contains(audioPart.lowercase())) {
                            detectedAudio = audioPart.take(80)
                        }
                    }
                } else if (desc.contains(" · ") ||
                    desc.contains("Original audio", ignoreCase = true) ||
                    desc.startsWith("Audio:", ignoreCase = true) ||
                    (desc.contains("audio", ignoreCase = true) && desc.length > 5 && !desc.equals("Audio", ignoreCase = true))
                ) {
                    val cleanAudio = desc.removePrefix("Audio:").removePrefix("audio:").trim()
                    if (cleanAudio.isNotEmpty() && !SYSTEM_BLACKLIST.contains(cleanAudio.lowercase()) && cleanAudio != detectedCreator) {
                        detectedAudio = cleanAudio.take(80)
                        Log.i(TAG, "Captured AudioTrack from desc: '$detectedAudio'")
                    }
                } else if (detectedCaption.isEmpty() && isCaptionForCurrentReel(desc, detectedCreator) && desc != detectedAudio) {
                    detectedCaption = cleanCaptionText(desc, detectedCreator)
                }
            }

            // 4. Extract Creator from explicit author View IDs (fallback only if desc did not have "Reel by")
            if (detectedCreator.isEmpty() && !viewId.contains("comment", ignoreCase = true)) {
                if (viewId.contains("clips_author") || viewId.contains("clips_creator") ||
                    (viewId.contains("profile_name") && !viewId.contains("comment", ignoreCase = true))
                ) {
                    val cleanText = text.removePrefix("@").trim()
                    if (isValidUsername(cleanText)) {
                        detectedCreator = cleanText
                    }
                }
            }

            // 5. Extract caption & audio text & candidate usernames (only outside sheets/menus)
            if (text.isNotEmpty() && !isModalOrSheetOpen) {
                val isSystemLabel = SYSTEM_BLACKLIST.contains(text.lowercase()) ||
                        text.startsWith("Like number", ignoreCase = true) ||
                        text.startsWith("Comment number", ignoreCase = true) ||
                        text.startsWith("Reshare number", ignoreCase = true) ||
                        text.startsWith("Re-share number", ignoreCase = true) ||
                        text.startsWith("Share number", ignoreCase = true) ||
                        text.startsWith("See translation", ignoreCase = true) ||
                        text.startsWith("Liked by", ignoreCase = true) ||
                        text.contains("posts tagged", ignoreCase = true) ||
                        text.contains("and others", ignoreCase = true) ||
                        text.contains("and 1 other", ignoreCase = true) ||
                        text.matches(Regex(".*and \\d+ others?.*", RegexOption.IGNORE_CASE)) ||
                        text.all { it.isDigit() || it == ',' || it == '.' || it == 'K' || it == 'M' || it == ' ' }

                val isMusicView = viewId.contains("music", ignoreCase = true) ||
                        viewId.contains("audio", ignoreCase = true)

                val isAudioText = isMusicView ||
                        text.contains("Original audio", ignoreCase = true) ||
                        (text.contains(" · ") && text.length in 4..80 && !isSystemLabel)

                if (isAudioText && detectedAudio.isEmpty()) {
                    val cleanAudio = text.trim()
                    if (cleanAudio.isNotEmpty() && !SYSTEM_BLACKLIST.contains(cleanAudio.lowercase())) {
                        detectedAudio = cleanAudio.take(80)
                        Log.i(TAG, "Found AudioTrack text: '$detectedAudio' from viewId='$viewId'")
                    }
                } else if (!isSystemLabel && !viewId.contains("comment", ignoreCase = true)) {
                    if (viewId.contains("caption", ignoreCase = true)) {
                        if (isCaptionForCurrentReel(text, detectedCreator)) {
                            detectedCaption = cleanCaptionText(text, detectedCreator)
                        }
                    } else if (text.length > 15 && detectedCaption.isEmpty() && text != detectedAudio) {
                        if (isCaptionForCurrentReel(text, detectedCreator)) {
                            detectedCaption = cleanCaptionText(text, detectedCreator)
                        }
                    } else if (text.length in 2..30 && detectedCreator.isEmpty() && candidateTexts.isEmpty()) {
                        val candidate = text.removePrefix("@").trim()
                        if (isValidUsername(candidate)) {
                            candidateTexts.add(candidate)
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                scan(node.getChild(i))
            }
        }

        scan(root)

        // If comment sheet or modal menu ("About this reel", etc.) is open, user is still on active reel
        if (isModalOrSheetOpen) {
            if (activeCreator.isNotBlank()) {
                lastReelActivityTimestamp = currentTime
            }
            return
        }

        val pageName = when {
            detectedCreator.isNotEmpty() -> detectedCreator
            candidateTexts.isNotEmpty() -> candidateTexts.first()
            else -> ""
        }

        if (pageName.isBlank()) return

        // 1. Check if this is the SAME Reel currently playing
        val isSameReel = activeCreator.isNotBlank() &&
                pageName.equals(activeCreator, ignoreCase = true)

        if (isSameReel) {
            // Update last activity timestamp
            lastReelActivityTimestamp = currentTime

            // Enrich caption/audio if they loaded after initial detection
            if (activeRecordId > 0L && (detectedAudio.isNotEmpty() || detectedCaption.isNotEmpty())) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        database.reelDao().updateMetadataIfEmpty(activeRecordId, detectedCaption, detectedAudio)
                    } catch (_: Exception) {}
                }
            }
            return
        }

        Log.i(TAG, "New Reel candidate: $pageName (previous: $activeCreator)")

        // 2. Different Reel detected -> Handle transition
        handleReelTransition(
            pageName = pageName,
            audioName = detectedAudio,
            caption = detectedCaption,
            transitionTime = currentTime
        )
    }

    private fun isValidUsername(name: String): Boolean {
        if (name.length !in 2..30) return false
        if (SYSTEM_BLACKLIST.contains(name.lowercase())) return false
        return USERNAME_REGEX.matches(name)
    }

    private fun isCaptionForCurrentReel(candidate: String, creator: String): Boolean {
        val trimmed = candidate.trim()
        if (trimmed.length < 3) return false
        if (trimmed.contains("posted a video", ignoreCase = true)) return false
        if (trimmed.contains("Double tap", ignoreCase = true)) return false
        if (trimmed.contains("View likes", ignoreCase = true)) return false
        if (trimmed.contains("View comments", ignoreCase = true)) return false
        if (trimmed.startsWith("Follow", ignoreCase = true)) return false
        if (SYSTEM_BLACKLIST.contains(trimmed.lowercase())) return false
        if (creator.isNotEmpty()) {
            val firstWord = trimmed.substringBefore(" ").substringBefore("\n").trim().removePrefix("@")
            // If the caption begins with a different creator handle, it's from an off-screen cached page!
            if (isValidUsername(firstWord) && !firstWord.equals(creator, ignoreCase = true)) {
                return false
            }
        }
        return true
    }

    private fun cleanCaptionText(raw: String, creator: String): String {
        var c = raw.trim()
        if (creator.isNotEmpty() && c.startsWith(creator, ignoreCase = true)) {
            c = c.removePrefix(creator).trim()
        }
        return c.take(160)
    }

    private fun handleReelTransition(
        pageName: String,
        audioName: String,
        caption: String,
        transitionTime: Long
    ) {
        // Commit dwell time for the previous Reel
        commitActiveReelTime()
        liveTickerJob?.cancel()
        dwellJob?.cancel()

        activeCreator = pageName
        activeCaption = caption
        activeAudio = audioName
        activeReelStartTime = transitionTime
        activeRecordId = 0L

        ReelTrackerState.updateCurrentCandidate(pageName)

        val isAlreadyCounted = recentlyCountedReels.contains(pageName)

        dwellJob = serviceScope.launch {
            delay(MIN_DWELL_TIME_MS)
            
            // Confirm the user stayed on this reel for at least MIN_DWELL_TIME_MS
            if (activeCreator.equals(pageName, ignoreCase = true)) {
                if (!isAlreadyCounted) {
                    if (recentlyCountedReels.size >= 15) {
                        recentlyCountedReels.removeFirst()
                    }
                    recentlyCountedReels.addLast(pageName)
                    ReelTrackerState.incrementCount(pageName)
                }

                // Ensure an active session exists (or create one)
                ensureActiveSession(transitionTime) { sessionId ->
                    val record = ReelRecord(
                        sessionId = sessionId,
                        dateString = getTodayDateString(),
                        timestamp = transitionTime,
                        creator = pageName,
                        caption = caption,
                        audioTrack = audioName,
                        dwellTimeMs = MIN_DWELL_TIME_MS
                    )

                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            val rowId = database.reelDao().insert(record)
                            activeRecordId = rowId
                            database.reelDao().refreshSessionStats(sessionId, System.currentTimeMillis())

                            // Launch live ticker to continually update dwell time every second
                            withContext(Dispatchers.Main) {
                                startLiveTicker(rowId, sessionId, transitionTime)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error inserting ReelRecord", e)
                        }
                    }
                }

                Log.i(TAG, "Reel Confirmed: $pageName (Session #$currentSessionId)")
            }
        }
    }

    private fun startLiveTicker(recordId: Long, sessionId: Long, startTime: Long) {
        liveTickerJob?.cancel()
        liveTickerJob = serviceScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val elapsedMs = (now - startTime).coerceIn(MIN_DWELL_TIME_MS, MAX_REEL_WATCH_CAP_MS)
                
                var sessionDuration = 0L
                var sessionReels = 1

                withContext(Dispatchers.IO) {
                    try {
                        database.reelDao().updateDwellTime(recordId, elapsedMs)
                        database.reelDao().refreshSessionStats(sessionId, now)
                        val sess = database.reelDao().getSessionById(sessionId)
                        if (sess != null) {
                            sessionDuration = sess.totalDurationMs
                            sessionReels = sess.totalReels
                        }
                    } catch (_: Exception) {}
                }

                withContext(Dispatchers.Main) {
                    notchOverlayManager.showOrUpdate(sessionDuration, sessionReels)
                }

                delay(1000L)
            }
        }
    }

    private fun ensureActiveSession(currentTime: Long, onReady: (Long) -> Unit) {
        // If away for more than 90 seconds, close previous session and start a new one
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
                withContext(Dispatchers.Main) {
                    onReady(newId)
                }
            }
        } else {
            onReady(currentSessionId)
        }
    }

    private fun commitActiveReelTime() {
        liveTickerJob?.cancel()
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
        liveTickerJob?.cancel()
        dwellJob?.cancel()
        notchOverlayManager.hide()
        Log.w(TAG, "ScrollMeter Accessibility Service Interrupted")
    }
}
