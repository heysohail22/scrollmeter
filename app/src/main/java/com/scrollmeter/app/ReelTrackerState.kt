package com.scrollmeter.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal in-memory state manager for Milestone 1.
 * Keeps track of live reel count, current reel fingerprint, and detection diagnostics.
 */
object ReelTrackerState {

    private val _reelCount = MutableStateFlow(0)
    val reelCount: StateFlow<Int> = _reelCount.asStateFlow()

    private val _currentFingerprint = MutableStateFlow("None (Start scrolling Reels)")
    val currentFingerprint: StateFlow<String> = _currentFingerprint.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _lastStatusMessage = MutableStateFlow("Waiting for Instagram activity...")
    val lastStatusMessage: StateFlow<String> = _lastStatusMessage.asStateFlow()

    fun incrementCount(fingerprint: String) {
        _reelCount.value += 1
        _currentFingerprint.value = fingerprint
        _lastStatusMessage.value = "New Reel counted (#${_reelCount.value}): $fingerprint"
    }

    fun updateCurrentCandidate(fingerprint: String) {
        _currentFingerprint.value = fingerprint
    }

    fun updateStatus(message: String) {
        _lastStatusMessage.value = message
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun resetCount() {
        _reelCount.value = 0
        _currentFingerprint.value = "Reset"
        _lastStatusMessage.value = "Counter reset to 0."
    }
}
