package com.scrollmeter.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.scrollmeter.app.data.AppDatabase
import com.scrollmeter.app.data.DayStat
import com.scrollmeter.app.data.ReelRecord
import com.scrollmeter.app.data.ReelSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.scrollmeter.app.capture.ScreenCaptureManager
import com.scrollmeter.app.capture.ScreenCaptureService
import com.scrollmeter.app.ui.DebugDashboardScreen
import androidx.compose.material.icons.filled.Visibility


// Design System Color Tokens
object ScrollMeterColors {
    val MainBackground = Color(0xFF080B14)
    val SecondaryBackground = Color(0xFF0D1120)
    val CardBackground = Color(0xFF111627)
    val ElevatedCard = Color(0xFF151A2C)
    val Border = Color(0xFF252B42)

    val Purple = Color(0xFF8B3DFF)
    val BrightPurple = Color(0xFFA855F7)
    val PurpleGlow = Color(0xFF7C3AED)
    val PurpleDarkTrack = Color(0xFF1E1736)

    val PrimaryText = Color(0xFFF8FAFC)
    val SecondaryText = Color(0xFFA6AEC4)
    val MutedText = Color(0xFF737C96)

    val ActiveGreen = Color(0xFF19E68C)
    val NegativeRed = Color(0xFFFF5C7A)
}

enum class NavDestination { HOME, HISTORY, INSIGHTS, SAVED, VISION }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tinted Status Bar & Navigation Bar seamlessly matching dark theme
        window.statusBarColor = android.graphics.Color.parseColor("#0B0E14")
        window.navigationBarColor = android.graphics.Color.parseColor("#0B0E14")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false // Crisp white icons on dark tinted bar
            isAppearanceLightNavigationBars = false
        }

        setContent {
            ScrollMeterTheme {
                ScrollMeterAppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        NotchOverlayManager.isScrollMeterForeground = true
    }

    override fun onStop() {
        super.onStop()
        NotchOverlayManager.isScrollMeterForeground = false
    }
}

@Composable
fun ScrollMeterAppRoot() {
    val context = LocalContext.current
    val telemetry by ScreenCaptureManager.detector.telemetry.collectAsState()
    var isForceReelsMode by remember {
        mutableStateOf(ScreenCaptureManager.detector.contextDetector.config.isForceReelsMode)
    }

    val captureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureManager.startService(
                context = context,
                resultCode = result.resultCode,
                data = result.data!!
            )
        } else {
            Toast.makeText(context, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    val startCaptureFlow: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
                Toast.makeText(context, "Please allow 'Display over other apps' for the floating pill", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }
    val database = remember { AppDatabase.getInstance(context) }
    val reelDao = database.reelDao()

    var selectedNav by remember { mutableStateOf(NavDestination.HOME) }
    var selectedSessionId by remember { mutableStateOf<Long?>(null) }

    val todayDate = remember { getFormattedDate(0) }
    val currentMonthPrefix = remember { SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date()) }
    val displayDate = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date()) }
    val displayMonth = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date()) }

    // 100% Real Room Database flows
    val todayCount by reelDao.observeCountForDate(todayDate).collectAsState(initial = 0)
    val todayTimeMs by reelDao.observeTotalTimeForDate(todayDate).collectAsState(initial = 0L)
    val todayAvgMs = remember(todayCount, todayTimeMs) {
        if (todayCount > 0) todayTimeMs.toDouble() / todayCount else 0.0
    }

    val monthCount by reelDao.observeCountForMonth(currentMonthPrefix).collectAsState(initial = 0)
    val monthTimeMs by reelDao.observeTotalTimeForMonth(currentMonthPrefix).collectAsState(initial = 0L)
    val dailyStats by reelDao.observeDailyStats().collectAsState(initial = emptyList())
    val allSessions by reelDao.observeAllSessions().collectAsState(initial = emptyList())

    val isServiceActive = telemetry.isCaptureActive || ScreenCaptureService.isRunning
    var isNotchEnabled by remember {
        mutableStateOf(NotchOverlayManager.isOverlayEnabled(context))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        NotchOverlayManager.isScrollMeterForeground = true
        isNotchEnabled = NotchOverlayManager.isOverlayEnabled(context)
    }

    // Handle back button when viewing a session detail
    BackHandler(enabled = selectedSessionId != null) {
        selectedSessionId = null
    }

    val coroutineScope = rememberCoroutineScope()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = ScrollMeterColors.CardBackground,
            titleContentColor = ScrollMeterColors.PrimaryText,
            textContentColor = ScrollMeterColors.SecondaryText,
            title = {
                Text(
                    text = "Clear All Data?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "This will permanently remove all tracked reels, viewing sessions, and reset your time stats to zero. This action cannot be undone.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            reelDao.clearAllData()
                            ReelTrackerState.resetCount()
                        }
                        showClearDialog = false
                    }
                ) {
                    Text(
                        text = "Clear All",
                        color = ScrollMeterColors.NegativeRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(text = "Cancel", color = ScrollMeterColors.SecondaryText)
                }
            }
        )
    }

    Scaffold(
        containerColor = ScrollMeterColors.MainBackground,
        bottomBar = {
            if (selectedSessionId == null) {
                ScrollMeterBottomNavigation(
                    selected = selectedNav,
                    onSelect = { selectedNav = it }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                selectedSessionId != null -> {
                    val sId = selectedSessionId!!
                    val sessionReels by reelDao.observeReelsForSession(sId).collectAsState(initial = emptyList())
                    val sessionItem = allSessions.find { it.id == sId }

                    SessionDetailScreen(
                        session = sessionItem,
                        reels = sessionReels,
                        onBack = { selectedSessionId = null }
                    )
                }

                selectedNav == NavDestination.HOME -> {
                    HomeScreen(
                        todayCount = todayCount,
                        todayTimeMs = todayTimeMs,
                        todayAvgMs = todayAvgMs,
                        monthCount = monthCount,
                        monthTimeMs = monthTimeMs,
                        dailyStats = dailyStats,
                        todayDate = todayDate,
                        displayDate = displayDate,
                        displayMonth = displayMonth,
                        isServiceActive = isServiceActive,
                        isNotchEnabled = isNotchEnabled,
                        onToggleNotch = { enabled ->
                            NotchOverlayManager.setOverlayEnabled(context, enabled)
                            isNotchEnabled = enabled
                            if (enabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                        },
                        onServiceCardClick = {
                            if (isServiceActive) {
                                ScreenCaptureManager.stopService(context)
                            } else {
                                startCaptureFlow()
                            }
                        },
                        onViewAllClick = { selectedNav = NavDestination.HISTORY },
                        onClearClick = { showClearDialog = true }
                    )
                }

                selectedNav == NavDestination.HISTORY -> {
                    SessionsHistoryScreen(
                        sessions = allSessions,
                        todayDate = todayDate,
                        onSessionClick = { session -> selectedSessionId = session.id },
                        onClearClick = { showClearDialog = true }
                    )
                }

                selectedNav == NavDestination.VISION -> {
                    DebugDashboardScreen(
                        telemetry = telemetry,
                        isServiceRunning = telemetry.isCaptureActive,
                        isForceReelsMode = isForceReelsMode,
                        onStartCaptureClick = startCaptureFlow,
                        onStopCaptureClick = {
                            ScreenCaptureManager.stopService(context)
                        },
                        onResetCountClick = {
                            ScreenCaptureManager.resetCounter()
                        },
                        onToggleForceReelsMode = { enabled ->
                            isForceReelsMode = enabled
                            ScreenCaptureManager.detector.contextDetector.config.isForceReelsMode = enabled
                        }
                    )
                }

                else -> {
                    // Insights / Saved placeholder
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "${selectedNav.name} feature coming soon",
                            color = ScrollMeterColors.SecondaryText,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 1: HOME DASHBOARD
// ==========================================
@Composable
fun HomeScreen(
    todayCount: Int,
    todayTimeMs: Long,
    todayAvgMs: Double,
    monthCount: Int,
    monthTimeMs: Long,
    dailyStats: List<DayStat>,
    todayDate: String,
    displayDate: String,
    displayMonth: String,
    isServiceActive: Boolean,
    isNotchEnabled: Boolean,
    onToggleNotch: (Boolean) -> Unit,
    onServiceCardClick: () -> Unit,
    onViewAllClick: () -> Unit,
    onClearClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        // Top Bar
        item {
            DashboardHeader(
                onSettingsClick = onServiceCardClick,
                onClearClick = onClearClick
            )
        }

        // 1. Service Status Card
        item {
            ServiceStatusCard(
                isActive = isServiceActive,
                onClick = onServiceCardClick
            )
        }

        // 1.5 Floating Notch Pill Toggle Card
        item {
            NotchOverlayToggleCard(
                isEnabled = isNotchEnabled,
                onToggle = onToggleNotch
            )
        }

        // 2. Today Card (Real Data)
        item {
            TodayHeroCard(
                todayCount = todayCount,
                todayTimeMs = todayTimeMs,
                todayAvgMs = todayAvgMs,
                displayDate = displayDate
            )
        }

        // 3. This Month Card (Real Data)
        item {
            ThisMonthCardClean(
                monthCount = monthCount,
                monthTimeMs = monthTimeMs,
                displayMonth = displayMonth
            )
        }

        // 4. Last 7 Days Card (Real Data)
        item {
            Last7DaysLargeCard(
                dailyStats = dailyStats,
                todayDate = todayDate,
                todayCount = todayCount,
                onViewAllClick = onViewAllClick
            )
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ==========================================
// SCREEN 2: SESSIONS HISTORY (BY DATE & SESSIONS)
// ==========================================
@Composable
fun SessionsHistoryScreen(
    sessions: List<ReelSession>,
    todayDate: String,
    onSessionClick: (ReelSession) -> Unit,
    onClearClick: () -> Unit
) {
    val yesterdayDate = remember { getFormattedDate(-1) }
    val groupedSessions = remember(sessions) {
        sessions.groupBy { it.dateString }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        // Top Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = ScrollMeterColors.PrimaryText, fontWeight = FontWeight.Bold, fontSize = 28.sp)) {
                                append("Scroll")
                            }
                            withStyle(SpanStyle(color = ScrollMeterColors.BrightPurple, fontWeight = FontWeight.Bold, fontSize = 28.sp)) {
                                append("Meter")
                            }
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Sessions History",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScrollMeterColors.PrimaryText
                    )
                }

                if (sessions.isNotEmpty()) {
                    IconButton(
                        onClick = onClearClick,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear History",
                            tint = ScrollMeterColors.NegativeRed,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        if (sessions.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ScrollMeterColors.CardBackground,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.Border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                ) {
                    Box(modifier = Modifier.padding(28.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No recorded sessions yet.\nWatch Reels on Instagram to record your first session!",
                            color = ScrollMeterColors.SecondaryText,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            groupedSessions.forEach { (dateKey, sessionList) ->
                // Date Section Header
                item {
                    val dateHeaderLabel = when (dateKey) {
                        todayDate -> "Today (${formatToDisplayDate(dateKey)})"
                        yesterdayDate -> "Yesterday (${formatToDisplayDate(dateKey)})"
                        else -> formatToDisplayDate(dateKey)
                    }
                    Text(
                        text = dateHeaderLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScrollMeterColors.BrightPurple,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                }

                // List of sessions for this date
                items(sessionList) { session ->
                    SessionCard(
                        session = session,
                        onClick = { onSessionClick(session) }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
fun SessionCard(
    session: ReelSession,
    onClick: () -> Unit
) {
    val timeRangeStr = remember(session.startTime, session.endTime) {
        val startFmt = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(session.startTime))
        val endFmt = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(session.endTime))
        if (session.endTime > session.startTime + 60000) "$startFmt - $endFmt" else startFmt
    }

    val durationText = remember(session.totalDurationMs) {
        val mins = (session.totalDurationMs / 60000).toInt()
        val secs = ((session.totalDurationMs % 60000) / 1000).toInt()
        when {
            mins > 0 -> "${mins} min"
            secs > 0 -> "${secs} sec"
            else -> "< 1 min"
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = ScrollMeterColors.CardBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ScrollMeterColors.PurpleDarkTrack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = ScrollMeterColors.BrightPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = timeRangeStr,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ScrollMeterColors.PrimaryText
                    )
                    Text(
                        text = "Session duration: $durationText",
                        fontSize = 12.sp,
                        color = ScrollMeterColors.SecondaryText
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${session.totalReels} reels",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScrollMeterColors.BrightPurple
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ScrollMeterColors.MutedText,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ==========================================
// SCREEN 3: SESSION DETAIL (PAGE, CAPTION, SONG)
// ==========================================
@Composable
fun SessionDetailScreen(
    session: ReelSession?,
    reels: List<ReelRecord>,
    onBack: () -> Unit
) {
    val timeRangeStr = remember(session) {
        if (session != null) {
            val startFmt = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(session.startTime))
            val endFmt = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(session.endTime))
            "$startFmt - $endFmt"
        } else "Session Details"
    }

    val totalDurationFormatted = remember(session) {
        val totalMs = session?.totalDurationMs ?: 0L
        val mins = totalMs / 60000
        val secs = (totalMs % 60000) / 1000
        if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        // Top Navigation Header
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(ScrollMeterColors.CardBackground)
                        .border(1.dp, ScrollMeterColors.Border, CircleShape)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = ScrollMeterColors.PrimaryText,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column {
                    Text(
                        text = "Session Details",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScrollMeterColors.PrimaryText
                    )
                    Text(
                        text = timeRangeStr,
                        fontSize = 12.sp,
                        color = ScrollMeterColors.MutedText
                    )
                }
            }
        }

        // Summary Card: Total Reels & Session Duration
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = ScrollMeterColors.CardBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Reels Watched",
                            fontSize = 12.sp,
                            color = ScrollMeterColors.MutedText
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${session?.totalReels ?: reels.size}",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = ScrollMeterColors.PrimaryText
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(40.dp)
                            .background(ScrollMeterColors.Border)
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Watch Duration",
                            fontSize = 12.sp,
                            color = ScrollMeterColors.MutedText
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = totalDurationFormatted,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = ScrollMeterColors.BrightPurple
                        )
                    }
                }
            }
        }

        // Header: Watched Reels in this session
        item {
            Text(
                text = "Reels in this Session (${reels.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ScrollMeterColors.PrimaryText,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // List of all Reels watched in this 10/15 min session
        items(reels) { reel ->
            ReelDetailCard(reel = reel)
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun ReelDetailCard(reel: ReelRecord) {
    val timeFormatted = remember(reel.timestamp) {
        SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(reel.timestamp))
    }
    val secondsWatched = (reel.dwellTimeMs / 1000).coerceAtLeast(1)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = ScrollMeterColors.CardBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Creator Page Name & Dwell Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(ScrollMeterColors.PurpleDarkTrack),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = ScrollMeterColors.BrightPurple,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Text(
                            text = reel.creator.ifEmpty { "Instagram Creator" },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = ScrollMeterColors.PrimaryText
                        )
                        Text(
                            text = timeFormatted,
                            fontSize = 11.sp,
                            color = ScrollMeterColors.MutedText
                        )
                    }
                }

                // Dwell time pill badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ScrollMeterColors.PurpleDarkTrack,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.BrightPurple.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "${secondsWatched}s watched",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ScrollMeterColors.BrightPurple,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Song / Audio Name (if detected)
            if (reel.audioTrack.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = ScrollMeterColors.BrightPurple,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = reel.audioTrack,
                        fontSize = 12.sp,
                        color = ScrollMeterColors.SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Caption Text (if detected)
            if (reel.caption.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ScrollMeterColors.ElevatedCard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            tint = ScrollMeterColors.MutedText,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = reel.caption,
                            fontSize = 12.sp,
                            color = ScrollMeterColors.SecondaryText,
                            lineHeight = 16.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// TOP HEADER
// ==========================================
@Composable
fun DashboardHeader(
    onSettingsClick: () -> Unit,
    onClearClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = ScrollMeterColors.PrimaryText, fontWeight = FontWeight.Bold, fontSize = 28.sp)) {
                        append("Scroll")
                    }
                    withStyle(SpanStyle(color = ScrollMeterColors.BrightPurple, fontWeight = FontWeight.Bold, fontSize = 28.sp)) {
                        append("Meter")
                    }
                }
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Track your Instagram Reels",
                fontSize = 13.sp,
                color = ScrollMeterColors.MutedText
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onClearClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Clear All Data",
                    tint = ScrollMeterColors.SecondaryText,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = ScrollMeterColors.PrimaryText,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

// ==========================================
// 1. SERVICE STATUS CARD
// ==========================================
@Composable
fun ServiceStatusCard(
    isActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = ScrollMeterColors.CardBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) ScrollMeterColors.ActiveGreen.copy(alpha = 0.18f)
                            else ScrollMeterColors.NegativeRed.copy(alpha = 0.18f)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive) ScrollMeterColors.ActiveGreen
                                else ScrollMeterColors.NegativeRed
                            )
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (isActive) "Computer Vision Active" else "Computer Vision Inactive",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ScrollMeterColors.PrimaryText
                    )
                    Text(
                        text = if (isActive) "Monitoring Instagram Reels in real-time" else "Tap to start Screen Capture & CV tracking",
                        fontSize = 12.sp,
                        color = ScrollMeterColors.SecondaryText
                    )
                }
            }

            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = ScrollMeterColors.MutedText,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ==========================================
// 1.5 NOTCH OVERLAY TOGGLE CARD
// ==========================================
@Composable
fun NotchOverlayToggleCard(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = ScrollMeterColors.CardBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ScrollMeterColors.PurpleDarkTrack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AvTimer,
                        contentDescription = null,
                        tint = ScrollMeterColors.BrightPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Floating Notch Pill",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ScrollMeterColors.PrimaryText
                    )
                    Text(
                        text = "Live overlay below camera notch while scrolling",
                        fontSize = 12.sp,
                        color = ScrollMeterColors.SecondaryText
                    )
                }
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ScrollMeterColors.PrimaryText,
                    checkedTrackColor = ScrollMeterColors.Purple,
                    uncheckedThumbColor = ScrollMeterColors.MutedText,
                    uncheckedTrackColor = ScrollMeterColors.ElevatedCard
                )
            )
        }
    }
}

// ==========================================
// 2. TODAY CARD (CLEAN HERO CARD - 100% REAL)
// ==========================================
@Composable
fun TodayHeroCard(
    todayCount: Int,
    todayTimeMs: Long,
    todayAvgMs: Double,
    displayDate: String
) {
    val totalSeconds = todayTimeMs / 1000
    val minutes = (totalSeconds / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()

    val watchTimeText = when {
        minutes > 0 -> "${minutes}m ${seconds}s"
        seconds > 0 -> "${seconds}s"
        else -> "0m"
    }

    val avgText = if (todayAvgMs > 0) {
        String.format(Locale.getDefault(), "%.1fs", todayAvgMs / 1000.0)
    } else "0s"

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = ScrollMeterColors.CardBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ScrollMeterColors.PurpleDarkTrack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = ScrollMeterColors.BrightPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = "Today",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScrollMeterColors.PrimaryText
                    )
                    Text(
                        text = displayDate,
                        fontSize = 12.sp,
                        color = ScrollMeterColors.MutedText
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    AnimatedContent(
                        targetState = todayCount,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "heroCountAnim"
                    ) { count ->
                        Text(
                            text = "$count",
                            fontSize = 62.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-2).sp,
                            color = ScrollMeterColors.PrimaryText
                        )
                    }
                    Text(
                        text = "Reels watched",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ScrollMeterColors.SecondaryText
                    )
                }

                CircularWatchTimeWidget(
                    displayTime = watchTimeText,
                    progress = if (totalSeconds > 0) (totalSeconds.toFloat() / 3600f).coerceIn(0.08f, 1f) else 0.05f
                )
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ScrollMeterColors.ElevatedCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.Border.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.AvTimer,
                            contentDescription = null,
                            tint = ScrollMeterColors.BrightPurple,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Average watch time per Reel",
                            fontSize = 12.sp,
                            color = ScrollMeterColors.SecondaryText
                        )
                    }
                    Text(
                        text = "$avgText / Reel",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScrollMeterColors.PrimaryText
                    )
                }
            }
        }
    }
}

@Composable
fun CircularWatchTimeWidget(
    displayTime: String,
    progress: Float
) {
    Box(
        modifier = Modifier.size(105.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 9.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = Color(0xFF1B2036),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            drawArc(
                brush = Brush.sweepGradient(
                    listOf(ScrollMeterColors.PurpleGlow, ScrollMeterColors.BrightPurple)
                ),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = displayTime,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ScrollMeterColors.PrimaryText
            )
            Text(
                text = "Watch time",
                fontSize = 10.sp,
                color = ScrollMeterColors.MutedText
            )
        }
    }
}

// ==========================================
// 3. THIS MONTH CARD (CLEAN DUAL STATS)
// ==========================================
@Composable
fun ThisMonthCardClean(
    monthCount: Int,
    monthTimeMs: Long,
    displayMonth: String
) {
    val totalSeconds = monthTimeMs / 1000
    val totalMins = totalSeconds / 60
    val hours = totalMins / 60
    val remainingMins = totalMins % 60

    val durationText = when {
        hours > 0 -> "${hours}h ${remainingMins}m"
        totalMins > 0 -> "${totalMins}m"
        totalSeconds > 0 -> "${totalSeconds}s"
        else -> "0m"
    }

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = ScrollMeterColors.CardBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ScrollMeterColors.PurpleDarkTrack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        tint = ScrollMeterColors.BrightPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = "This Month",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScrollMeterColors.PrimaryText
                    )
                    Text(
                        text = displayMonth,
                        fontSize = 12.sp,
                        color = ScrollMeterColors.MutedText
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = ScrollMeterColors.ElevatedCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.Border.copy(alpha = 0.6f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Reels Watched",
                            fontSize = 12.sp,
                            color = ScrollMeterColors.SecondaryText
                        )
                        Text(
                            text = "$monthCount",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = ScrollMeterColors.PrimaryText
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = ScrollMeterColors.ElevatedCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.Border.copy(alpha = 0.6f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = ScrollMeterColors.BrightPurple,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Total Time",
                                fontSize = 12.sp,
                                color = ScrollMeterColors.SecondaryText
                            )
                        }
                        Text(
                            text = durationText,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = ScrollMeterColors.PrimaryText
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. LAST 7 DAYS CARD (LARGE BAR CHART)
// ==========================================
@Composable
fun Last7DaysLargeCard(
    dailyStats: List<DayStat>,
    todayDate: String,
    todayCount: Int,
    onViewAllClick: () -> Unit
) {
    val past7Days = remember(dailyStats, todayDate, todayCount) {
        generateRealPast7Days(dailyStats, todayDate, todayCount)
    }

    val maxCount = remember(past7Days) {
        val max = past7Days.maxOfOrNull { it.count } ?: 0
        if (max < 10) 10 else max
    }

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = ScrollMeterColors.CardBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Poll,
                        contentDescription = null,
                        tint = ScrollMeterColors.BrightPurple,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Last 7 Days",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScrollMeterColors.PrimaryText
                    )
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1B2034),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ScrollMeterColors.Border),
                    modifier = Modifier.clickable { onViewAllClick() }
                ) {
                    Text(
                        text = "View all →",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ScrollMeterColors.BrightPurple,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf("$maxCount", "${maxCount * 2 / 3}", "${maxCount / 3}", "0").forEach { v ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = v,
                                fontSize = 10.sp,
                                color = ScrollMeterColors.MutedText.copy(alpha = 0.8f),
                                modifier = Modifier.width(24.dp)
                            )
                            HorizontalDivider(
                                color = ScrollMeterColors.Border.copy(alpha = 0.35f),
                                thickness = 0.8.dp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 28.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    past7Days.forEach { item ->
                        val isToday = item.isToday
                        val barHeightFactor = if (maxCount > 0) {
                            (item.count.toFloat() / maxCount.toFloat()).coerceIn(if (item.count > 0) 0.12f else 0.04f, 1f)
                        } else 0.04f

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier.width(36.dp)
                        ) {
                            Text(
                                text = if (item.count > 0) "${item.count}" else "0",
                                fontSize = 12.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                                color = if (isToday) ScrollMeterColors.PrimaryText else ScrollMeterColors.SecondaryText
                            )

                            Spacer(Modifier.height(5.dp))

                            Box(
                                modifier = Modifier
                                    .width(26.dp)
                                    .height(115.dp * barHeightFactor)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isToday) {
                                            Brush.verticalGradient(
                                                listOf(
                                                    ScrollMeterColors.BrightPurple,
                                                    ScrollMeterColors.PurpleGlow
                                                )
                                            )
                                        } else {
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color(0xFF38235E),
                                                    Color(0xFF22163A)
                                                )
                                            )
                                        }
                                    )
                                    .then(
                                        if (isToday) {
                                            Modifier.border(
                                                1.dp,
                                                ScrollMeterColors.BrightPurple.copy(alpha = 0.6f),
                                                RoundedCornerShape(10.dp)
                                            )
                                        } else Modifier
                                    )
                            )

                            Spacer(Modifier.height(8.dp))

                            Text(
                                text = item.label,
                                fontSize = 10.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = if (isToday) ScrollMeterColors.BrightPurple else ScrollMeterColors.MutedText,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// BOTTOM NAVIGATION
// ==========================================
@Composable
fun ScrollMeterBottomNavigation(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit
) {
    Surface(
        color = ScrollMeterColors.MainBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF181D2E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScrollMeterNavItem(
                icon = Icons.Default.Home,
                label = "Home",
                isSelected = selected == NavDestination.HOME,
                onClick = { onSelect(NavDestination.HOME) }
            )

            ScrollMeterNavItem(
                icon = Icons.Default.CalendarMonth,
                label = "History",
                isSelected = selected == NavDestination.HISTORY,
                onClick = { onSelect(NavDestination.HISTORY) }
            )

            ScrollMeterNavItem(
                icon = Icons.Default.Insights,
                label = "Insights",
                isSelected = selected == NavDestination.INSIGHTS,
                onClick = { onSelect(NavDestination.INSIGHTS) }
            )

            ScrollMeterNavItem(
                icon = Icons.Default.BookmarkBorder,
                label = "Saved",
                isSelected = selected == NavDestination.SAVED,
                onClick = { onSelect(NavDestination.SAVED) }
            )

            ScrollMeterNavItem(
                icon = Icons.Default.Visibility,
                label = "Vision",
                isSelected = selected == NavDestination.VISION,
                onClick = { onSelect(NavDestination.VISION) }
            )
        }
    }
}

@Composable
fun ScrollMeterNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) ScrollMeterColors.BrightPurple else ScrollMeterColors.MutedText,
            modifier = Modifier.size(23.dp)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) ScrollMeterColors.BrightPurple else ScrollMeterColors.MutedText
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(ScrollMeterColors.BrightPurple)
            )
        } else {
            Spacer(Modifier.height(2.5.dp))
        }
    }
}

// ==========================================
// HELPERS
// ==========================================
data class DayChartItem(val label: String, val count: Int, val isToday: Boolean)

fun generateRealPast7Days(dailyStats: List<DayStat>, todayDate: String, todayCount: Int): List<DayChartItem> {
    val statsMap = dailyStats.associate { it.date to it.count }.toMutableMap()
    statsMap[todayDate] = todayCount

    val result = mutableListOf<DayChartItem>()
    for (i in 6 downTo 0) {
        val loopCal = Calendar.getInstance()
        loopCal.add(Calendar.DAY_OF_YEAR, -i)
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(loopCal.time)
        val dayLabel = if (i == 0) "Today" else SimpleDateFormat("MMM d", Locale.getDefault()).format(loopCal.time)
        val count = statsMap[dateKey] ?: 0
        result.add(DayChartItem(label = dayLabel, count = count, isToday = (i == 0)))
    }
    return result
}

fun formatToDisplayDate(dateKey: String): String {
    return try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateKey)
        if (parsed != null) {
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(parsed)
        } else dateKey
    } catch (_: Exception) {
        dateKey
    }
}

fun getFormattedDate(daysOffset: Int): String {
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, daysOffset)
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
}

@Composable
fun ScrollMeterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ScrollMeterColors.BrightPurple,
            background = ScrollMeterColors.MainBackground,
            surface = ScrollMeterColors.CardBackground,
            onSurface = ScrollMeterColors.PrimaryText,
            outline = ScrollMeterColors.Border
        ),
        content = content
    )
}
