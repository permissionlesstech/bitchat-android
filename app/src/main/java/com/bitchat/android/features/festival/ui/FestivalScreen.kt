package com.bitchat.android.features.festival

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * FestivalScreen - Main festival mode screen with bottom navigation
 * 
 * This is the main entry point for festival mode UI.
 * It contains four tabs: Schedule, Chat, Map, and Info.
 * 
 * Android equivalent of iOS FestivalContentView.swift
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FestivalScreen(
    festivalViewModel: FestivalScheduleManager = viewModel(),
    // Pass your existing ChatViewModel here for the chat tab
    // chatViewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Schedule", "Chat", "Map", "Info")
    
    val festivalData by festivalViewModel.festivalData.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = festivalData?.festival?.name ?: "Festival",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = when (index) {
                                    0 -> Icons.Default.Schedule
                                    1 -> Icons.Default.Chat
                                    2 -> Icons.Default.Map
                                    else -> Icons.Default.Info
                                },
                                contentDescription = title
                            )
                        },
                        label = { Text(title) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> FestivalScheduleTab(festivalViewModel)
                1 -> FestivalChatTab() // Integrate existing ChatScreen here
                2 -> FestivalMapTab(festivalViewModel)
                3 -> FestivalInfoTab(festivalViewModel)
            }
        }
    }
}

// ============================================================================
// MARK: - Schedule Tab
// ============================================================================

/**
 * FestivalScheduleTab - Shows the festival schedule by day
 * 
 * Android equivalent of iOS FestivalScheduleView.swift
 */
@Composable
fun FestivalScheduleTab(
    viewModel: FestivalScheduleManager,
    modifier: Modifier = Modifier
) {
    val selectedDay by viewModel.selectedDay.collectAsState()
    val selectedStage by viewModel.selectedStage.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val upcomingSoon by viewModel.upcomingSoon.collectAsState()
    
    Column(modifier = modifier.fillMaxSize()) {
        // Day selector
        DaySelector(
            days = viewModel.days,
            selectedDay = selectedDay,
            onDaySelected = { viewModel.selectDay(it) },
            formatDay = { viewModel.shortDayName(it) }
        )
        
        // Stage filter chips
        StageFilterChips(
            stages = viewModel.stages,
            selectedStage = selectedStage,
            onStageSelected = { viewModel.selectStage(it) }
        )
        
        // Schedule list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Now Playing section
            if (nowPlaying.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Now Playing",
                        icon = Icons.Default.PlayCircle
                    )
                }
                items(nowPlaying) { set ->
                    ScheduleSetCard(
                        set = set,
                        stage = viewModel.stageById(set.stage),
                        isNowPlaying = true
                    )
                }
            }
            
            // Up Next section
            if (upcomingSoon.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Up Next (30 min)",
                        icon = Icons.Default.Schedule
                    )
                }
                items(upcomingSoon) { set ->
                    ScheduleSetCard(
                        set = set,
                        stage = viewModel.stageById(set.stage),
                        isUpcoming = true
                    )
                }
            }
            
            // Full schedule for selected day
            item {
                SectionHeader(
                    title = selectedDay?.let { viewModel.formatDayForDisplay(it) } ?: "Schedule",
                    icon = Icons.Default.CalendarToday
                )
            }
            
            items(viewModel.filteredSets()) { set ->
                ScheduleSetCard(
                    set = set,
                    stage = viewModel.stageById(set.stage)
                )
            }
        }
    }
}

// ============================================================================
// MARK: - UI Components
// ============================================================================

@Composable
fun DaySelector(
    days: List<String>,
    selectedDay: String?,
    onDaySelected: (String) -> Unit,
    formatDay: (String) -> String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        days.forEach { day ->
            FilterChip(
                selected = day == selectedDay,
                onClick = { onDaySelected(day) },
                label = { Text(formatDay(day)) }
            )
        }
    }
}

@Composable
fun StageFilterChips(
    stages: List<Stage>,
    selectedStage: String?,
    onStageSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // "All" chip
        FilterChip(
            selected = selectedStage == null,
            onClick = { onStageSelected(null) },
            label = { Text("All") }
        )
        
        // Stage chips
        stages.forEach { stage ->
            FilterChip(
                selected = stage.id == selectedStage,
                onClick = { onStageSelected(stage.id) },
                label = { Text(stage.name) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = stage.composeColor.copy(alpha = 0.3f)
                )
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ScheduleSetCard(
    set: ScheduledSet,
    stage: Stage?,
    isNowPlaying: Boolean = false,
    isUpcoming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when {
        isNowPlaying -> MaterialTheme.colorScheme.primaryContainer
        isUpcoming -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = set.artist,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = set.timeRangeString,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                set.genre?.let { genre ->
                    Text(
                        text = genre,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Stage indicator
            stage?.let { s ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(s.composeColor)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = s.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

// ============================================================================
// MARK: - Placeholder Tabs (to be expanded)
// ============================================================================

@Composable
fun FestivalChatTab(modifier: Modifier = Modifier) {
    // TODO: Integrate existing ChatScreen here
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Chat tab - integrate existing ChatScreen")
    }
}

@Composable
fun FestivalMapTab(
    viewModel: FestivalScheduleManager,
    modifier: Modifier = Modifier
) {
    // TODO: Implement Google Maps with stage markers and POIs
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Map,
                contentDescription = null,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("Map tab - implement with Google Maps Compose")
            Text(
                text = "Stages: ${viewModel.stages.size}, POIs: ${viewModel.pointsOfInterest.size}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun FestivalInfoTab(
    viewModel: FestivalScheduleManager,
    modifier: Modifier = Modifier
) {
    val festivalData by viewModel.festivalData.collectAsState()
    
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Festival info card
            festivalData?.festival?.let { info ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = info.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "📍 ${info.location}")
                        Text(text = "📅 ${info.dates.start} to ${info.dates.end}")
                        Text(text = "🚪 Gates open: ${info.gatesOpen}")
                        Text(text = "🎵 Music: ${info.musicStart} - ${info.musicEnd}")
                    }
                }
            }
        }
        
        // Custom channels section
        item {
            SectionHeader(
                title = "Festival Channels",
                icon = Icons.Default.Tag
            )
        }
        
        items(viewModel.customChannels) { channel ->
            ChannelCard(channel = channel)
        }
    }
}

@Composable
fun ChannelCard(
    channel: CustomChannel,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { /* TODO: Join channel */ }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(channel.composeColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Tag,
                    contentDescription = null,
                    tint = Color.White
                )
            }
            Column {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = channel.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
