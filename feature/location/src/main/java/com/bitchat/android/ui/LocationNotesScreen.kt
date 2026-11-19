package com.bitchat.android.ui

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.BitchatTheme
import com.bitchat.android.feature.location.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.core.ui.component.screen.BitchatScreenTopBar
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.nostr.LocationNotesManager

/**
 * The location notes destination's stateless screen: the notes once the building is
 * known, and otherwise an acquiring or an unavailable state.
 *
 * The three states swap inside one destination rather than each being one of
 * their own, and each is a full screen with a back arrow and its own insets.
 */
@Composable
internal fun LocationNotesScreen(
    uiState: LocationNotesUiState,
    onAction: (LocationNotesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onBack = { onAction(LocationNotesAction.BackClicked) }
    when (val mode = uiState.mode) {
        is NotesMode.Notes -> LocationNotesContent(
            mode = mode,
            uiState = uiState,
            onAction = onAction,
            onBack = onBack,
            modifier = modifier,
        )
        NotesMode.Acquiring -> LocationNotesAcquiringContent(onBack = onBack, modifier = modifier)
        NotesMode.Unavailable -> LocationNotesErrorContent(
            onBack = onBack,
            onEnableLocation = { onAction(LocationNotesAction.EnableLocationClicked) },
            modifier = modifier,
        )
    }
}

/**
 * Location notes content - EXACT iOS UI match for bitchat
 * Matches iOS LocationNotesView.swift exactly in style, colors, fonts, and text
 */
@Composable
private fun LocationNotesContent(
    mode: NotesMode.Notes,
    uiState: LocationNotesUiState,
    onAction: (LocationNotesAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val accentGreen = colorScheme.primary
    val state = mode.loadState
    val count = mode.notes.size

    // Scroll state
    val listState = rememberLazyListState()

    Box(modifier = modifier.locationNotesScreenRoot()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 64.dp, bottom = 20.dp)
        ) {
            item(key = "notes_header") {
                LocationNotesHeader(
                    locationName = mode.displayLocationName,
                    state = state,
                    accentGreen = accentGreen,
                )
            }

            // Notes content (matches iOS notesContent)
            when (notesBody(state, mode.initialLoadComplete, hasNotes = mode.notes.isNotEmpty())) {
                NotesBody.NoRelays -> {
                    item {
                        NoRelaysRow(
                            onRetry = { onAction(LocationNotesAction.RetryClicked) }
                        )
                    }
                }
                NotesBody.Loading -> {
                    item {
                        LoadingRow()
                    }
                }
                NotesBody.Empty -> {
                    item {
                        EmptyRow()
                    }
                }
                NotesBody.Notes -> {
                    items(mode.notes, key = { it.id }) { note ->
                        NoteRow(note = note)
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }

            // Error row (matches iOS errorRow)
            mode.errorMessage?.let { error ->
                if (notesErrorShown(error, state)) {
                    item {
                        ErrorRow(
                            message = error,
                            onDismiss = { onAction(LocationNotesAction.DismissError) }
                        )
                    }
                }
            }
        }

        BitchatScreenTopBar(
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
            title = {
                BitchatSheetTitle(
                    text = pluralStringResource(
                        id = R.plurals.location_notes_title,
                        count = count,
                        mode.geohash,
                        count
                    )
                )
            }
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
        ){
            Column {
                // Divider before input (matches iOS overlay)
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                    thickness = 1.dp
                )

                // Input section (matches iOS inputSection)
                LocationNotesInputSection(
                    draft = uiState.draft,
                    onDraftChange = { onAction(LocationNotesAction.DraftChanged(it)) },
                    sendButtonEnabled = uiState.sendEnabled,
                    accentGreen = accentGreen,
                    authorBaseName = uiState.authorBaseName,
                    onSend = { onAction(LocationNotesAction.SendClicked) }
                )
            }
        }
    }
}

/**
 * Header section - matches iOS headerSection exactly
 * Shows: "#geohash • X notes", location name, description
 */
@Composable
private fun LocationNotesHeader(
    locationName: String?,
    state: LocationNotesManager.State,
    accentGreen: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
    ) {
        // Location name in green (building or block)
        locationName?.let { name ->
            if (name.isNotEmpty()) {
                Text(
                    text = name,
                    fontFamily = BitchatFontFamily,
                    fontSize = 12.sp,
                    color = accentGreen
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        
        // Description
        Text(
            text = stringResource(R.string.location_notes_description),
            fontFamily = BitchatFontFamily,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        
        // Relays paused message if no relays
        if (notesRelaysUnavailableShown(state)) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.location_notes_relays_unavailable),
                fontFamily = BitchatFontFamily,
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
private fun NoteRow(note: NoteUi) {
    val baseName = note.authorBaseName
    val ts = note.timestampText
    
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
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (ts.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = ts,
                    fontFamily = BitchatFontFamily,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(2.dp))
        
        // Second row: content
        Text(
            text = note.content,
            fontFamily = BitchatFontFamily,
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
            fontFamily = BitchatFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.location_notes_no_relays_desc),
            fontFamily = BitchatFontFamily,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.retry),
            fontFamily = BitchatFontFamily,
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
            fontFamily = BitchatFontFamily,
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
            fontFamily = BitchatFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.location_notes_empty_desc),
            fontFamily = BitchatFontFamily,
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
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.dismiss),
            fontFamily = BitchatFontFamily,
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
    authorBaseName: String?,
    onSend: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (authorBaseName != null) {
            val baseName = authorBaseName
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "@$baseName",
                    fontFamily = BitchatFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    fontFamily = BitchatFontFamily
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
                        fontFamily = BitchatFontFamily
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
                    } else {
                        colorScheme.onPrimary
                    }
                )
            }
        }
    }
    }
}

/** Synthetic notes state for previews and screen tests: two notes, no real location. */
internal fun previewLocationNotesUiState(): LocationNotesUiState = LocationNotesUiState(
    mode = NotesMode.Notes(
        geohash = "s0000000",
        displayLocationName = "Synthetic Building",
        notes = listOf(
            NoteUi(
                id = "note-1",
                authorBaseName = "tester",
                timestampText = "5m ago",
                content = "synthetic note",
            ),
            NoteUi(
                id = "note-2",
                authorBaseName = "alice",
                timestampText = "2h ago",
                content = "another synthetic note",
            ),
        ),
        loadState = LocationNotesManager.State.READY,
        errorMessage = null,
        initialLoadComplete = true,
    ),
    draft = "",
    sendEnabled = false,
    authorBaseName = "alice",
    locationEnabled = true,
    locationAuthorized = true,
)

@Preview(name = "Notes")
@Preview(name = "Notes (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun LocationNotesNotesPreview() {
    BitchatTheme {
        LocationNotesScreen(uiState = previewLocationNotesUiState(), onAction = {})
    }
}

@Preview(name = "Acquiring")
@Preview(name = "Acquiring (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun LocationNotesAcquiringPreview() {
    BitchatTheme {
        LocationNotesScreen(
            uiState = previewLocationNotesUiState().copy(mode = NotesMode.Acquiring),
            onAction = {},
        )
    }
}

@Preview(name = "Unavailable")
@Preview(name = "Unavailable (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun LocationNotesUnavailablePreview() {
    BitchatTheme {
        LocationNotesScreen(
            uiState = previewLocationNotesUiState().copy(
                mode = NotesMode.Unavailable,
                locationEnabled = false,
                locationAuthorized = false,
            ),
            onAction = {},
        )
    }
}
