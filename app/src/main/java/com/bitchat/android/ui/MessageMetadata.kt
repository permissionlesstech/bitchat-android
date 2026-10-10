package com.bitchat.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.ui.theme.BitchatFontFamily
import java.text.SimpleDateFormat

/** Reserve the receipt slot before the first status arrives, for text and media alike. */
@Composable
internal fun MessageMetadata(
    message: BitchatMessage,
    timeFormatter: SimpleDateFormat,
    showDeliveryStatus: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text = formatTextMessageMetadata(message, timeFormatter), fontFamily = BitchatFontFamily)
        if (showDeliveryStatus) {
            Spacer(Modifier.width(4.dp))
            val status = message.deliveryStatus
            Box(
                Modifier.graphicsLayer { alpha = if (status == null) 0f else 1f }
                    .then(if (status == null) Modifier.clearAndSetSemantics {} else Modifier),
            ) {
                DeliveryStatusIcon(status ?: DeliveryStatus.Sending)
            }
        }
    }
}
