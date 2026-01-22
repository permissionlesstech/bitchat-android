package com.bitchat.android.features.festival.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitchat.android.features.festival.FestivalTab
import com.bitchat.android.features.festival.FestivalScheduleManager

/**
 * Main festival screen with configurable bottom tab navigation
 * Tabs are defined in FestivalSchedule.json
 */
@Composable
fun FestivalScreen(
    scheduleManager: FestivalScheduleManager = viewModel(),
    onExitFestivalMode: () -> Unit = {},
    chatContent: @Composable () -> Unit = {}
) {
    val festivalData by scheduleManager.festivalData.collectAsState()
    val tabs = festivalData?.configuredTabs ?: FestivalTab.defaultTabs
    var selectedTabId by remember { mutableStateOf(tabs.firstOrNull()?.id ?: "schedule") }
    
    // Ensure selected tab is valid
    LaunchedEffect(tabs) {
        if (tabs.none { it.id == selectedTabId }) {
            selectedTabId = tabs.firstOrNull()?.id ?: "schedule"
        }
    }
    
    val selectedTab = tabs.find { it.id == selectedTabId }
    
    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.primary
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Festival banner
        FestivalBanner(
            festivalName = festivalData?.festival?.name ?: "Festival Mode",
            textColor = textColor,
            onExit = onExitFestivalMode
        )
        
        // Content based on selected tab
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            selectedTab?.let { tab ->
                FestivalTabContent(
                    tab = tab,
                    scheduleManager = scheduleManager,
                    chatContent = chatContent
                )
            }
        }
        
        HorizontalDivider()
        
        // Dynamic tab bar
        FestivalTabBar(
            tabs = tabs,
            selectedTabId = selectedTabId,
            textColor = textColor,
            onTabSelected = { selectedTabId = it }
        )
    }
}

@Composable
private fun FestivalBanner(
    festivalName: String,
    textColor: Color,
    onExit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(textColor.copy(alpha = 0.1f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Festival,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(20.dp)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = festivalName,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = textColor
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        TextButton(onClick = onExit) {
            Text(
                text = "Exit",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun FestivalTabContent(
    tab: FestivalTab,
    scheduleManager: FestivalScheduleManager,
    chatContent: @Composable () -> Unit
) {
    when (tab.type) {
        FestivalTab.TabType.SCHEDULE -> {
            FestivalScheduleTab(scheduleManager = scheduleManager)
        }
        FestivalTab.TabType.CHANNELS -> {
            FestivalChannelsTab(scheduleManager = scheduleManager)
        }
        FestivalTab.TabType.CHAT -> {
            chatContent()
        }
        FestivalTab.TabType.MAP -> {
            FestivalMapTab(scheduleManager = scheduleManager)
        }
        FestivalTab.TabType.INFO -> {
            FestivalInfoTab(scheduleManager = scheduleManager)
        }
        FestivalTab.TabType.FRIENDS -> {
            FriendMapTab()
        }
        FestivalTab.TabType.CUSTOM -> {
            CustomTab(tabName = tab.name)
        }
    }
}

@Composable
private fun FestivalTabBar(
    tabs: List<FestivalTab>,
    selectedTabId: String,
    textColor: Color,
    onTabSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        tabs.forEach { tab ->
            val isSelected = tab.id == selectedTabId
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(tab.id) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = mapIconName(tab.icon),
                    contentDescription = tab.name,
                    tint = if (isSelected) textColor else Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = tab.name,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = if (isSelected) textColor else Color.Gray
                )
            }
        }
    }
}

/** Map iOS SF Symbol names to Material icons */
private fun mapIconName(sfSymbol: String): ImageVector {
    return when (sfSymbol) {
        "calendar" -> Icons.Default.CalendarMonth
        "antenna.radiowaves.left.and.right" -> Icons.Default.CellTower
        "bubble.left.and.bubble.right" -> Icons.Default.Forum
        "map" -> Icons.Default.Map
        "info.circle" -> Icons.Default.Info
        "person.2" -> Icons.Default.People
        "sparkles" -> Icons.Default.AutoAwesome
        else -> Icons.Default.Circle
    }
}

// MARK: - Tab Content Views

@Composable
fun FestivalScheduleTab(scheduleManager: FestivalScheduleManager) {
    val festivalData by scheduleManager.festivalData.collectAsState()
    val selectedDay by scheduleManager.selectedDay.collectAsState()
    val nowPlaying by scheduleManager.nowPlaying.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Day selector
        val days = scheduleManager.days
        if (days.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                days.forEach { day ->
                    FilterChip(
                        selected = day == selectedDay,
                        onClick = { scheduleManager.selectDay(day) },
                        label = { Text(scheduleManager.formatDayForDisplay(day)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Now playing section
        if (nowPlaying.isNotEmpty()) {
            Text(
                text = "Now Playing",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            nowPlaying.forEach { set ->
                val stage = scheduleManager.stage(set.stage)
                SetCard(set = set, stageColor = stage?.composeColor ?: Color.Gray)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Full schedule
        Text(
            text = "Full Schedule",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        selectedDay?.let { day ->
            scheduleManager.sets(day).forEach { set ->
                val stage = scheduleManager.stage(set.stage)
                SetCard(set = set, stageColor = stage?.composeColor ?: Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun SetCard(
    set: com.bitchat.android.features.festival.ScheduledSet,
    stageColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = stageColor.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = set.artist,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = "${set.timeRangeString} • ${set.stage}",
                fontSize = 12.sp,
                color = Color.Gray
            )
            set.genre?.let {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = stageColor
                )
            }
        }
    }
}

@Composable
fun FestivalChannelsTab(scheduleManager: FestivalScheduleManager) {
    val festivalData by scheduleManager.festivalData.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Stage Channels",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        festivalData?.stages?.forEach { stage ->
            ChannelRow(
                name = stage.channelName,
                description = stage.description,
                color = stage.composeColor
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Custom Channels",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        festivalData?.customChannels?.forEach { channel ->
            ChannelRow(
                name = channel.name,
                description = channel.description,
                color = channel.composeColor
            )
        }
    }
}

@Composable
fun ChannelRow(name: String, description: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, shape = androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = name, fontWeight = FontWeight.Medium)
            Text(text = description, fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
fun FestivalMapTab(scheduleManager: FestivalScheduleManager) {
    // Placeholder - integrate Google Maps or MapBox here
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Map,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Map Coming Soon",
                fontFamily = FontFamily.Monospace,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun FestivalInfoTab(scheduleManager: FestivalScheduleManager) {
    val festivalData by scheduleManager.festivalData.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        festivalData?.festival?.let { festival ->
            Text(
                text = festival.name,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = festival.location,
                fontSize = 14.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Gates: ${festival.gatesOpen} • Music: ${festival.musicStart} - ${festival.musicEnd}",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Festival Tips",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        TipRow(icon = Icons.Default.WifiOff, text = "Mesh chat works without cell service")
        TipRow(icon = Icons.Default.People, text = "Add friends as favorites to find them later")
        TipRow(icon = Icons.Default.BatteryFull, text = "BLE mesh is battery efficient")
    }
}

@Composable
fun TipRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, fontSize = 14.sp)
    }
}

@Composable
fun FriendMapTab() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Friend Locations",
                fontFamily = FontFamily.Monospace,
                color = Color.Gray
            )
            Text(
                text = "Coming Soon",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun CustomTab(tabName: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = tabName,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
