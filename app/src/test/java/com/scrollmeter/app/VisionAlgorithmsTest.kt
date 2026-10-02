package com.scrollmeter.app

import com.scrollmeter.app.context.ReelsContextDetector
import com.scrollmeter.app.context.ReelsContextMode
import com.scrollmeter.app.detector.DetectionConfig
import com.scrollmeter.app.detector.DetectorState
import com.scrollmeter.app.detector.ReelDetectionEvent
import com.scrollmeter.app.detector.ReelDetectionStateMachine
import com.scrollmeter.app.model.FlowVector
import com.scrollmeter.app.model.FrameData
import com.scrollmeter.app.vision.FrameAnalysisResult
import com.scrollmeter.app.vision.ImageDifferenceAnalyzer
import com.scrollmeter.app.vision.OpticalFlowAnalyzer
import com.scrollmeter.app.vision.PerceptualHashAnalyzer
import org.junit.Assert.*
import org.junit.Test

class VisionAlgorithmsTest {

    @Test
    fun testImageDifferenceAnalyzer() {
        val analyzer = ImageDifferenceAnalyzer()
        val size = 180 * 320

        val arr1 = ByteArray(size) { 100.toByte() }
        val arr2 = ByteArray(size) { 100.toByte() }
        val diffIdentical = analyzer.computeDifference(arr1, arr2)
        assertEquals(0f, diffIdentical, 0.001f)

        val arr3 = ByteArray(size) { 200.toByte() }
        val diffDistinct = analyzer.computeDifference(arr1, arr3)
        assertTrue("Difference should be around ~39%", diffDistinct > 0.35f)
    }

    @Test
    fun testPerceptualHashAnalyzer() {
        val analyzer = PerceptualHashAnalyzer()
        val width = 180
        val height = 320

        // Test identical frames produce distance 0
        val frameA = ByteArray(width * height) { (it % 256).toByte() }
        val hashA = analyzer.computeDHash(frameA, width, height)
        val distIdentical = analyzer.hammingDistance(hashA, hashA)
        assertEquals(0, distIdentical)

        // Test inverse pattern produces high Hamming distance
        val frameB = ByteArray(width * height) { (255 - (it % 256)).toByte() }
        val hashB = analyzer.computeDHash(frameB, width, height)
        val distDistinct = analyzer.hammingDistance(hashA, hashB)
        assertTrue("Inverted pattern should have high distance", distDistinct >= 10)
    }

    @Test
    fun testOpticalFlowVerticalShift() {
        val analyzer = OpticalFlowAnalyzer()
        val width = 180
        val height = 320

        // Synthetic horizontal bands that shift vertically by 6 pixels
        val prev = ByteArray(width * height) { idx ->
            val y = idx / width
            if ((y / 16) % 2 == 0) 200.toByte() else 40.toByte()
        }

        // Shift down by 6 pixels
        val shift = 6
        val curr = ByteArray(width * height) { idx ->
            val y = idx / width
            val shiftedY = y - shift
            if (shiftedY in 0 until height) {
                if ((shiftedY / 16) % 2 == 0) 200.toByte() else 40.toByte()
            } else {
                0.toByte()
            }
        }

        val flow = analyzer.computeFlow(prev, curr, width, height)
        assertTrue("Should detect vertical movement magnitude", flow.magnitude > 0f)
        assertTrue("Vertical flow should be non-zero", flow.vy != 0f)
    }

    @Test
    fun testReelsContextDetectorFiltersHomeFeed() {
        val detector = ReelsContextDetector()
        val width = 180
        val height = 320

        // Synthetic Home feed: contains high-contrast transitions in the stories row (Y: 40..75)
        val homeFeedFrame = ByteArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            if (y in 25..64) {
                // Circular avatar alternating pattern
                if ((x / 8) % 2 == 0) 240.toByte() else 20.toByte()
            } else {
                120.toByte()
            }
        }

        val frame = FrameData(homeFeedFrame, width, height, 1000L)
        // Evaluate multiple frames to pass hysteresis
        detector.evaluate(frame)
        val mode = detector.evaluate(frame)

        assertEquals(ReelsContextMode.REELS_NOT_ACTIVE, mode)
        assertFalse(detector.lastSignals.hasRightActionColumn)
    }

    @Test
    fun testHomeFeedScrollIgnoredByStateMachine() {
        val config = DetectionConfig(
            swipeFlowThreshold = 4.0f,
            motionConsensusThreshold = 0.60f,
            settlingFlowThreshold = 1.0f,
            settlingDwellMs = 100L,
            minDHashHammingDistance = 10
        )

        var verifiedCount = 0
        val hashAnalyzer = PerceptualHashAnalyzer()
        val stateMachine = ReelDetectionStateMachine(
            config = config,
            hashAnalyzer = hashAnalyzer,
            onEvent = { event ->
                if (event is ReelDetectionEvent.ReelVerified) {
                    verifiedCount++
                }
            }
        )

        val hashPostA = 0x1111111111111111_u.toLong()
        val hashPostB = 0x9999999999999999_u.toLong()

        // User is scrolling Instagram Home Feed (contextMode = REELS_NOT_ACTIVE)
        stateMachine.processFrame(
            FrameAnalysisResult(0.20f, FlowVector(0f, 8.0f, 8.0f, 0.9f, 10f), hashPostA, 0, 100f, true),
            ReelsContextMode.REELS_NOT_ACTIVE,
            1000L
        )
        assertEquals(DetectorState.REELS_NOT_ACTIVE, stateMachine.currentState)

        // Motion settles on next post
        stateMachine.processFrame(
            FrameAnalysisResult(0.01f, FlowVector(0f, 0f, 0f, 0f, 0f), hashPostB, 64, 0f, false),
            ReelsContextMode.REELS_NOT_ACTIVE,
            1300L
        )

        // Counter MUST remain 0!
        assertEquals(0, verifiedCount)
        assertEquals(0, stateMachine.totalReelCount)
        assertEquals(DetectorState.REELS_NOT_ACTIVE, stateMachine.currentState)
    }

    @Test
    fun testReelTransitionCountedInsideReelsActive() {
        val config = DetectionConfig(
            swipeFlowThreshold = 4.0f,
            motionConsensusThreshold = 0.60f,
            settlingFlowThreshold = 1.0f,
            settlingDwellMs = 100L,
            minDHashHammingDistance = 10,
            sameReelBounceDistanceMax = 4
        )

        var verifiedCount = 0
        val hashAnalyzer = PerceptualHashAnalyzer()
        val stateMachine = ReelDetectionStateMachine(
            config = config,
            hashAnalyzer = hashAnalyzer,
            onEvent = { event ->
                if (event is ReelDetectionEvent.ReelVerified) {
                    verifiedCount++
                }
            }
        )

        val hashReelA = 0xAAAAAAAAAAAAAAAA_u.toLong()
        val hashReelB = 0x5555555555555555_u.toLong()

        // 1. User enters Reels (REELS_ACTIVE) -> establishes Reel A
        stateMachine.processFrame(
            FrameAnalysisResult(0f, FlowVector(), hashReelA, 0, 100f, false),
            ReelsContextMode.REELS_ACTIVE,
            1000L
        )
        assertEquals(DetectorState.REEL_VISIBLE, stateMachine.currentState)

        // 2. Swipe initiated between Reel A and Reel B
        stateMachine.processFrame(
            FrameAnalysisResult(0.15f, FlowVector(0f, 6.0f, 6.0f, 0.8f, 10f), hashReelA, 0, 100f, true),
            ReelsContextMode.REELS_ACTIVE,
            1100L
        )
        assertEquals(DetectorState.TRANSITIONING, stateMachine.currentState)

        // 3. Motion settles on Reel B
        stateMachine.processFrame(
            FrameAnalysisResult(0.01f, FlowVector(0f, 0.2f, 0.2f, 0.1f, 0f), hashReelB, 64, 0f, false),
            ReelsContextMode.REELS_ACTIVE,
            1300L
        )
        assertTrue(
            stateMachine.currentState == DetectorState.CANDIDATE_REEL ||
            stateMachine.currentState == DetectorState.VERIFYING
        )

        if (stateMachine.currentState == DetectorState.CANDIDATE_REEL) {
            stateMachine.processFrame(
                FrameAnalysisResult(0.01f, FlowVector(0f, 0.1f, 0.1f, 0f, 0f), hashReelB, 64, 0f, false),
                ReelsContextMode.REELS_ACTIVE,
                1350L
            )
        }
        assertEquals(DetectorState.VERIFYING, stateMachine.currentState)

        // 4. Dwell time satisfied (>100ms) -> VERIFIED_REEL
        stateMachine.processFrame(
            FrameAnalysisResult(0.01f, FlowVector(0f, 0.1f, 0.1f, 0f, 0f), hashReelB, 64, 0f, false),
            ReelsContextMode.REELS_ACTIVE,
            1500L
        )

        assertEquals(1, verifiedCount)
        assertEquals(1, stateMachine.totalReelCount)
        assertEquals(DetectorState.REEL_VISIBLE, stateMachine.currentState)
    }
}
