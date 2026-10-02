package com.scrollmeter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scrollmeter.app.context.ReelsContextMode
import com.scrollmeter.app.detector.DetectorState
import com.scrollmeter.app.model.DetectionTelemetry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugDashboardScreen(
    telemetry: DetectionTelemetry,
    isServiceRunning: Boolean,
    isForceReelsMode: Boolean,
    onStartCaptureClick: () -> Unit,
    onStopCaptureClick: () -> Unit,
    onResetCountClick: () -> Unit,
    onToggleForceReelsMode: (Boolean) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "ScrollMeter Vision PoC",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Reels-Only Detection Engine (No Accessibility)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                actions = {
                    IconButton(onClick = onResetCountClick) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Counter")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // 1. Master Control Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isServiceRunning)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isServiceRunning) "CAPTURE ACTIVE" else "CAPTURE STOPPED",
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = if (isServiceRunning)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (isServiceRunning) onStopCaptureClick() else onStartCaptureClick()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isServiceRunning)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isServiceRunning) "Stop Screen Analysis" else "Start Screen Capture",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            // 2. Dedicated Reels Context Status Card (Critical requirement)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when (telemetry.reelsContextMode) {
                        ReelsContextMode.REELS_ACTIVE -> Color(0xFF1B5E20)
                        ReelsContextMode.REELS_MODAL_OPEN -> Color(0xFF0D47A1)
                        ReelsContextMode.REELS_NOT_ACTIVE -> Color(0xFF37474F)
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "REELS CONTEXT DETECTOR",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB0BEC5),
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "${(telemetry.reelsConfidence * 100).toInt()}% confidence",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = when (telemetry.reelsContextMode) {
                            ReelsContextMode.REELS_ACTIVE -> "REELS ACTIVE"
                            ReelsContextMode.REELS_MODAL_OPEN -> "REELS (MODAL SHEET OPEN)"
                            ReelsContextMode.REELS_NOT_ACTIVE -> "NOT REELS (HOME FEED / OTHER)"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = telemetry.contextReason,
                        fontSize = 12.sp,
                        color = Color(0xFFECEFF1)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Context Signals Badges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SignalBadge(
                            label = "Action Icons",
                            isActive = telemetry.hasActionColumn,
                            activeText = "Present",
                            inactiveText = "Missing"
                        )
                        SignalBadge(
                            label = "Home Stories",
                            isActive = !telemetry.hasStoriesHeader,
                            activeText = "None",
                            inactiveText = "Detected!"
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Mode Toggle: Auto vs Force
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isForceReelsMode) "Mode: FORCE REELS (TEST)" else "Mode: AUTO-DETECT REELS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isForceReelsMode) Color(0xFFFFD54F) else Color(0xFF81C784)
                        )
                        FilterChip(
                            selected = isForceReelsMode,
                            onClick = { onToggleForceReelsMode(!isForceReelsMode) },
                            label = {
                                Text(
                                    text = if (isForceReelsMode) "Switch to Auto" else "Force Reels Active",
                                    fontSize = 11.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFFD54F),
                                selectedLabelColor = Color.Black
                            )
                        )
                    }
                }
            }

            // 3. Primary Metric: Verified Reel Counter
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "VERIFIED REELS WATCHED",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${telemetry.verifiedReelCount}",
                        fontSize = 58.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Detector State Badge
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = when (telemetry.state) {
                            DetectorState.REELS_NOT_ACTIVE -> Color.Gray
                            DetectorState.REELS_ACTIVE -> Color(0xFF2E7D32)
                            DetectorState.REEL_VISIBLE -> Color(0xFF1B5E20)
                            DetectorState.TRANSITIONING -> Color(0xFFE65100)
                            DetectorState.CANDIDATE_REEL -> Color(0xFF0D47A1)
                            DetectorState.VERIFYING -> Color(0xFF4A148C)
                            DetectorState.VERIFIED_REEL -> Color(0xFF00C853)
                            DetectorState.INACTIVE -> Color.DarkGray
                        },
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = "STATE: ${telemetry.state.name}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = telemetry.candidateStatus,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 4. Live Computer Vision Telemetry
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Live Computer Vision Signals",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem("Sample Rate", String.format("%.1f FPS", telemetry.sampleRateFps))
                        MetricItem("Frame Diff (MAD)", String.format("%.1f%%", telemetry.frameDifferenceMad * 100f))
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem("Flow Direction", telemetry.flow.directionText)
                        MetricItem("Vertical Vy", String.format("%.1f px", telemetry.flow.vy))
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem("Flow Consensus", String.format("%d%%", (telemetry.flow.consensus * 100).toInt()))
                        MetricItem("dHash Distance", "${telemetry.dHashDistance} bits")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem("Similarity vs Last", String.format("%.1f%%", telemetry.similarityScore))
                        MetricItem("Flow Magnitude", String.format("%.1f px", telemetry.flow.magnitude))
                    }
                }
            }

            // 5. Live Decision Terminal Logs
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF121212)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Engine Event Log",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFF81C784)
                        )
                        Text(
                            text = "Latest at bottom",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF000000))
                            .padding(8.dp)
                    ) {
                        if (telemetry.recentLogs.isEmpty()) {
                            Text(
                                text = "Waiting for events...\nStart capture and scroll Reels on Instagram.\nNote: Scrolling on Home feed will NOT trigger counts.",
                                color = Color.DarkGray,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                reverseLayout = true
                            ) {
                                items(telemetry.recentLogs.reversed()) { logLine ->
                                    val logColor = when {
                                        logLine.contains("VERIFIED") -> Color(0xFF4CAF50)
                                        logLine.contains("REELS_ACTIVE") -> Color(0xFF81C784)
                                        logLine.contains("REELS_NOT_ACTIVE") -> Color(0xFFFFB74D)
                                        logLine.contains("Rejected") -> Color(0xFFE57373)
                                        logLine.contains("Candidate") -> Color(0xFF64B5F6)
                                        logLine.contains("Motion") -> Color(0xFFFFD54F)
                                        else -> Color(0xFFE0E0E0)
                                    }
                                    Text(
                                        text = logLine,
                                        color = logColor,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun SignalBadge(
    label: String,
    isActive: Boolean,
    activeText: String,
    inactiveText: String
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isActive) Color(0x334CAF50) else Color(0x33F44336)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$label: ",
                fontSize = 11.sp,
                color = Color.White
            )
            Text(
                text = if (isActive) activeText else inactiveText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isActive) Color(0xFF81C784) else Color(0xFFEF9A9A)
            )
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
