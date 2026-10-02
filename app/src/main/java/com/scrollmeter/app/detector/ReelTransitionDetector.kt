package com.scrollmeter.app.detector

import com.scrollmeter.app.context.ReelsContextDetector
import com.scrollmeter.app.context.ReelsContextMode
import com.scrollmeter.app.model.DetectionTelemetry
import com.scrollmeter.app.model.FlowVector
import com.scrollmeter.app.model.FrameData
import com.scrollmeter.app.vision.FrameAnalysisResult
import com.scrollmeter.app.vision.MotionAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-level detection engine coordinating:
 * 1. Dedicated ReelsContextDetector (filters out Instagram Home feed, Explore, Profile)
 * 2. MotionAnalyzer (computer vision difference, optical flow, dHash)
 * 3. ReelDetectionStateMachine (transition verification and counting)
 * 4. Reactive telemetry updates
 */
class ReelTransitionDetector(
    val config: DetectionConfig = DetectionConfig()
) {
    val contextDetector = ReelsContextDetector()
    private val motionAnalyzer = MotionAnalyzer(config)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val recentLogs = ArrayDeque<String>(30)

    private val _telemetry = MutableStateFlow(DetectionTelemetry())
    val telemetry: StateFlow<DetectionTelemetry> = _telemetry.asStateFlow()

    // FPS calculation
    private var frameCount = 0
    private var lastFpsCalculationTime = System.currentTimeMillis()
    private var currentFps = 0f

    val stateMachine = ReelDetectionStateMachine(
        config = config,
        hashAnalyzer = motionAnalyzer.hashAnalyzer,
        onEvent = { event ->
            handleStateEvent(event)
        }
    )

    private fun handleStateEvent(event: ReelDetectionEvent) {
        val timestamp = timeFormat.format(Date())
        val logEntry = when (event) {
            is ReelDetectionEvent.ContextChanged -> {
                "[$timestamp] Context: ${if (event.isReelsActive) "REELS_ACTIVE" else "REELS_NOT_ACTIVE"} (${event.reason})"
            }
            is ReelDetectionEvent.MotionDetected -> {
                "[$timestamp] Motion: vy=${String.format("%.1f", event.vy)}px, consensus=${(event.consensus * 100).toInt()}%"
            }
            is ReelDetectionEvent.CandidateFound -> {
                "[$timestamp] Candidate: dist=${event.dHashDistance}b, sim=${String.format("%.1f", event.similarity)}%"
            }
            is ReelDetectionEvent.CandidateRejected -> {
                "[$timestamp] Rejected: ${event.reason}"
            }
            is ReelDetectionEvent.ReelVerified -> {
                "[$timestamp] VERIFIED REEL #${event.newTotal} (dist=${event.dHashDistance}b, dwell=${event.durationMs}ms)"
            }
        }

        synchronized(recentLogs) {
            if (recentLogs.size >= 25) {
                recentLogs.removeFirst()
            }
            recentLogs.addLast(logEntry)
        }
    }

    /**
     * Entry point for each sampled screen frame from FrameSampler.
     */
    fun onNewFrame(frame: FrameData) {
        frameCount++
        val now = frame.timestampMs
        if (now - lastFpsCalculationTime >= 1000L) {
            currentFps = (frameCount * 1000f) / (now - lastFpsCalculationTime)
            frameCount = 0
            lastFpsCalculationTime = now
        }

        // STEP 1: Dedicated Reels Context Detection
        val contextMode = contextDetector.evaluate(frame)
        val signals = contextDetector.lastSignals

        // STEP 2: Motion Analysis (only run full motion pipeline when inside Reels)
        val analysis = if (contextMode != ReelsContextMode.REELS_NOT_ACTIVE) {
            motionAnalyzer.analyze(frame, stateMachine.referenceStableHash)
        } else {
            // When outside Reels, cheap diff is sufficient; optical flow is bypassed
            FrameAnalysisResult(
                frameDifferenceMad = 0f,
                flow = FlowVector(),
                currentDHash = 0L,
                dHashDistanceVsStable = 0,
                similarityScore = 100f,
                isSignificantChange = false
            )
        }

        // STEP 3: Advance State Machine
        stateMachine.processFrame(analysis, contextMode, now)

        // STEP 4: Publish live telemetry
        val logsSnapshot = synchronized(recentLogs) { recentLogs.toList() }

        _telemetry.value = DetectionTelemetry(
            isCaptureActive = stateMachine.currentState != DetectorState.INACTIVE,
            state = stateMachine.currentState,
            reelsContextMode = contextMode,
            reelsConfidence = signals.confidence,
            contextReason = signals.reason,
            hasActionColumn = signals.hasRightActionColumn,
            hasStoriesHeader = signals.hasHomeFeedStoriesOrHeader,
            sampleRateFps = currentFps,
            frameDifferenceMad = analysis.frameDifferenceMad,
            flow = analysis.flow,
            dHashDistance = analysis.dHashDistanceVsStable,
            similarityScore = analysis.similarityScore,
            candidateStatus = stateMachine.getCandidateStatus(),
            verifiedReelCount = stateMachine.totalReelCount,
            recentLogs = logsSnapshot
        )
    }

    fun startCapture() {
        stateMachine.setActive()
        contextDetector.reset()
        addLog("Screen capture & Reels-context pipeline started.")
    }

    fun stopCapture() {
        stateMachine.setInactive()
        contextDetector.reset()
        motionAnalyzer.reset()
        addLog("Screen capture stopped.")
        _telemetry.value = _telemetry.value.copy(
            isCaptureActive = false,
            state = DetectorState.INACTIVE,
            reelsContextMode = ReelsContextMode.REELS_NOT_ACTIVE,
            sampleRateFps = 0f
        )
    }

    fun resetCount() {
        stateMachine.resetCount()
        contextDetector.reset()
        motionAnalyzer.reset()
        addLog("Reel counter reset to 0.")
        _telemetry.value = _telemetry.value.copy(
            verifiedReelCount = 0,
            candidateStatus = "Reset"
        )
    }

    fun addLog(message: String) {
        val timestamp = timeFormat.format(Date())
        synchronized(recentLogs) {
            if (recentLogs.size >= 25) {
                recentLogs.removeFirst()
            }
            recentLogs.addLast("[$timestamp] $message")
        }
        _telemetry.value = _telemetry.value.copy(
            recentLogs = synchronized(recentLogs) { recentLogs.toList() }
        )
    }
}
