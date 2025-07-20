package com.bitchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.*

/**
 * Patient data models for healthcare application
 * These models represent patient records and medical data
 */

@Parcelize
data class PatientRecord(
    val id: String = UUID.randomUUID().toString(),
    val patientId: String,                          // Human-readable ID (P123456)
    val name: String,
    val age: Int? = null,
    val gender: String? = null,
    val bloodType: String? = null,
    val allergies: List<String> = emptyList(),
    val currentMedications: List<String> = emptyList(),
    val medicalHistory: String = "",
    val presentingComplaint: String = "",
    val treatment: String = "",
    val status: PatientStatus = PatientStatus.STABLE,
    val priority: Priority = Priority.LOW,
    val location: String? = null,
    val authorFingerprint: String = "",
    val lastModified: Date = Date(),
    val version: Int = 1
) : Parcelable

enum class PatientStatus(val value: String, val displayName: String) {
    STABLE("stable", "Stable"),
    CRITICAL("critical", "Critical"),
    TREATED("treated", "Treated"),
    TRANSFERRED("transferred", "Transferred"),
}

enum class Priority(val value: String, val displayName: String) {
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
    URGENT("urgent", "Urgent")
}

@Parcelize
data class MedicalUpdate(
    val id: String = UUID.randomUUID().toString(),
    val patientId: String,
    val updateType: UpdateType,
    val notes: String,
    val vitals: Vitals? = null,
    val authorFingerprint: String = "",
    val timestamp: Date = Date()
) : Parcelable

enum class UpdateType(val value: String, val displayName: String) {
    ASSESSMENT("assessment", "Assessment"),
    TREATMENT("treatment", "Treatment"),
    STATUS_CHANGE("statusChange", "Status Change"),
    TRANSFER("transfer", "Transfer")
}

@Parcelize
data class Vitals(
    val bloodPressure: String? = null,
    val heartRate: Int? = null,
    val temperature: Double? = null,
    val oxygenSaturation: Int? = null,
    val painLevel: Int? = null                      // 1-10 scale
) : Parcelable
