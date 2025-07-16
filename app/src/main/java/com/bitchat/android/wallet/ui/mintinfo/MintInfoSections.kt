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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.wallet.data.ContactInfo
import com.bitchat.android.wallet.data.Mint
import java.text.SimpleDateFormat
import java.util.*

/**
 * Header section showing mint icon and name
 */
@Composable
fun MintHeaderSection(
    mint: Mint,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A).copy(alpha = 0.05f) // Very subtle background
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mint icon/avatar
            Card(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2A2A2A)
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // For now, we'll use the AccountBalance icon as default
                    // In the future, could load from mint.info?.iconUrl
                    Icon(
                        imageVector = Icons.Filled.AccountBalance,
                        contentDescription = "Mint Icon",
                        tint = Color(0xFF00C851),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Mint name from info or fallback to nickname
            Text(
                text = mint.info?.name?.takeIf { it.isNotEmpty() } ?: mint.nickname,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            
            // Show nickname separately if different from name
            if (mint.info?.name?.isNotEmpty() == true && mint.info.name != mint.nickname) {
                Text(
                    text = "\"${mint.nickname}\"",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Description section showing mint description and long description
 */
@Composable
fun MintDescriptionSection(
    mint: Mint,
    modifier: Modifier = Modifier
) {
    val info = mint.info
    if (info?.description.isNullOrEmpty() && info?.descriptionLong.isNullOrEmpty()) {
        return
    }
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Short description
        info?.description?.takeIf { it.isNotEmpty() }?.let { description ->
            Text(
                text = description,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 24.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        
        // Long description
        info?.descriptionLong?.takeIf { it.isNotEmpty() }?.let { longDescription ->
            Text(
                text = longDescription,
                color = Color.Gray,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Contact section showing mint contact information
 */
@Composable
fun MintContactSection(
    contacts: List<ContactInfo>,
    modifier: Modifier = Modifier
) {
    if (contacts.isEmpty()) return
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section divider and title
        SectionDivider(title = "CONTACT")
        
        // Contact items
        contacts.forEach { contact ->
            ContactItem(contact = contact)
        }
    }
}

/**
 * Details section showing mint technical information
 */
@Composable
fun MintDetailsSection(
    mint: Mint,
    modifier: Modifier = Modifier
) {
    var showAllNuts by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section divider and title
        SectionDivider(title = "DETAILS")
        
        // URL
        DetailItem(
            icon = Icons.Filled.Link,
            label = "URL",
            value = mint.url,
            onClick = { /* Copy URL handled by parent */ }
        )
        
        // NUTs (Cashu specifications)
        mint.info?.nuts?.let { nuts ->
            val supportedNuts = nuts.filter { (_, nutInfo) ->
                when (nutInfo) {
                    is Map<*, *> -> nutInfo["supported"] == true || nutInfo["disabled"] != true
                    else -> true
                }
            }
            
            if (supportedNuts.isNotEmpty()) {
                DetailItem(
                    icon = Icons.Filled.Extension,
                    label = "NUTs",
                    value = if (showAllNuts) "Hide" else "Show all",
                    onClick = { showAllNuts = !showAllNuts }
                )
                
                if (showAllNuts) {
                    NutsExpandedSection(nuts = supportedNuts)
                }
            }
        }
        
        // Version
        mint.info?.version?.takeIf { it.isNotEmpty() }?.let { version ->
            DetailItem(
                icon = Icons.Filled.Info,
                label = "Version",
                value = version
            )
        }
        
        // Date added
        DetailItem(
            icon = Icons.Filled.CalendarToday,
            label = "Added",
            value = formatDate(mint.dateAdded)
        )
    }
}

@Composable
private fun ContactItem(
    contact: ContactInfo,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                clipboardManager.setText(AnnotatedString(contact.info))
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Contact method icon
        val icon = when (contact.method.lowercase()) {
            "email" -> Icons.Filled.Email
            "twitter", "x" -> Icons.Filled.Share
            "nostr" -> Icons.Filled.Public
            else -> Icons.Filled.ContactMail
        }
        
        Icon(
            imageVector = icon,
            contentDescription = contact.method,
            tint = Color.Gray,
            modifier = Modifier.size(20.dp)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Text(
            text = contact.info,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1
        )
        
        Icon(
            imageVector = Icons.Filled.ContentCopy,
            contentDescription = "Copy",
            tint = Color.Gray,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun DetailItem(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.Gray,
            modifier = Modifier.size(20.dp)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Text(
            text = label,
            color = Color.Gray,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        Text(
            text = value,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.Right,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.6f)
        )
    }
}

@Composable
private fun NutsExpandedSection(
    nuts: Map<String, Any>,
    modifier: Modifier = Modifier
) {
    val nutNames = mapOf(
        "7" to "Token state check",
        "8" to "Overpaid Lightning fees",
        "9" to "Signature restore",
        "10" to "Spending conditions",
        "11" to "Pay-To-Pubkey (P2PK)",
        "12" to "DLEQ proofs",
        "13" to "Deterministic secrets",
        "14" to "Hashed Timelock Contracts",
        "15" to "Partial multi-path payments",
        "16" to "Animated QR codes",
        "17" to "WebSocket subscriptions",
        "18" to "Payment requests",
        "19" to "Cached Responses",
        "20" to "Signature on Mint Quote",
        "21" to "Clear authentication",
        "22" to "Blind authentication"
    )
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 32.dp), // Indent under the main detail item
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        nuts.filter { (nutNumber, _) -> 
            nutNumber.toIntOrNull()?.let { it >= 7 } == true // Only show NUT-7 and above
        }.forEach { (nutNumber, _) ->
            val nutName = nutNames[nutNumber] ?: "Unknown NUT"
            
            Card(
                shape = RoundedCornerShape(4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2A2A2A)
                )
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$nutNumber:",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = nutName,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
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

// Utility function
private fun formatDate(date: Date): String {
    return SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(date)
} 