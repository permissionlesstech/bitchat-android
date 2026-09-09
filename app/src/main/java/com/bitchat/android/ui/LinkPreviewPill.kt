package com.bitchat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import java.net.URL

/**
 * LinkPreviewPill - A compact link preview component that matches the iOS design
 * 
 * This component replicates the iOS link preview pill functionality:
 * - Shows a clickable pill with link icon, title, and host
 * - No preview image fetching - just styled link display
 * - Opens link in external browser when clicked
 * - Uses iOS-style green colors and monospaced fonts
 */

@Composable
fun LinkPreviewPill(
    url: String,
    title: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val textColor = colorScheme.secondary
    val backgroundColor = colorScheme.secondaryContainer.copy(alpha = 0.35f)
    val borderColor = textColor.copy(alpha = 0.3f)
    
    // Parse URL for host extraction
    val parsedUrl = try {
        URL(url)
    } catch (e: Exception) {
        null
    }
    
    val displayTitle = title ?: parsedUrl?.host ?: "Link"
    val displayHost = parsedUrl?.host ?: url
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Silently fail if URL can't be opened
                }
            },
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Link icon (matches iOS placeholder)
            Surface(
                modifier = Modifier.size(60.dp),
                shape = RoundedCornerShape(8.dp),
                color = colorScheme.secondaryContainer
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Link,
                        contentDescription = stringResource(com.bitchat.android.R.string.cd_link),
                        modifier = Modifier.size(24.dp),
                        tint = colorScheme.secondary
                    )
                }
            }
            
            // Text content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Title - matches iOS styling
                Text(
                    text = displayTitle,
                    fontFamily = BitchatFontFamily,
                    fontSize = BASE_FONT_SIZE.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                // Host - matches iOS styling
                Text(
                    text = displayHost,
                    fontFamily = BitchatFontFamily,
                    fontSize = 11.sp,
                    color = textColor.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * URL detection utility that extracts URLs from text content
 * Matches the iOS extractURLs() extension functionality
 */
object URLDetector {
    
    // Regex pattern for URL detection (matches common URL patterns)
    private val urlRegex = Regex(
        """(?i)\b(?:https?://|www\d{0,3}[.]|[a-z0-9.\-]+[.][a-z]{2,4}/)(?:[^\s()<>]+|\([^\s()<>]+\))+(?:\([^\s()<>]+\)|[^\s`!()\[\]{};:'".,<>?«»""''])""",
        RegexOption.IGNORE_CASE
    )
    
    data class UrlMatch(
        val url: String,
        val range: IntRange
    )
    
    /**
     * Extract URLs from text content
     * Returns list of URL matches with their positions in the text
     */
    fun extractUrls(text: String): List<UrlMatch> {
        val urls = mutableListOf<UrlMatch>()
        val matches = urlRegex.findAll(text)
        
        for (match in matches) {
            var url = match.value
            val start = match.range.first
            val end = match.range.last
            
            // Add protocol if missing
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            
            urls.add(UrlMatch(url, start..end))
        }
        
        return urls
    }
    
    /**
     * Check if a string contains any URLs
     */
    fun containsUrls(text: String): Boolean {
        return urlRegex.containsMatchIn(text)
    }
}
