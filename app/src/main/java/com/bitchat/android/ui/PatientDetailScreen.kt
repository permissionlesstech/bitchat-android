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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.model.PatientRecord
import com.bitchat.android.model.PatientStatus
import com.bitchat.android.model.Priority
import com.bitchat.android.model.MedicalUpdate
import java.text.SimpleDateFormat
import java.util.*

/**
 * Patient detail screen showing comprehensive patient information
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientDetailScreen(
    patient: PatientRecord,
    patientViewModel: PatientViewModel,
    onBack: () -> Unit,
    onEdit: (PatientRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val medicalUpdates by patientViewModel.medicalUpdates.observeAsState(emptyMap())
    val patientUpdates = medicalUpdates[patient.patientId] ?: emptyList()
    
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Header with back button and edit
        PatientDetailHeader(
            patient = patient,
            onBack = onBack,
            onEdit = { onEdit(patient) },
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientDetailHeader(
    patient: PatientRecord,
    onBack: () -> Unit,
    onEdit: () -> Unit,
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
                        PatientStatus.DECEASED -> Color(0xFF9E9E9E)
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
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onStatusUpdate(PatientStatus.STABLE) },
                    enabled = patient.status != PatientStatus.STABLE,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stable")
                }
                
                OutlinedButton(
                    onClick = { onStatusUpdate(PatientStatus.TREATED) },
                    enabled = patient.status != PatientStatus.TREATED,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.MedicalServices,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Treated")
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
