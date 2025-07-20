package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.bitchat.android.model.PatientStatus
import com.bitchat.android.model.Priority

/**
 * Dashboard screen showing overview of devices and patient data
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    chatViewModel: ChatViewModel,
    patientViewModel: PatientViewModel,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val connectedPeers by chatViewModel.connectedPeers.observeAsState(emptyList())
    val patients by patientViewModel.patients.observeAsState(emptyList())
    
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Healthcare Dashboard",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary
            )
        }
        
        // Device status cards
        item {
            DeviceStatusSection(
                connectedDevices = connectedPeers.size,
                colorScheme = colorScheme
            )
        }
        
        // Patient data cards  
        item {
            PatientDataSection(
                patients = patients,
                colorScheme = colorScheme
            )
        }
        
        // Recent activity
        item {
            RecentActivitySection(
                patients = patients.take(3),
                colorScheme = colorScheme
            )
        }
    }
}

@Composable
fun DeviceStatusSection(
    connectedDevices: Int,
    colorScheme: ColorScheme
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.DeviceHub,
                    contentDescription = "Devices",
                    tint = colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Network Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusCard(
                    title = "Connected Devices",
                    value = connectedDevices.toString(),
                    icon = Icons.Default.Wifi,
                    color = if (connectedDevices > 0) Color(0xFF4CAF50) else Color(0xFFFF5722),
                    colorScheme = colorScheme
                )
            }
        }
    }
}

@Composable
fun PatientDataSection(
    patients: List<com.bitchat.android.model.PatientRecord>,
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
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.LocalHospital,
                    contentDescription = "Patients",
                    tint = colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Patient Data",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusCard(
                    title = "Total Patients",
                    value = patients.size.toString(),
                    icon = Icons.Default.People,
                    color = colorScheme.secondary,
                    colorScheme = colorScheme,
                    modifier = Modifier.weight(1f)
                )
                
                StatusCard(
                    title = "Critical",
                    value = patients.count { it.status == PatientStatus.CRITICAL }.toString(),
                    icon = Icons.Default.Warning,
                    color = Color(0xFFFF5722),
                    colorScheme = colorScheme,
                    modifier = Modifier.weight(1f)
                )
                
                StatusCard(
                    title = "Stable",
                    value = patients.count { it.status == PatientStatus.STABLE }.toString(),
                    icon = Icons.Default.CheckCircle,
                    color = Color(0xFF4CAF50),
                    colorScheme = colorScheme,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun StatusCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun RecentActivitySection(
    patients: List<com.bitchat.android.model.PatientRecord>,
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
                    imageVector = Icons.Default.History,
                    contentDescription = "Recent Activity",
                    tint = colorScheme.tertiary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Recent Patients",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (patients.isEmpty()) {
                Text(
                    text = "No patient records yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                patients.forEach { patient ->
                    PatientSummaryItem(
                        patient = patient,
                        colorScheme = colorScheme
                    )
                    if (patient != patients.last()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = colorScheme.outline.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PatientSummaryItem(
    patient: com.bitchat.android.model.PatientRecord,
    colorScheme: ColorScheme
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        Box(
            modifier = Modifier
                .size(12.dp)
                .padding(end = 8.dp)
        ) {
            val statusColor = when (patient.status) {
                PatientStatus.CRITICAL -> Color(0xFFFF5722)
                PatientStatus.STABLE -> Color(0xFF4CAF50)
                PatientStatus.TREATED -> Color(0xFF2196F3)
                PatientStatus.TRANSFERRED -> Color(0xFFFF9800)
            }
            
            Card(
                modifier = Modifier.size(8.dp),
                colors = CardDefaults.cardColors(containerColor = statusColor)
            ) {}
        }
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${patient.patientId} - ${patient.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = patient.status.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        
        // Priority indicator
        if (patient.priority == Priority.HIGH || patient.priority == Priority.URGENT) {
            Icon(
                imageVector = Icons.Default.PriorityHigh,
                contentDescription = "High Priority",
                tint = Color(0xFFFF5722),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
