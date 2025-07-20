package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bitchat.android.model.PatientRecord
import com.bitchat.android.model.PatientStatus
import com.bitchat.android.model.Priority
import com.bitchat.android.model.PatientHistoryEntry
import java.util.*

/**
 * Screen for editing an existing patient record
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPatientScreen(
    patient: PatientRecord,
    patientViewModel: PatientViewModel,
    onBack: () -> Unit,
    onPatientUpdated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    // Form state - pre-populated with existing patient data
    var patientId by remember { mutableStateOf(patient.patientId) }
    var name by remember { mutableStateOf(patient.name) }
    var age by remember { mutableStateOf(patient.age?.toString() ?: "") }
    var gender by remember { mutableStateOf(patient.gender ?: "") }
    var bloodType by remember { mutableStateOf(patient.bloodType ?: "") }
    var allergies by remember { mutableStateOf(patient.allergies.joinToString(", ")) }
    var medications by remember { mutableStateOf(patient.currentMedications.joinToString(", ")) }
    var medicalHistory by remember { mutableStateOf(patient.medicalHistory) }
    var presentingComplaint by remember { mutableStateOf(patient.presentingComplaint) }
    var treatment by remember { mutableStateOf(patient.treatment) }
    var location by remember { mutableStateOf(patient.location ?: "") }
    var newHistoryEntry by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf(patient.status) }
    var selectedPriority by remember { mutableStateOf(patient.priority) }
    
    // Validation state
    var showErrors by remember { mutableStateOf(false) }
    
    val isFormValid = name.isNotBlank() && patientId.isNotBlank() && presentingComplaint.isNotBlank()
    
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Header
        EditPatientHeader(
            onBack = onBack,
            onSave = {
                if (isFormValid) {
                    val updatedPatient = patient.copy(
                        patientId = patientId.trim(),
                        name = name.trim(),
                        age = age.toIntOrNull(),
                        gender = gender.trim().takeIf { it.isNotBlank() },
                        bloodType = bloodType.trim().takeIf { it.isNotBlank() },
                        allergies = allergies.split(",").map { it.trim() }.filter { it.isNotBlank() },
                        currentMedications = medications.split(",").map { it.trim() }.filter { it.isNotBlank() },
                        medicalHistory = medicalHistory.trim(),
                        presentingComplaint = presentingComplaint.trim(),
                        treatment = treatment.trim(),
                        status = selectedStatus,
                        priority = selectedPriority,
                        location = location.trim().takeIf { it.isNotBlank() },
                        lastModified = Date(),
                        version = patient.version + 1,
                        historyEntries = if (newHistoryEntry.isNotBlank()) {
                            patient.historyEntries + PatientHistoryEntry(
                                text = "Edit note: $newHistoryEntry",
                                authorFingerprint = "current_user", // In real app, get from auth
                                timestamp = Date()
                            )
                        } else {
                            patient.historyEntries
                        }
                    )
                    
                    patientViewModel.updatePatient(updatedPatient)
                    onPatientUpdated()
                } else {
                    showErrors = true
                }
            },
            isFormValid = isFormValid,
            colorScheme = colorScheme
        )
        
        // Form content
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Patient identification
            item {
                PatientIdentificationSection(
                    patientId = patientId,
                    onPatientIdChange = { patientId = it },
                    name = name,
                    onNameChange = { name = it },
                    showErrors = showErrors,
                    colorScheme = colorScheme
                )
            }
            
            // Demographics
            item {
                DemographicsSection(
                    age = age,
                    onAgeChange = { age = it },
                    gender = gender,
                    onGenderChange = { gender = it },
                    bloodType = bloodType,
                    onBloodTypeChange = { bloodType = it },
                    colorScheme = colorScheme
                )
            }
            
            // Status and Priority
            item {
                StatusPrioritySection(
                    selectedStatus = selectedStatus,
                    onStatusChange = { selectedStatus = it },
                    selectedPriority = selectedPriority,
                    onPriorityChange = { selectedPriority = it },
                    colorScheme = colorScheme
                )
            }
            
            // Medical information
            item {
                MedicalInformationSection(
                    presentingComplaint = presentingComplaint,
                    onPresentingComplaintChange = { presentingComplaint = it },
                    treatment = treatment,
                    onTreatmentChange = { treatment = it },
                    medicalHistory = medicalHistory,
                    onMedicalHistoryChange = { medicalHistory = it },
                    showErrors = showErrors,
                    colorScheme = colorScheme
                )
            }
            
            // Allergies and medications
            item {
                AllergiesMedicationsSection(
                    allergies = allergies,
                    onAllergiesChange = { allergies = it },
                    medications = medications,
                    onMedicationsChange = { medications = it },
                    colorScheme = colorScheme
                )
            }
            
            // Location
            item {
                LocationSection(
                    location = location,
                    onLocationChange = { location = it },
                    colorScheme = colorScheme
                )
            }
            
            // Edit Note (optional)
            item {
                EditNoteSection(
                    note = newHistoryEntry,
                    onNoteChange = { newHistoryEntry = it },
                    colorScheme = colorScheme
                )
            }
            
            // Bottom padding for FAB
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPatientHeader(
    onBack: () -> Unit,
    onSave: () -> Unit,
    isFormValid: Boolean,
    colorScheme: ColorScheme
) {
    TopAppBar(
        title = {
            Text(
                text = "Edit Patient",
                fontWeight = FontWeight.Bold
            )
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
            TextButton(
                onClick = onSave,
                enabled = isFormValid
            ) {
                Text("Save")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colorScheme.surface
        )
    )
}

@Composable
fun EditNoteSection(
    note: String,
    onNoteChange: (String) -> Unit,
    colorScheme: ColorScheme
) {
    FormSection(
        title = "Edit Note (Optional)",
        icon = Icons.Default.Note
    ) {
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            label = { Text("Add a note about this edit") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Describe what you changed and why (optional)") },
            minLines = 2,
            maxLines = 4
        )
        
        if (note.isBlank()) {
            Text(
                text = "Optional note that will be added to patient history",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
    }
}
