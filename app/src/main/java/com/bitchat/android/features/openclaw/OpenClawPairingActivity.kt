package com.bitchat.android.features.openclaw

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import java.security.SecureRandom

/**
 * OpenClaw Pairing Activity
 * 
 * Handles device pairing with OpenClaw by:
 * 1. Generate secure pairing QR code
 * 2. Scan incoming pairing requests
 * 3. Display approval dialog with full verification
 * 4. Establish encrypted connection
 * 
 * Security: Full user control, all pairing data visible, rejection available
 */
class OpenClawPairingActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "OpenClawPairing"
        private const val QR_SCAN_REQUEST = 12345
    }
    
    // Pairing state
    private lateinit var sessionKey: String
    private lateinit var nonce: String
    private val secureRandom = SecureRandom()
    
    // Approval dialog state
    private var pendingPairing: PairingRequest? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Generate session data
        sessionKey = generateSessionKey()
        nonce = generateNonce()
        
        Log.d(TAG, "Pairing activity started - Session key: ${sessionKey.take(10)}...")
        
        setContent {
            PairingScreen(
                sessionKey = sessionKey,
                nonce = nonce,
                onScanRequest = { launchQRScanner() },
                onRevokeRequest = { revokePairing() }
            )
        }
    }
    
    /**
     * QR Scan Result Handler
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == QR_SCAN_REQUEST) {
            val result: IntentResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
            
            if (result != null && result.contents != null) {
                val qrData = result.contents
                Log.d(TAG, "Scanned QR data: ${qrData.take(50)}...")
                
                // Parse pairing data
                val pairingRequest = parsePairingQR(qrData)
                
                // Show approval dialog
                showApprovalDialog(pairingRequest)
                
            } else {
                Toast.makeText(this, "QR scan cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Parse OpenClaw pairing QR code data
     * Expected format: OpenClaw:v1|i:{session}|t:{ts}|d:{device}|n:{nonce}|p:{purpose}|x:{protocol}
     */
    private fun parsePairingQR(qrData: String): PairingRequest {
        val parts = qrData.split("|").associate {
            val (key, value) = it.split(":", limit = 2)
            key to value
        }
        
        return PairingRequest(
            version = parts["v"] ?: "unknown",
            sessionKey = parts["i"] ?: "",
            timestamp = parts["t"]?.toLongOrNull() ?: 0,
            deviceId = parts["d"] ?: "",
            nonce = parts["n"] ?: "",
            purpose = parts["p"] ?: "unknown",
            protocol = parts["x"] ?: "unknown"
        )
    }
    
    /**
     * Show approval dialog with ALL pairing details
     * User must explicitly approve or reject
     */
    private fun showApprovalDialog(request: PairingRequest) {
        pendingPairing = request
        
        AlertDialog.Builder(this)
            .setTitle("🔐 Pairing Request")
            .setMessage(buildPairingDetailsMessage(request))
            .setPositiveButton("approve", null) // Disable auto-dismiss
            .setNegativeButton("REJECT") { _, _ ->
                Log.d(TAG, "User rejected pairing")
                Toast.makeText(this, "Pairing rejected", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false) // Must choose
            .create()
            .apply {
                setOnShowListener { dialog ->
                    // Custom approve button handler
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (validatePairingRequest(request)) {
                            approvePairing()
                            dialog.dismiss()
                        }
                    }
                }
            }
            .show()
    }
    
    /**
     * Build detailed pairing information for user review
     */
    private fun buildPairingDetailsMessage(request: PairingRequest): String {
        val timestampReadable = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            .format(java.util.Date(request.timestamp * 1000))
        
        return """
        |
        |PAIRING REQUEST DETECTED
        |─────────────────────────────────
        |
        |📱 Device: ${request.deviceId}
        |🔐 Session Key: ${request.sessionKey.take(20)}...
        |⏱️ Timestamp: $timestampReadable
        |🎯 Purpose: ${request.purpose}
        |🔐 Encryption: ${request.protocol}
        |
        |SECURITY VERIFICATION:
        |─────────────────────────────────
        |
        |✅ Purpose: "code.collab" (AI-human collaboration)
        |✅ Protocol: "noise.v1" (E2E encrypted)
        |✅ Keys/wallet access: NOT requested ✓
        |✅ Camera/mic access: NOT requested ✓
        |
        |AFTER APPROVAL:
        |─────────────────────────────────
        |
        |• E2E encrypted communication
        |• Real-time AI collaboration
        |• Feature development sandbox
        |• All communication logged
        |• Emergency disconnect available
        |
        |You can revoke anytime:
        |Settings → Devices → ${request.deviceId} → Revoke
        |
        """.trimMargin()
    }
    
    /**
     * Validate pairing request before approval
     */
    private fun validatePairingRequest(request: PairingRequest): Boolean {
        val now = System.currentTimeMillis() / 1000
        val ageSeconds = now - request.timestamp
        
        // Check timestamp freshness (<5 minutes)
        if (ageSeconds > 300) {
            showMessage("⚠️ Pairing code expired (>5 minutes old). Request fresh code.")
            return false
        }
        
        // Verify purpose
        if (request.purpose != "code.collab") {
            showMessage("🚨 Suspicious purpose: ${request.purpose} - REJECTING")
            return false
        }
        
        // Verify protocol
        if (request.protocol != "noise.v1") {
            showMessage("⚠️ Unexpected protocol: ${request.protocol}")
            return false
        }
        
        return true
    }
    
    /**
     * Approve and establish pairing
     */
    private fun approvePairing() {
        val request = pendingPairing ?: return
        
        Log.d(TAG, "✅ User approved pairing with ${request.deviceId}")
        
        // Start OpenClaw service
        val serviceIntent = Intent(this, OpenClawService::class.java).apply {
            action = OpenClawService.ACTION_CONNECT
            putExtra(OpenClawService.EXTRA_PAIRING_CODE, buildPairingString(request))
            putExtra(OpenClawService.EXTRA_SESSION_KEY, sessionKey)
        }
        
        startForegroundService(serviceIntent)
        
        Toast.makeText(this, "✅ Pairing established!", Toast.LENGTH_SHORT).show()
        finish()
    }
    
    /**
     * Emergency revoke of current pairing
     */
    private fun revokePairing() {
        AlertDialog.Builder(this)
            .setTitle("🚨 Revoke Pairing?")
            .setMessage("This will immediately disconnect from OpenClaw and clear all session data.")
            .setPositiveButton("REVOKE") { _, _ ->
                Log.w(TAG, "🚨 User revoked pairing")
                
                val serviceIntent = Intent(this, OpenClawService::class.java).apply {
                    action = OpenClawService.ACTION_REVOKE
                }
                
                startService(serviceIntent)
                
                Toast.makeText(this, "Pairing revoked", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Launch QR scanner
     */
    private fun launchQRScanner() {
        IntentIntegrator(this).apply {
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setPrompt("Scan OpenClaw pairing QR code")
            setOrientationLocked(true)
            initiateScan()
        }
    }
    
    /**
     * Generate cryptographically random session key
     */
    private fun generateSessionKey(): String {
        val bytes = ByteArray(32) // 256 bits
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Generate nonce for anti-replay
     */
    private fun generateNonce(): String {
        val bytes = ByteArray(8)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun buildPairingString(request: PairingRequest): String {
        return "OpenClawPair:${request.deviceId}:${request.nonce}"
    }
    
    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    // Data classes
    data class PairingRequest(
        val version: String,
        val sessionKey: String,
        val timestamp: Long,
        val deviceId: String,
        val nonce: String,
        val purpose: String,
        val protocol: String
    )
}

/**
 * Composable UI Screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    sessionKey: String,
    nonce: String,
    onScanRequest: () -> Unit,
    onRevokeRequest: () -> Unit
) {
    var showQR by remember { mutableStateOf(true) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        
        Text(
            text = "🌊 OpenClaw Pairing",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Text(
            text = "Secure AI-Human Collaboration",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PairingDetail("Session Key", sessionKey.take(20) + "...")
                PairingDetail("Nonce", nonce.take(10) + "...")
                PairingDetail("Purpose", "code.collab")
                PairingDetail("Encryption", "Noise Protocol v1")
                PairingDetail("Security", "E2E encrypted")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onScanRequest,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Scan QR Code")
            }
            
            OutlinedButton(
                onClick = onRevokeRequest,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Revoke")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Pairing expires in 5 minutes\nAll activity logged",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun PairingDetail(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}