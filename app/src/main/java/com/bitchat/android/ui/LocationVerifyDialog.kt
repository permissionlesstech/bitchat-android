package com.bitchat.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun IncomingLocationVerifyRequestDialog(
    viewModel: ChatViewModel
) {
    val incomingVerifyRequestPeer by viewModel.incomingLocationVerifyRequest.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()

    if (incomingVerifyRequestPeer != null) {
        val requestPeerID = incomingVerifyRequestPeer!!
        val requesterName = peerNicknames[requestPeerID] ?: requestPeerID.take(8)

        AlertDialog(
            onDismissRequest = { viewModel.rejectLocationVerificationRequest(requestPeerID) },
            title = {
                Text(
                    text = "LOCATION SHARE REQUEST",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF00FF66)
                )
            },
            text = {
                Text(
                    text = "$requesterName wants to share live radar location with you. Do you accept?",
                    color = Color.White,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.acceptLocationVerificationRequest(requestPeerID) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C851))
                ) {
                    Text("ACCEPT", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                Button(
                    onClick = { viewModel.rejectLocationVerificationRequest(requestPeerID) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                ) {
                    Text("REJECT", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            containerColor = Color(0xFF14241A),
            shape = RoundedCornerShape(16.dp)
        )
    }
}
