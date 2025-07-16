package com.bitchat.android.wallet.ui.mintinfo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.wallet.data.Mint

/**
 * Action buttons section for mint operations
 */
@Composable
fun MintActionButtons(
    mint: Mint,
    onEditNickname: () -> Unit,
    onCopyUrl: () -> Unit,
    onDeleteMint: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section divider and title
        SectionDivider(title = "ACTIONS")
        
        // Action buttons container
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Edit nickname action
            ActionButton(
                icon = Icons.Filled.Edit,
                label = "Edit Nickname",
                onClick = onEditNickname
            )
            
            // Copy URL action
            ActionButton(
                icon = Icons.Filled.ContentCopy,
                label = "Copy Mint URL",
                onClick = onCopyUrl
            )
            
            // Delete mint action
            ActionButton(
                icon = Icons.Filled.Delete,
                label = "Delete Mint",
                onClick = onDeleteMint,
                isDestructive = true
            )
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val iconColor = if (isDestructive) Color(0xFFFF453A) else Color.Gray
    val textColor = if (isDestructive) Color(0xFFFF453A) else Color.White
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(20.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            text = label,
            color = textColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun SectionDivider(
    title: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Color(0xFF333333))
        )
        
        Text(
            text = title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Color(0xFF333333))
        )
    }
} 