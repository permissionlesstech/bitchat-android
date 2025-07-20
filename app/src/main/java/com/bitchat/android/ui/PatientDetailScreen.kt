package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.model.PatientRecord
import com.bitchat.android.model.PatientStatus
import com.bitchat.android.model.Priority
import com.bitchat.android.model.MedicalUpdate
import com.bitchat.android.model.PatientHistoryEntry
import java.text.SimpleDateFormat
import java.util.*

/**
 * Patient detail screen showing comprehensive patient information
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientDetailScreen(
    patientId: String,
    patientViewModel: PatientViewModel,
    onBack: () -> Unit,
    onEdit: (PatientRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val patients by patientViewModel.patients.observeAsState(emptyList())
    val patient = patients.find { it.patientId == patientId || it.id == patientId }
        ?: return // If patient not found, don't render
    
    val medicalUpdates by patientViewModel.medicalUpdates.observeAsState(emptyMap())
    val patientUpdates = medicalUpdates[patient.patientId] ?: emptyList()
    
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    
    // State for delete confirmation dialog
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Header with back button, edit and delete
        PatientDetailHeader(
            patient = patient,
            onBack = onBack,
            onEdit = { onEdit(patient) },
            onDelete = { showDeleteConfirmDialog = true },
            colorScheme = colorScheme
        )
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Patient info card
            item {
                PatientInfoCard(
                    patient = patient,
                    colorScheme = colorScheme
                )
            }
            
            // Medical details card
            item {
                MedicalDetailsCard(
                    patient = patient,
                    colorScheme = colorScheme
                )
            }
            
            // Medical updates section
            if (patientUpdates.isNotEmpty()) {
                item {
                    MedicalUpdatesCard(
                        updates = patientUpdates,
                        dateFormatter = dateFormatter,
                        colorScheme = colorScheme
                    )
                }
            }
            
            // Actions card
            item {
                PatientActionsCard(
                    patient = patient,
                    onStatusUpdate = { newStatus ->
                        val updatedPatient = patient.copy(
                            status = newStatus,
                            lastModified = Date()
                        )
                        patientViewModel.updatePatient(updatedPatient)
                    },
                    colorScheme = colorScheme
                )
            }
            
            // Patient History Comments Card
            item {
                PatientHistoryCard(
                    patient = patient,
                    onAddHistoryEntry = { text ->
                        patientViewModel.addHistoryEntry(patient.patientId, text)
                    },
                    colorScheme = colorScheme
                )
            }
        }
        
        // Delete confirmation dialog
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("Delete Patient") },
                text = { Text("Are you sure you want to delete ${patient.name}'s record? This action cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            patientViewModel.deletePatient(patient.patientId)
                            showDeleteConfirmDialog = false
                            onBack() // Go back to patient list after deletion
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red
                        )
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showDeleteConfirmDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientDetailHeader(
    patient: PatientRecord,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    colorScheme: ColorScheme
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = patient.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "ID: ${patient.patientId}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.primary
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        actions = {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit Patient"
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Patient",
                    tint = Color.Red
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colorScheme.surface
        )
    )
}

@Composable
fun PatientInfoCard(
    patient: PatientRecord,
    colorScheme: ColorScheme
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Patient Info",
                    tint = colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Patient Information",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Status and Priority Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusBadge(
                    label = "Status",
                    value = patient.status.displayName,
                    color = when (patient.status) {
                        PatientStatus.CRITICAL -> Color(0xFFFF5722)
                        PatientStatus.STABLE -> Color(0xFF4CAF50)
                        PatientStatus.TREATED -> Color(0xFF2196F3)
                        PatientStatus.TRANSFERRED -> Color(0xFFFF9800)
                    },
                    modifier = Modifier.weight(1f)
                )
                
                StatusBadge(
                    label = "Priority",
                    value = patient.priority.displayName,
                    color = when (patient.priority) {
                        Priority.URGENT -> Color(0xFFFF5722)
                        Priority.HIGH -> Color(0xFFFF9800)
                        Priority.MEDIUM -> Color(0xFFFFC107)
                        Priority.LOW -> Color(0xFF4CAF50)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Demographics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                patient.age?.let { age ->
                    InfoItem(
                        label = "Age",
                        value = "${age} years",
                        modifier = Modifier.weight(1f)
                    )
                }
                
                patient.gender?.let { gender ->
                    InfoItem(
                        label = "Gender",
                        value = gender,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                patient.bloodType?.let { bloodType ->
                    InfoItem(
                        label = "Blood Type",
                        value = bloodType,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            patient.location?.let { location ->
                Spacer(modifier = Modifier.height(12.dp))
                InfoItem(
                    label = "Location",
                    value = location,
                    icon = Icons.Default.LocationOn
                )
            }
        }
    }
}

@Composable
fun MedicalDetailsCard(
    patient: PatientRecord,
    colorScheme: ColorScheme
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.LocalHospital,
                    contentDescription = "Medical Details",
                    tint = colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Medical Details",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (patient.presentingComplaint.isNotEmpty()) {
                InfoItem(
                    label = "Presenting Complaint",
                    value = patient.presentingComplaint,
                    icon = Icons.Default.Description
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            if (patient.treatment.isNotEmpty()) {
                InfoItem(
                    label = "Treatment",
                    value = patient.treatment,
                    icon = Icons.Default.MedicalServices
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            if (patient.medicalHistory.isNotEmpty()) {
                InfoItem(
                    label = "Medical History",
                    value = patient.medicalHistory,
                    icon = Icons.Default.History
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            if (patient.allergies.isNotEmpty()) {
                InfoItem(
                    label = "Allergies",
                    value = patient.allergies.joinToString(", "),
                    icon = Icons.Default.Warning,
                    valueColor = Color(0xFFFF5722)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            if (patient.currentMedications.isNotEmpty()) {
                InfoItem(
                    label = "Current Medications",
                    value = patient.currentMedications.joinToString(", "),
                    icon = Icons.Default.Medication
                )
            }
        }
    }
}

@Composable
fun MedicalUpdatesCard(
    updates: List<MedicalUpdate>,
    dateFormatter: SimpleDateFormat,
    colorScheme: ColorScheme
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Update,
                    contentDescription = "Medical Updates",
                    tint = colorScheme.tertiary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Medical Updates (${updates.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            updates.sortedByDescending { it.timestamp }.forEach { update ->
                MedicalUpdateItem(
                    update = update,
                    dateFormatter = dateFormatter,
                    colorScheme = colorScheme
                )
                if (update != updates.last()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun MedicalUpdateItem(
    update: MedicalUpdate,
    dateFormatter: SimpleDateFormat,
    colorScheme: ColorScheme
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (update.updateType) {
                    com.bitchat.android.model.UpdateType.ASSESSMENT -> Icons.Default.Assessment
                    com.bitchat.android.model.UpdateType.TREATMENT -> Icons.Default.MedicalServices
                    com.bitchat.android.model.UpdateType.STATUS_CHANGE -> Icons.Default.ChangeCircle
                    com.bitchat.android.model.UpdateType.TRANSFER -> Icons.Default.TransferWithinAStation
                },
                contentDescription = update.updateType.displayName,
                tint = colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = update.updateType.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = dateFormatter.format(update.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = update.notes,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun PatientActionsCard(
    patient: PatientRecord,
    onStatusUpdate: (PatientStatus) -> Unit,
    colorScheme: ColorScheme
) {
    // Add local state to track the current selection for immediate UI updates
    var selectedStatus by remember { mutableStateOf(patient.status) }
    
    // Effect to sync local state with the actual patient status when it changes externally
    LaunchedEffect(patient.status) {
        selectedStatus = patient.status
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Status toggle selector
            Text(
                text = "Status",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Using SingleChoiceSegmentedButtonRow for toggle effect
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                PatientStatus.values().forEachIndexed { index, status ->
                    SegmentedButton(
                        // Use local state for immediate UI feedback
                        selected = selectedStatus == status,
                        onClick = { 
                            // Update local state immediately for UI
                            selectedStatus = status
                            // Also update backend state
                            onStatusUpdate(status) 
                        },
                        shape = when (index) {
                            0 -> RoundedCornerShape(
                                topStart = 8.dp,
                                bottomStart = 8.dp,
                                topEnd = 0.dp,
                                bottomEnd = 0.dp
                            )
                            PatientStatus.values().size - 1 -> RoundedCornerShape(
                                topStart = 0.dp,
                                bottomStart = 0.dp,
                                topEnd = 8.dp,
                                bottomEnd = 8.dp
                            )
                            else -> RoundedCornerShape(0.dp)
                        },
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = when (status) {
                                PatientStatus.STABLE -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                                PatientStatus.CRITICAL -> Color(0xFFFF5722).copy(alpha = 0.2f)
                                PatientStatus.TREATED -> Color(0xFF2196F3).copy(alpha = 0.2f)
                                PatientStatus.TRANSFERRED -> Color(0xFFFF9800).copy(alpha = 0.2f)
                            },
                            activeContentColor = when (status) {
                                PatientStatus.STABLE -> Color(0xFF4CAF50)
                                PatientStatus.CRITICAL -> Color(0xFFFF5722)
                                PatientStatus.TREATED -> Color(0xFF2196F3)
                                PatientStatus.TRANSFERRED -> Color(0xFFFF9800)
                            }
                        )
                    ) {
                        Text(
                            text = status.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
fun InfoItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let { iconVector ->
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Card displaying patient history entries (comments) and allowing users to add new entries
 */
@Composable
fun PatientHistoryCard(
    patient: PatientRecord,
    onAddHistoryEntry: (String) -> Unit,
    colorScheme: ColorScheme
) {
    var newEntryText by remember { mutableStateOf("") }
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Patient History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // New entry input field
            OutlinedTextField(
                value = newEntryText,
                onValueChange = { newEntryText = it },
                label = { Text("Add a new comment") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colorScheme.primary,
                    unfocusedBorderColor = colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                ),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (newEntryText.isNotEmpty()) {
                                onAddHistoryEntry(newEntryText)
                                newEntryText = ""
                            }
                        },
                        enabled = newEntryText.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Add comment"
                        )
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // History entries list
            if (patient.historyEntries.isEmpty()) {
                Text(
                    text = "No history entries yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                // Display entries in reverse chronological order (newest first)
                patient.historyEntries.sortedByDescending { it.timestamp }.forEach { entry ->
                    HistoryEntryItem(
                        entry = entry,
                        dateFormatter = dateFormatter,
                        colorScheme = colorScheme
                    )
                    
                    Divider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                    )
                }
            }
        }
    }
}

/**
 * Displays a single history entry (comment) with timestamp
 */
@Composable
fun HistoryEntryItem(
    entry: PatientHistoryEntry,
    dateFormatter: SimpleDateFormat,
    colorScheme: ColorScheme
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Author/timestamp
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.primary.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (entry.authorFingerprint.isNotEmpty()) entry.authorFingerprint else "Provider",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.primary
                )
            }
            
            // Timestamp
            Text(
                text = dateFormatter.format(entry.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Comment text
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}
