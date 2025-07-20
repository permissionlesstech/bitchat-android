package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import java.text.SimpleDateFormat
import java.util.*

/**
 * Patients screen showing list of all patients with details and add functionality
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientsScreen(
    patientViewModel: PatientViewModel,
    onNavigateToDetail: (PatientRecord) -> Unit,
    onNavigateToAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val patients by patientViewModel.patients.observeAsState(emptyList())
    val isLoading by patientViewModel.isLoading.observeAsState(false)
    
    var searchQuery by remember { mutableStateOf("") }
    var selectedStatusFilter by remember { mutableStateOf<PatientStatus?>(null) }
    
    // Filter patients based on search and status
    val filteredPatients = remember(patients, searchQuery, selectedStatusFilter) {
        patients.filter { patient ->
            val matchesSearch = searchQuery.isEmpty() ||
                    patient.name.contains(searchQuery, ignoreCase = true) ||
                    patient.patientId.contains(searchQuery, ignoreCase = true)
            
            val matchesStatus = selectedStatusFilter == null || patient.status == selectedStatusFilter
            
            matchesSearch && matchesStatus
        }.sortedWith(
            compareByDescending<PatientRecord> { it.priority == Priority.URGENT }
                .thenByDescending { it.priority == Priority.HIGH }
                .thenByDescending { it.lastModified }
        )
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header with search and filters
            PatientsHeader(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                selectedStatusFilter = selectedStatusFilter,
                onStatusFilterChange = { selectedStatusFilter = it },
                onAddPatient = onNavigateToAdd,
                colorScheme = colorScheme
            )
            
            // Patients list
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredPatients.isEmpty()) {
                EmptyPatientsState(
                    hasSearchQuery = searchQuery.isNotEmpty() || selectedStatusFilter != null,
                    onAddPatient = onNavigateToAdd,
                    colorScheme = colorScheme
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredPatients) { patient ->
                        PatientListItem(
                            patient = patient,
                            onClick = { onNavigateToDetail(patient) },
                            colorScheme = colorScheme
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientsHeader(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedStatusFilter: PatientStatus?,
    onStatusFilterChange: (PatientStatus?) -> Unit,
    onAddPatient: () -> Unit,
    colorScheme: ColorScheme
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Title, Sync button, and Add button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left section with sync button and title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Sync button
                    IconButton(
                        onClick = { /* Sync functionality to be added later */ },
                        modifier = Modifier
                            .size(36.dp)
                            .background(colorScheme.primaryContainer, shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sync Records",
                            tint = colorScheme.onPrimaryContainer
                        )
                    }
                    
                    Text(
                        text = "Patient Records",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary
                    )
                }
                
                // Add button (right side)
                FloatingActionButton(
                    onClick = onAddPatient,
                    containerColor = colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Patient",
                        tint = colorScheme.onPrimary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("Search patients...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search"
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Status filter chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedStatusFilter == null,
                        onClick = { onStatusFilterChange(null) },
                        label = { Text("All") }
                    )
                }
                
                items(PatientStatus.values().toList()) { status ->
                    FilterChip(
                        selected = selectedStatusFilter == status,
                        onClick = { 
                            onStatusFilterChange(if (selectedStatusFilter == status) null else status)
                        },
                        label = { Text(status.displayName) }
                    )
                }
            }
        }
    }
}

@Composable
fun PatientListItem(
    patient: PatientRecord,
    onClick: () -> Unit,
    colorScheme: ColorScheme
) {
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = when (patient.status) {
                            PatientStatus.CRITICAL -> Color(0xFFFF5722)
                            PatientStatus.STABLE -> Color(0xFF4CAF50)
                            PatientStatus.TREATED -> Color(0xFF2196F3)
                            PatientStatus.TRANSFERRED -> Color(0xFFFF9800)
                        },
                        shape = RoundedCornerShape(6.dp)
                    )
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                // Patient ID and Name
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = patient.patientId,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = patient.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Age, Gender, Status
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    patient.age?.let { age ->
                        Text(
                            text = "${age}y",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            text = " • ",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    patient.gender?.let { gender ->
                        Text(
                            text = gender,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            text = " • ",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        text = patient.status.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (patient.status) {
                            PatientStatus.CRITICAL -> Color(0xFFFF5722)
                            PatientStatus.STABLE -> Color(0xFF4CAF50)
                            else -> colorScheme.onSurface.copy(alpha = 0.7f)
                        },
                        fontWeight = if (patient.status == PatientStatus.CRITICAL) FontWeight.Bold else FontWeight.Normal
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Presenting complaint (if not empty)
                if (patient.presentingComplaint.isNotEmpty()) {
                    Text(
                        text = patient.presentingComplaint,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurface.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                }
                
                // Last modified
                Text(
                    text = "Updated: ${dateFormatter.format(patient.lastModified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
            
            Column(
                horizontalAlignment = Alignment.End
            ) {
                // Priority indicator
                if (patient.priority == Priority.HIGH || patient.priority == Priority.URGENT) {
                    Icon(
                        imageVector = Icons.Default.PriorityHigh,
                        contentDescription = "High Priority",
                        tint = Color(0xFFFF5722),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                
                // Location
                patient.location?.let { location ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Location",
                            tint = colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = location,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                }
                
                // Navigation arrow
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "View Details",
                    tint = colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun EmptyPatientsState(
    hasSearchQuery: Boolean,
    onAddPatient: () -> Unit,
    colorScheme: ColorScheme
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (hasSearchQuery) Icons.Default.SearchOff else Icons.Default.PersonOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.4f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = if (hasSearchQuery) "No patients found" else "No patient records yet",
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = if (hasSearchQuery) "Try adjusting your search or filters" else "Add your first patient to get started",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface.copy(alpha = 0.5f)
            )
            
            if (!hasSearchQuery) {
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onAddPatient,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Patient")
                }
            }
        }
    }
}
