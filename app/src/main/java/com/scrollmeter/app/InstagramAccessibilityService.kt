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
        
        // 300ms threshold to reliably capture fast scrolling and flicking through reels
        const val MIN_DWELL_TIME_MS = 300L
        
        // Cap single Reel watch time to 180s (3 minutes) to prevent runaways
        const val MAX_REEL_WATCH_CAP_MS = 180_000L
        
        // If user is away from Reels for > 90 seconds, close session
        const val SESSION_TIMEOUT_MS = 90_000L

        private const val SCAN_THROTTLE_MS = 80L

        private val USERNAME_REGEX = Regex("^[a-zA-Z0-9._]{2,30}$")

        @Volatile
        var isScrollMeterForeground: Boolean = false
            private set

        var instance: InstagramAccessibilityService? = null
            private set

        fun onScrollMeterResumed() {
            isScrollMeterForeground = true
            instance?.onAppForegrounded()
        }

        fun onScrollMeterPaused() {
            isScrollMeterForeground = false
        }

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
            "sequence", "unfollow", "mute", "block", "restrict", "info", "settings", "not",
            "turn sound on", "turn sound off", "sound on", "sound off", "sound", "music",
            "unmute", "audio muted", "audio unmuted", "message", "see more", "see translation",
            "scrollmeter", "instagram", "open app"
        )

        fun getTodayDateString(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }
    }

    fun isInstagramActive(): Boolean {
        if (isScrollMeterForeground) return false
        val activeRoot = rootInActiveWindow ?: return false
        val pkg = activeRoot.packageName?.toString() ?: ""
        return pkg == INSTAGRAM_PKG
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getInstance(applicationContext)
        notchOverlayManager = NotchOverlayManager(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
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
        instance = null
        onAppForegrounded()
        closeCurrentSessionIfActive()
        notchOverlayManager.destroy()
        ReelTrackerState.setServiceRunning(false)
        ReelTrackerState.updateStatus("Accessibility Service stopped")
    }

    fun onAppForegrounded() {
        liveTickerJob?.cancel()
        liveTickerJob = null
        dwellJob?.cancel()
        dwellJob = null
        commitActiveReelTime()
        closeCurrentSessionIfActive()
        activeCreator = ""
        activeRecordId = 0L
        activeReelStartTime = 0L
        currentSessionId = 0L
        notchOverlayManager.hide()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventPkg = event.packageName?.toString() ?: ""
        val eventType = event.eventType

        // 1. Events from ScrollMeter package (MainActivity or NotchOverlayManager):
        // NEVER treat our own floating notch overlay as foregrounding the ScrollMeter app!
        // MainActivity already manages foreground state via onScrollMeterResumed() / onScrollMeterPaused().
        if (eventPkg == packageName || eventPkg == "com.scrollmeter.app") {
            return
        }

        // 2. If ScrollMeter is currently open and foregrounded: suppress background reel tracking.
        if (isScrollMeterForeground) {
            return
        }

        // 3. If event is from Instagram: process normally.
        if (eventPkg == INSTAGRAM_PKG) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScanTimestamp < SCAN_THROTTLE_MS) return
            lastScanTimestamp = currentTime

            // Safely locate Instagram's root window, ignoring floating overlays or system bars
            val rootNode = (if (rootInActiveWindow?.packageName?.toString() == INSTAGRAM_PKG) rootInActiveWindow else null)
                ?: windows.firstOrNull { it.root?.packageName?.toString() == INSTAGRAM_PKG }?.root
                ?: (if (event.source?.packageName?.toString() == INSTAGRAM_PKG) event.source else null)
                ?: return

            try {
                inspectInstagramTree(rootNode, currentTime)
            } catch (e: Exception) {
                Log.e(TAG, "Error inspecting node tree", e)
            }
            return
        }

        // 4. Event from a third-party / system package.
        // Only reset tracking on a real window-state-change to a genuine non-transient app.
        // Ignore transient system overlays: IME, toasts, volume controls, status bar, and our overlay.
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            val isTransient = eventPkg.contains("inputmethod") ||
                    eventPkg == "com.android.systemui" ||
                    eventPkg == "android" ||
                    eventPkg == packageName ||
                    eventPkg == "com.scrollmeter.app" ||
                    className.contains("Toast", ignoreCase = true) ||
                    className.contains("PopupWindow", ignoreCase = true) ||
                    className.contains("Panel", ignoreCase = true)

            if (!isTransient) {
                // A genuine foreground app replaced Instagram — stop tracking.
                Log.d(TAG, "Real app foregrounded: $eventPkg — resetting tracker")
                onAppForegrounded()
            }
        }
        // All other event types from non-Instagram packages are silently ignored.
    }

    private fun inspectInstagramTree(root: AccessibilityNodeInfo, currentTime: Long) {
        val rootPkg = root.packageName?.toString() ?: ""
        if (rootPkg.isNotEmpty() && rootPkg != INSTAGRAM_PKG) return

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
            val nodePkg = node.packageName?.toString() ?: ""
            if (nodePkg.isNotEmpty() && nodePkg != INSTAGRAM_PKG) return
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

            // 3. Extract Creator & Audio & Caption from content description
            if (desc.isNotEmpty()) {
                if (desc.startsWith("Reel by ", ignoreCase = true)) {
                    isReelsSurface = true
                    val raw = desc.removePrefix("Reel by ").removePrefix("reel by ")
                    val creator = raw.substringBefore("•").substringBefore(".").substringBefore(",").trim()
                    if (isValidUsername(creator)) {
                        detectedCreator = creator
                    }
                    val audioCandidate = when {
                        raw.contains("•") -> raw.substringAfter("•")
                        raw.contains("Audio:", ignoreCase = true) -> raw.substringAfter("Audio:")
                        raw.contains("audio:", ignoreCase = true) -> raw.substringAfter("audio:")
                        else -> ""
                    }.substringBefore(" Double tap").substringBefore(". Double").substringBefore(" double tap").trim()

                    if (audioCandidate.isNotEmpty() && detectedAudio.isEmpty()) {
                        val clean = cleanAudioTitle(audioCandidate, detectedCreator)
                        if (clean.isNotEmpty()) {
                            detectedAudio = clean
                            Log.i(TAG, "Captured AudioTrack from Reel by: '$detectedAudio'")
                        }
                    }
                } else if (desc.startsWith("Profile picture of ", ignoreCase = true) && detectedCreator.isEmpty()) {
                    val candidate = desc.removePrefix("Profile picture of ").removePrefix("profile picture of ")
                        .substringBefore("•").substringBefore(".").trim()
                    if (isValidUsername(candidate)) {
                        detectedCreator = candidate
                    }
                } else if (detectedCaption.isEmpty() && isCaptionForCurrentReel(desc, detectedCreator) && desc != detectedAudio) {
                    detectedCaption = cleanCaptionText(desc, detectedCreator)
                }
            }

            // 4. Extract Creator from explicit author View IDs (fallback only if desc did not have "Reel by")
            if (detectedCreator.isEmpty() && !viewId.contains("comment", ignoreCase = true)) {
                val isExplicitAuthorView = viewId.contains("clips_author", ignoreCase = true) ||
                        viewId.contains("clips_creator", ignoreCase = true) ||
                        viewId.contains("row_feed_photo_profile_name", ignoreCase = true) ||
                        (viewId.contains("profile_name", ignoreCase = true) && !viewId.contains("comment", ignoreCase = true))

                if (isExplicitAuthorView) {
                    val cleanText = text.removePrefix("@").trim()
                    if (isValidUsername(cleanText)) {
                        detectedCreator = cleanText
                    }
                }
            }

            // 5. Extract audio track from this node if not yet detected
            if (detectedAudio.isEmpty()) {
                val candidateAudio = extractAudioTrack(desc, text, viewId, detectedCreator)
                if (candidateAudio.isNotEmpty()) {
                    detectedAudio = candidateAudio
                    Log.i(TAG, "Captured AudioTrack: '$detectedAudio' (from viewId='$viewId')")
                }
            }

            // 6. Extract caption & candidate usernames (only outside sheets/menus)
            if (text.isNotEmpty() && !isModalOrSheetOpen && text != detectedAudio) {
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

                if (!isSystemLabel && !viewId.contains("comment", ignoreCase = true)) {
                    if (viewId.contains("caption", ignoreCase = true) || viewId.contains("description", ignoreCase = true)) {
                        if (isCaptionForCurrentReel(text, detectedCreator)) {
                            detectedCaption = cleanCaptionText(text, detectedCreator)
                        }
                    } else if (text.length > 15 && detectedCaption.isEmpty()) {
                        if (isCaptionForCurrentReel(text, detectedCreator)) {
                            detectedCaption = cleanCaptionText(text, detectedCreator)
                        }
                    } else if (detectedCreator.isEmpty() && candidateTexts.isEmpty()) {
                        // Only treat as candidate author if it starts with '@' or has an author/profile viewId
                        val isAuthorView = viewId.contains("author", ignoreCase = true) ||
                                viewId.contains("creator", ignoreCase = true) ||
                                viewId.contains("user", ignoreCase = true) ||
                                viewId.contains("profile", ignoreCase = true)
                        val hasAtSign = text.startsWith("@")

                        if (isAuthorView || hasAtSign) {
                            val candidate = text.removePrefix("@").trim()
                            if (isValidUsername(candidate)) {
                                candidateTexts.add(candidate)
                            }
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

            var hasNewMetadata = false
            if (detectedAudio.isNotEmpty() && activeAudio.isEmpty()) {
                activeAudio = detectedAudio
                hasNewMetadata = true
            }
            if (detectedCaption.isNotEmpty() && activeCaption.isEmpty()) {
                activeCaption = detectedCaption
                hasNewMetadata = true
            }

            // Enrich caption/audio if they loaded after initial detection
            if (activeRecordId > 0L && hasNewMetadata) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        database.reelDao().updateMetadataIfEmpty(activeRecordId, activeCaption, activeAudio)
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

    private fun cleanAudioTitle(raw: String, creator: String): String {
        var a = raw.trim()
            .removePrefix("Audio:")
            .removePrefix("audio:")
            .removePrefix("Music:")
            .removePrefix("music:")
            .removePrefix("Sound:")
            .removePrefix("sound:")
            .removePrefix("Song:")
            .removePrefix("song:")
            .removePrefix("♪")
            .removePrefix("♫")
            .trim()

        if (creator.isNotEmpty()) {
            if (a.startsWith("$creator · ", ignoreCase = true)) {
                a = a.removePrefix("$creator · ").trim()
            } else if (a.startsWith("$creator • ", ignoreCase = true)) {
                a = a.removePrefix("$creator • ").trim()
            }
        }

        val lower = a.lowercase()
        if (SYSTEM_BLACKLIST.contains(lower) || lower.startsWith("turn sound") ||
            lower.contains("sound on") || lower.contains("sound off") || lower == "audio" || lower == "music"
        ) {
            return ""
        }
        return a.take(80)
    }

    private fun extractAudioTrack(desc: String, text: String, viewId: String, creator: String): String {
        val isMusicViewId = viewId.contains("music", ignoreCase = true) ||
                viewId.contains("audio", ignoreCase = true) ||
                viewId.contains("sound", ignoreCase = true) ||
                viewId.contains("track", ignoreCase = true)

        val candidate = when {
            desc.isNotEmpty() && (isMusicViewId || desc.startsWith("Audio:", ignoreCase = true) ||
                    desc.startsWith("Music:", ignoreCase = true) || desc.startsWith("Sound:", ignoreCase = true) ||
                    desc.contains(" · ") || desc.contains(" • ") || desc.contains("Original audio", ignoreCase = true)) -> desc
            text.isNotEmpty() && (isMusicViewId || text.startsWith("Audio:", ignoreCase = true) ||
                    text.startsWith("Music:", ignoreCase = true) || text.startsWith("Sound:", ignoreCase = true) ||
                    text.contains(" · ") || text.contains(" • ") || text.contains("Original audio", ignoreCase = true)) -> text
            isMusicViewId && text.isNotBlank() -> text
            isMusicViewId && desc.isNotBlank() -> desc
            else -> ""
        }

        if (candidate.isBlank()) return ""
        return cleanAudioTitle(candidate, creator)
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
        if (trimmed.contains("Turn sound", ignoreCase = true)) return false
        if (trimmed.contains("Sound on", ignoreCase = true)) return false
        if (trimmed.contains("Sound off", ignoreCase = true)) return false
        if (trimmed.contains("Consistency Wins", ignoreCase = true) && !creator.equals("zeelabpharmacy", ignoreCase = true)) return false
        if (trimmed.contains("zeelabpharmacy", ignoreCase = true) && !creator.equals("zeelabpharmacy", ignoreCase = true)) return false
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
            
            // Confirm the user stayed on this reel for at least MIN_DWELL_TIME_MS (300ms)
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
                    val finalCaption = activeCaption.ifBlank { caption }
                    val finalAudio = activeAudio.ifBlank { audioName }

                    val record = ReelRecord(
                        sessionId = sessionId,
                        dateString = getTodayDateString(),
                        timestamp = transitionTime,
                        creator = pageName,
                        caption = finalCaption,
                        audioTrack = finalAudio,
                        dwellTimeMs = MIN_DWELL_TIME_MS
                    )

                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            val rowId = database.reelDao().insert(record)
                            activeRecordId = rowId
                            database.reelDao().refreshSessionStats(sessionId, System.currentTimeMillis())

                            // If newer metadata arrived right as row was inserting
                            if (activeCaption.isNotEmpty() || activeAudio.isNotEmpty()) {
                                database.reelDao().updateMetadataIfEmpty(rowId, activeCaption, activeAudio)
                            }

                            // Keep live ticker running smoothly for this session
                            withContext(Dispatchers.Main) {
                                startLiveTicker(sessionId)
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

    private fun startLiveTicker(sessionId: Long) {
        if (liveTickerJob?.isActive == true) {
            // Live ticker is already running continuously across reels in this session
            return
        }
        if (!isInstagramActive()) {
            notchOverlayManager.hide()
            return
        }
        liveTickerJob = serviceScope.launch {
            while (isActive && isInstagramActive()) {
                val now = System.currentTimeMillis()
                val currentRecId = activeRecordId
                val currentStartTime = activeReelStartTime

                if (currentRecId > 0L && currentStartTime > 0L) {
                    val elapsedMs = (now - currentStartTime).coerceIn(MIN_DWELL_TIME_MS, MAX_REEL_WATCH_CAP_MS)
                    withContext(Dispatchers.IO) {
                        try {
                            database.reelDao().updateDwellTime(currentRecId, elapsedMs)
                            database.reelDao().refreshSessionStats(sessionId, now)
                        } catch (_: Exception) {}
                    }
                }

                var sessionDuration = 0L
                var sessionReels = 1

                withContext(Dispatchers.IO) {
                    try {
                        val sess = database.reelDao().getSessionById(sessionId)
                        if (sess != null) {
                            sessionDuration = sess.totalDurationMs
                            sessionReels = sess.totalReels
                        }
                    } catch (_: Exception) {}
                }

                if (!isInstagramActive()) {
                    withContext(Dispatchers.Main) {
                        notchOverlayManager.hide()
                    }
                    break
                }

                withContext(Dispatchers.Main) {
                    notchOverlayManager.showOrUpdate(sessionDuration, sessionReels)
                }

                delay(1000L)
            }

            withContext(Dispatchers.Main) {
                if (!isInstagramActive()) {
                    notchOverlayManager.hide()
                }
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
