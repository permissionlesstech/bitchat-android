package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.LocationNotesCounter
import com.bitchat.android.nostr.LocationNotesManager
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * Location Notes Sheet - Material Design bottom sheet for location-based notes
 * iOS-compatible implementation with Android Material Design
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationNotesSheet(
    geohash: String,
    locationName: String?,
    nickname: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Managers
    val notesManager = remember { LocationNotesManager.getInstance() }
    val counter = remember { LocationNotesCounter }
    val locationManager = remember { LocationChannelManager.getInstance(context) }
    
    // State
    val notes by notesManager.notes.observeAsState(emptyList())
    val state by notesManager.state.observeAsState(LocationNotesManager.State.IDLE)
    val errorMessage by notesManager.errorMessage.observeAsState()
    val count by counter.count.observeAsState(0)
    val initialLoadComplete by notesManager.initialLoadComplete.observeAsState(false)
    
    // Input field state
    var messageText by remember { mutableStateOf("") }
    val canSend = messageText.isNotBlank()
    
    // Scroll state
    val listState = rememberLazyListState()
    
    // Effect to set geohash when sheet opens
    LaunchedEffect(geohash) {
        notesManager.setGeohash(geohash)
        counter.subscribe(geohash)
    }
    
    // Cleanup when sheet closes
    DisposableEffect(Unit) {
        onDispose {
            // Don't cancel subscriptions here - they'll be managed by the manager lifecycle
        }
    }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 16.dp)
        ) {
            // Header
            LocationNotesHeader(
                geohash = geohash,
                locationName = locationName,
                count = count,
                onClose = onDismiss
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Content area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    state == LocationNotesManager.State.NO_RELAYS -> {
                        EmptyStateMessage(
                            message = "No relays available",
                            icon = "🌐"
                        )
                    }
                    state == LocationNotesManager.State.LOADING && !initialLoadComplete -> {
                        LoadingState()
                    }
                    notes.isEmpty() && initialLoadComplete -> {
                        EmptyStateMessage(
                            message = "No notes yet.\nBe the first to share!",
                            icon = "📝"
                        )
                    }
                    else -> {
                        LocationNotesList(
                            notes = notes,
                            listState = listState,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
            
            // Error message
            errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Input field
            LocationNotesInputField(
                text = messageText,
                onTextChange = { messageText = it },
                onSend = {
                    if (canSend) {
                        notesManager.send(messageText, nickname)
                        messageText = ""
                    }
                },
                enabled = state != LocationNotesManager.State.NO_RELAYS,
                canSend = canSend
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Header with geohash, count, location name, and close button
 */
@Composable
private fun LocationNotesHeader(
    geohash: String,
    locationName: String?,
    count: Int,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = geohash,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "($count)",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            locationName?.let { name ->
                Text(
                    text = name,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * List of location notes
 */
@Composable
private fun LocationNotesList(
    notes: List<LocationNotesManager.Note>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        reverseLayout = false, // Normal order (newest at top since sorted desc)
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(notes, key = { it.id }) { note ->
            LocationNoteItem(note = note)
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * Individual note item
 */
@Composable
private fun LocationNoteItem(note: LocationNotesManager.Note) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = note.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = formatTimestamp(note.createdAt),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(6.dp))
        
        Text(
            text = note.content,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 20.sp
        )
    }
}

/**
 * Input field with send button
 */
@Composable
private fun LocationNotesInputField(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    canSend: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = if (enabled) "Share a note..." else "No relays available",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            enabled = enabled,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            maxLines = 3
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (canSend && enabled) {
                        Color(0xFFFF8C00) // Orange color when enabled
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .clickable(enabled = canSend && enabled) { onSend() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "Send",
                tint = if (canSend && enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Loading state indicator
 */
@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Loading notes...",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Empty state message
 */
@Composable
private fun EmptyStateMessage(message: String, icon: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = icon,
                fontSize = 48.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/**
 * Format timestamp - iOS-compatible formatting
 * Relative for < 7 days, absolute otherwise
 */
private fun formatTimestamp(createdAt: Int): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - createdAt
    
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> {
            val minutes = diff / 60
            "${minutes}m ago"
        }
        diff < 86400 -> {
            val hours = diff / 3600
            "${hours}h ago"
        }
        diff < 604800 -> { // 7 days
            val days = diff / 86400
            "${days}d ago"
        }
        else -> {
            // Absolute date for older than 7 days
            val date = Date(createdAt * 1000L)
            val formatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            formatter.format(date)
        }
    }
}
