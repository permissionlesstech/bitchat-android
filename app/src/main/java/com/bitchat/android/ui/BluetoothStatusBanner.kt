package com.bitchat.android.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.bitchat.android.onboarding.BluetoothStatusManager

@Preview(showBackground = true)
@Composable
fun BluetoothStatusManager(
    message: String = "Bluetooth is off, kindly turn it on"
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Text(
            text = message,
            color = Color.Red,
            style = MaterialTheme.typography.labelSmall,
        )

        Divider(color = colorScheme.outline.copy(alpha = 0.3f))
    }
}