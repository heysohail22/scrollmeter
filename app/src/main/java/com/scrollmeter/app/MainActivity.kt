package com.scrollmeter.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.scrollmeter.app.data.AppDatabase
import com.scrollmeter.app.data.DayStat
import com.scrollmeter.app.data.ReelRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class AppTab { HOME, HISTORY }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ScrollMeterTheme {
                ScrollMeterApp()
            }
        }
    }
}

@Composable
fun ScrollMeterApp() {
    val context = LocalContext.current
    val database = remember { AppDatabase.getInstance(context) }
    val reelDao = database.reelDao()

    var currentTab by remember { mutableStateOf(AppTab.HOME) }
    var selectedDateForDetail by remember { mutableStateOf<String?>(null) }

    val todayDate = remember { getFormattedDate(0) }
    val yesterdayDate = remember { getFormattedDate(-1) }

    val todayCount by reelDao.observeCountForDate(todayDate).collectAsState(initial = 0)
    val yesterdayCount by reelDao.observeCountForDate(yesterdayDate).collectAsState(initial = 0)
    val totalCount by reelDao.observeTotalCount().collectAsState(initial = 0)
    val dailyStats by reelDao.observeDailyStats().collectAsState(initial = emptyList())

    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityEnabled(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isAccessibilityEnabled = checkAccessibilityEnabled(context)
    }

    // Handle back button when inside Day Detail view
    BackHandler(enabled = selectedDateForDetail != null) {
        selectedDateForDetail = null
    }

    Scaffold(
        containerColor = Color(0xFF0F1016),
        bottomBar = {
            if (selectedDateForDetail == null) {
                BottomNavBar(
                    selectedTab = currentTab,
                    onTabSelected = { currentTab = it }
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
                selectedDateForDetail != null -> {
                    val dateKey = selectedDateForDetail!!
                    val reelsForDay by reelDao.observeReelsForDate(dateKey).collectAsState(initial = emptyList())
                    val totalDayTimeMs by reelDao.observeTotalTimeForDate(dateKey).collectAsState(initial = 0L)

                    DayDetailScreen(
                        dateString = dateKey,
                        todayDate = todayDate,
                        yesterdayDate = yesterdayDate,
                        reels = reelsForDay,
                        totalTimeMs = totalDayTimeMs,
                        onBack = { selectedDateForDetail = null }
                    )
                }

                currentTab == AppTab.HOME -> {
                    HomeScreen(
                        todayCount = todayCount,
                        yesterdayCount = yesterdayCount,
                        totalCount = totalCount,
                        dailyStats = dailyStats,
                        todayDate = todayDate,
                        isServiceActive = isAccessibilityEnabled,
                        onServiceCardClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    )
                }

                currentTab == AppTab.HISTORY -> {
                    DailyHistoryScreen(
                        dailyStats = dailyStats,
                        todayDate = todayDate,
                        yesterdayDate = yesterdayDate,
                        onDaySelected = { date -> selectedDateForDetail = date }
                    )
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
    yesterdayCount: Int,
    totalCount: Int,
    dailyStats: List<DayStat>,
    todayDate: String,
    isServiceActive: Boolean,
    onServiceCardClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(Modifier.height(12.dp)) }

        // Top App Header
        item {
            Column {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp)) {
                            append("Scroll")
                        }
                        withStyle(SpanStyle(color = Color(0xFFA855F7), fontWeight = FontWeight.Bold, fontSize = 28.sp)) {
                            append("Meter")
                        }
                    }
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Track your Instagram Reels",
                    fontSize = 13.sp,
                    color = Color(0xFF8E92A4)
                )
            }
        }

        // Service Active Card
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF161824),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232638)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onServiceCardClick() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isServiceActive) Color(0x2210B981) else Color(0x22EF4444)
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isServiceActive) Color(0xFF10B981) else Color(0xFFEF4444)
                                    )
                            )
                        }

                        Column {
                            Text(
                                text = if (isServiceActive) "Service Active" else "Service Disabled",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = if (isServiceActive) "Monitoring Instagram Reels" else "Tap to enable accessibility permission",
                                fontSize = 12.sp,
                                color = Color(0xFF8E92A4)
                            )
                        }
                    }

                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Color(0xFF6B7280),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Hero Today's Reels Card
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF161824),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232638)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF6D28D9), Color(0xFF8B5CF6))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Today's Reels",
                            fontSize = 14.sp,
                            color = Color(0xFF8E92A4),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        AnimatedContent(
                            targetState = todayCount,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "heroCountAnim"
                        ) { count ->
                            Text(
                                text = "$count",
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-1).sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Split Stats (Yesterday & All Time)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Yesterday card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF161824),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232638)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.DateRange,
                                contentDescription = null,
                                tint = Color(0xFFA855F7),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Yesterday",
                                fontSize = 12.sp,
                                color = Color(0xFF8E92A4)
                            )
                        }
                        Text(
                            text = "$yesterdayCount",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                // All Time card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF161824),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232638)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.BarChart,
                                contentDescription = null,
                                tint = Color(0xFFA855F7),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "All Time",
                                fontSize = 12.sp,
                                color = Color(0xFF8E92A4)
                            )
                        }
                        Text(
                            text = "$totalCount",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Last 7 Days Bar Chart Card
        item {
            Last7DaysChartCard(dailyStats = dailyStats, todayDate = todayDate, todayCount = todayCount)
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun Last7DaysChartCard(
    dailyStats: List<DayStat>,
    todayDate: String,
    todayCount: Int
) {
    val past7Days = remember(dailyStats, todayDate, todayCount) {
        generatePast7Days(dailyStats, todayDate, todayCount)
    }
    val maxCount = remember(past7Days) {
        val max = past7Days.maxOfOrNull { it.count } ?: 1
        if (max < 10) 10 else max
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF161824),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232638)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.BarChart,
                    contentDescription = null,
                    tint = Color(0xFFA855F7),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Last 7 Days",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Chart Bars Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                past7Days.forEach { day ->
                    val isToday = day.isToday
                    val fraction = (day.count.toFloat() / maxCount.toFloat()).coerceIn(0.12f, 1f)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(36.dp)
                    ) {
                        // Count above bar
                        Text(
                            text = if (day.count > 0) "${day.count}" else "",
                            fontSize = 11.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) Color.White else Color(0xFF8E92A4),
                            modifier = Modifier.height(16.dp)
                        )

                        Spacer(Modifier.height(4.dp))

                        // Rounded Bar
                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .height(85.dp * fraction)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isToday) {
                                        Brush.verticalGradient(
                                            listOf(Color(0xFFA855F7), Color(0xFF7C3AED))
                                        )
                                    } else {
                                        Brush.verticalGradient(
                                            listOf(Color(0xFF3B2F63), Color(0xFF241D3B))
                                        )
                                    }
                                )
                        )

                        Spacer(Modifier.height(8.dp))

                        // Label below bar
                        Text(
                            text = day.label,
                            fontSize = 10.sp,
                            color = if (isToday) Color(0xFFA855F7) else Color(0xFF8E92A4),
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 2: DAILY HISTORY
// ==========================================
@Composable
fun DailyHistoryScreen(
    dailyStats: List<DayStat>,
    todayDate: String,
    yesterdayDate: String,
    onDaySelected: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(Modifier.height(12.dp)) }

        item {
            Column {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp)) {
                            append("Scroll")
                        }
                        withStyle(SpanStyle(color = Color(0xFFA855F7), fontWeight = FontWeight.Bold, fontSize = 28.sp)) {
                            append("Meter")
                        }
                    }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Daily History",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        if (dailyStats.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF161824),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp)
                ) {
                    Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No history recorded yet.\nWatch Reels to start your daily log!",
                            color = Color(0xFF8E92A4),
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(dailyStats) { stat ->
                HistoryDayCard(
                    stat = stat,
                    todayDate = todayDate,
                    yesterdayDate = yesterdayDate,
                    onClick = { onDaySelected(stat.date) }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun HistoryDayCard(
    stat: DayStat,
    todayDate: String,
    yesterdayDate: String,
    onClick: () -> Unit
) {
    val title = when (stat.date) {
        todayDate -> "Today"
        yesterdayDate -> "Yesterday"
        else -> formatToDisplayDate(stat.date)
    }
    val subtitle = formatToDisplayDate(stat.date)
    val minutesSpent = (stat.totalDurationMs / 60000).coerceAtLeast(1)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF161824),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232638)),
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
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF8E92A4)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "${stat.count} reels",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (stat.totalDurationMs > 0) "$minutesSpent min" else "< 1 min",
                        fontSize = 12.sp,
                        color = Color(0xFF8E92A4)
                    )
                }

                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color(0xFF6B7280),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ==========================================
// SCREEN 3: DAY DETAIL & TIMELINE VIEW
// ==========================================
@Composable
fun DayDetailScreen(
    dateString: String,
    todayDate: String,
    yesterdayDate: String,
    reels: List<ReelRecord>,
    totalTimeMs: Long,
    onBack: () -> Unit
) {
    val displayTitle = when (dateString) {
        todayDate -> "Today (${formatToDisplayDate(dateString)})"
        yesterdayDate -> "Yesterday (${formatToDisplayDate(dateString)})"
        else -> formatToDisplayDate(dateString)
    }

    val totalMinutes = (totalTimeMs / 60000).coerceAtLeast(if (reels.isNotEmpty()) 1 else 0)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(Modifier.height(10.dp)) }

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
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF161824))
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Text(
                    text = displayTitle,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // Summary Split Card (Total Reels | Total Time)
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF161824),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232638)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Total Reels
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.CalendarMonth,
                                contentDescription = null,
                                tint = Color(0xFFA855F7),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Total Reels",
                                fontSize = 12.sp,
                                color = Color(0xFF8E92A4)
                            )
                        }
                        Text(
                            text = "${reels.size}",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // Vertical Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(48.dp)
                            .background(Color(0xFF2A2D3E))
                    )

                    // Right: Total Time
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = Color(0xFFA855F7),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Total Time",
                                fontSize = 12.sp,
                                color = Color(0xFF8E92A4)
                            )
                        }
                        Text(
                            text = if (totalMinutes > 0) "$totalMinutes min" else "${totalTimeMs / 1000} sec",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Section Title: Reel Events
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Reel Events",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "${reels.size} reels",
                    fontSize = 13.sp,
                    color = Color(0xFF8E92A4)
                )
            }
        }

        // Vertical Timeline List
        if (reels.isEmpty()) {
            item {
                Text(
                    text = "No individual reel events recorded.",
                    fontSize = 13.sp,
                    color = Color(0xFF8E92A4),
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        } else {
            items(reels) { reel ->
                TimelineReelRow(reel = reel)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun TimelineReelRow(reel: ReelRecord) {
    val timeFormatted = remember(reel.timestamp) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(reel.timestamp))
    }
    val secondsWatched = (reel.dwellTimeMs / 1000).coerceAtLeast(1)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Timeline Dot & Connector
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFA855F7))
            )
        }

        Spacer(Modifier.width(8.dp))

        // Event Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF161824),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232638)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = timeFormatted,
                    fontSize = 13.sp,
                    color = Color(0xFF8E92A4),
                    fontWeight = FontWeight.Medium
                )

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (reel.creator.isNotEmpty()) reel.creator.take(24) else "Reel watched",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = "$secondsWatched sec",
                        fontSize = 11.sp,
                        color = Color(0xFF8E92A4)
                    )
                }
            }
        }
    }
}

// ==========================================
// BOTTOM NAVIGATION BAR
// ==========================================
@Composable
fun BottomNavBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit
) {
    Surface(
        color = Color(0xFF11121A),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1D2030)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Home Tab
            BottomNavItem(
                icon = Icons.Default.Home,
                label = "Home",
                isSelected = selectedTab == AppTab.HOME,
                onClick = { onTabSelected(AppTab.HOME) }
            )

            // History Tab
            BottomNavItem(
                icon = Icons.Default.DateRange,
                label = "History",
                isSelected = selectedTab == AppTab.HISTORY,
                onClick = { onTabSelected(AppTab.HISTORY) }
            )
        }
    }
}

@Composable
fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) Color(0xFFA855F7) else Color(0xFF6B7280),
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) Color(0xFFA855F7) else Color(0xFF6B7280)
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color(0xFFA855F7))
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }
    }
}

// ==========================================
// HELPERS & THEME
// ==========================================
data class DayChartItem(val label: String, val count: Int, val isToday: Boolean)

fun generatePast7Days(dailyStats: List<DayStat>, todayDate: String, todayCount: Int): List<DayChartItem> {
    val statsMap = dailyStats.associate { it.date to it.count }.toMutableMap()
    statsMap[todayDate] = todayCount

    val result = mutableListOf<DayChartItem>()
    val cal = Calendar.getInstance()

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

private fun checkAccessibilityEnabled(context: Context): Boolean {
    if (ReelTrackerState.isServiceRunning.value) return true

    try {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        if (enabledServices.contains("com.scrollmeter.app", ignoreCase = true)) {
            return true
        }
    } catch (_: Exception) {}

    return try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledList = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        enabledList.any { it.id.contains("com.scrollmeter.app", ignoreCase = true) }
    } catch (_: Exception) {
        false
    }
}

@Composable
fun ScrollMeterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFA855F7),
            primaryContainer = Color(0xFF2E1065),
            background = Color(0xFF0F1016),
            surface = Color(0xFF161824),
            surfaceVariant = Color(0xFF1C1E2E),
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFF8E92A4),
            outline = Color(0xFF232638)
        ),
        content = content
    )
}
