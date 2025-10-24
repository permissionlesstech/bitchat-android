package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.bitchat.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.LocationNotesManager
import java.text.SimpleDateFormat
import java.util.*
import java.util.Calendar

/**
 * Location Notes Sheet - EXACT iOS UI match for bitchat
 * Matches iOS LocationNotesView.swift exactly in style, colors, fonts, and text
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
    val isDark = isSystemInDarkTheme()
    
    // iOS color scheme
    val backgroundColor = if (isDark) Color.Black else Color.White
    val accentGreen = if (isDark) Color.Green else Color(0xFF008000) // dark: green, light: dark green (0, 0.5, 0)
    
    // Managers
    val notesManager = remember { LocationNotesManager.getInstance() }
    val locationManager = remember { LocationChannelManager.getInstance(context) }
    
    // State
    val notes by notesManager.notes.observeAsState(emptyList())
    val state by notesManager.state.observeAsState(LocationNotesManager.State.IDLE)
    val errorMessage by notesManager.errorMessage.observeAsState()
    val initialLoadComplete by notesManager.initialLoadComplete.observeAsState(false)
    
    // SIMPLIFIED: Get count directly from notes list (no separate counter needed)
    val count = notes.size
    
    // Get location name (building or block) - matches iOS locationNames lookup
    val locationNames by locationManager.locationNames.observeAsState(emptyMap())
    val displayLocationName = locationNames[GeohashChannelLevel.BUILDING]?.takeIf { it.isNotEmpty() }
        ?: locationNames[GeohashChannelLevel.BLOCK]?.takeIf { it.isNotEmpty() }
    
    // Input field state
    var draft by remember { mutableStateOf("") }
    val sendButtonEnabled = draft.trim().isNotEmpty() && state != LocationNotesManager.State.NO_RELAYS
    
    // Scroll state
    val listState = rememberLazyListState()
    
    // Effect to set geohash when sheet opens
    LaunchedEffect(geohash) {
        notesManager.setGeohash(geohash)
    }
    
    // Cleanup when sheet closes
    DisposableEffect(Unit) {
        onDispose {
            notesManager.cancel()
        }
    }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = backgroundColor,
        contentColor = if (isDark) Color.White else Color.Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            // Header section (matches iOS headerSection)
            LocationNotesHeader(
                geohash = geohash,
                count = count,
                locationName = displayLocationName,
                state = state,
                accentGreen = accentGreen,
                backgroundColor = backgroundColor,
                onClose = onDismiss
            )
            
            // ScrollView with notes content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(backgroundColor)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Notes content (matches iOS notesContent)
                    when {
                        state == LocationNotesManager.State.NO_RELAYS -> {
                            item {
                                NoRelaysRow(
                                    onRetry = { notesManager.refresh() }
                                )
                            }
                        }
                        state == LocationNotesManager.State.LOADING && !initialLoadComplete -> {
                            item {
                                LoadingRow()
                            }
                        }
                        notes.isEmpty() -> {
                            item {
                                EmptyRow()
                            }
                        }
                        else -> {
                            items(notes, key = { it.id }) { note ->
                                NoteRow(note = note)
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }
                    
                    // Error row (matches iOS errorRow)
                    errorMessage?.let { error ->
                        if (state != LocationNotesManager.State.NO_RELAYS) {
                            item {
                                ErrorRow(
                                    message = error,
                                    onDismiss = { notesManager.clearError() }
                                )
                            }
                        }
                    }
                }
            }
            
            // Divider before input (matches iOS overlay)
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                thickness = 1.dp
            )
            
            // Input section (matches iOS inputSection)
            LocationNotesInputSection(
                draft = draft,
                onDraftChange = { draft = it },
                sendButtonEnabled = sendButtonEnabled,
                accentGreen = accentGreen,
                backgroundColor = backgroundColor,
                onSend = {
                    val content = draft.trim()
                    if (content.isNotEmpty()) {
                        notesManager.send(content, nickname)
                        draft = ""
                    }
                }
            )
        }
    }
}

/**
 * Header section - matches iOS headerSection exactly
 * Shows: "#geohash • X notes", location name, description, and close button
 */
@Composable
private fun LocationNotesHeader(
    geohash: String,
    count: Int,
    locationName: String?,
    state: LocationNotesManager.State,
    accentGreen: Color,
    backgroundColor: Color,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        // Title row with close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Localized title with ±1 and note count
            Text(
                text = pluralStringResource(
                    id = R.plurals.location_notes_title,
                    count = count,
                    geohash,
                    count
                ),
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // Close button - iOS style with xmark icon
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✕",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Location name in green (building or block)
        locationName?.let { name ->
            if (name.isNotEmpty()) {
                Text(
                    text = name,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = accentGreen
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        
        // Description
        Text(
            text = stringResource(R.string.location_notes_description),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        
        // Relays paused message if no relays
        if (state == LocationNotesManager.State.NO_RELAYS) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.location_notes_relays_unavailable),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Note row - matches iOS noteRow exactly
 * Shows @basename then timestamp, then content below
 */
@Composable
private fun NoteRow(note: LocationNotesManager.Note) {
    // Extract baseName (before #suffix like iOS)
    val baseName = note.displayName.split("#", limit = 2).firstOrNull() ?: note.displayName
    val ts = timestampText(note.createdAt)
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // First row: @nickname and timestamp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "@$baseName",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (ts.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = ts,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(2.dp))
        
        // Second row: content
        Text(
            text = note.content,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * No relays row - matches iOS noRelaysRow
 */
@Composable
private fun NoRelaysRow(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = stringResource(R.string.location_notes_no_relays_title),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.location_notes_no_relays_desc),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.retry),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onRetry)
        )
    }
}

/**
 * Loading row - matches iOS loadingRow
 */
@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.loading_location_notes),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/**
 * Empty row - matches iOS emptyRow
 */
@Composable
private fun EmptyRow() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = stringResource(R.string.location_notes_empty_title),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.location_notes_empty_desc),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/**
 * Error row - matches iOS errorRow
 */
@Composable
private fun ErrorRow(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⚠",
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = message,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.dismiss),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onDismiss)
        )
    }
}

/**
 * Input section - matches main chat input exactly
 */
@Composable
private fun LocationNotesInputSection(
    draft: String,
    onDraftChange: (String) -> Unit,
    sendButtonEnabled: Boolean,
    accentGreen: Color,
    backgroundColor: Color,
    onSend: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 8.dp), // Match main chat padding
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp) // Match main chat spacing
    ) {
        // Text input with placeholder overlay (matches main chat exactly)
        Box(
            modifier = Modifier.weight(1f)
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colorScheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = { if (sendButtonEnabled) onSend() }
                ),
                modifier = Modifier.fillMaxWidth()
            )
            
            // Placeholder when empty (matches main chat)
            if (draft.isEmpty()) {
                Text(
                    text = stringResource(R.string.location_notes_input_placeholder),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        
        // Send button - circular with icon (matches main chat exactly)
        IconButton(
            onClick = { if (sendButtonEnabled) onSend() },
            enabled = sendButtonEnabled,
            modifier = Modifier.size(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = if (!sendButtonEnabled) {
                            colorScheme.onSurface.copy(alpha = 0.3f)
                        } else {
                            accentGreen.copy(alpha = 0.75f)
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = stringResource(R.string.send_message),
                    modifier = Modifier.size(20.dp),
                    tint = if (!sendButtonEnabled) {
                        colorScheme.onSurface.copy(alpha = 0.5f)
                    } else if (isDark) {
                        Color.Black // Black arrow on green in dark theme
                    } else {
                        Color.White // White arrow on green in light theme
                    }
                )
            }
        }
    }
}

/**
 * Timestamp text - matches iOS timestampText exactly
 * Shows relative time for < 7 days, absolute date otherwise
 */
private fun timestampText(createdAt: Int): String {
    val date = Date(createdAt * 1000L)
    val now = Date()
    
    // Calculate days difference
    val calendar = Calendar.getInstance()
    calendar.time = date
    val dateDay = calendar.get(Calendar.DAY_OF_YEAR)
    val dateYear = calendar.get(Calendar.YEAR)
    
    calendar.time = now
    val nowDay = calendar.get(Calendar.DAY_OF_YEAR)
    val nowYear = calendar.get(Calendar.YEAR)
    
    val daysDiff = if (dateYear == nowYear) {
        nowDay - dateDay
    } else {
        // Simplified: just check if less than 7 days by timestamp
        val diff = (now.time - date.time) / (1000 * 60 * 60 * 24)
        diff.toInt()
    }
    
    return if (daysDiff < 7) {
        // Relative formatting (abbreviated)
        val diffMillis = now.time - date.time
        val diffSeconds = diffMillis / 1000
        
        when {
            diffSeconds < 60 -> "" // Don't show "just now" in iOS
            diffSeconds < 3600 -> {
                val minutes = (diffSeconds / 60).toInt()
                "${minutes}m ago"
            }
            diffSeconds < 86400 -> {
                val hours = (diffSeconds / 3600).toInt()
                "${hours}h ago"
            }
            else -> {
                val days = (diffSeconds / 86400).toInt()
                "${days}d ago"
            }
        }
    } else {
        // Absolute date formatting
        val sameYear = dateYear == nowYear
        val formatter = if (sameYear) {
            SimpleDateFormat("MMM d", Locale.getDefault())
        } else {
            SimpleDateFormat("MMM d, y", Locale.getDefault())
        }
        formatter.format(date)
    }
}
