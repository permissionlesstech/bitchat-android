package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette

internal val PeerAvatarBadgeSize = 18.dp
private val PeerAvatarStarSize = 16.dp
private val PeerAvatarVerifiedSize = 16.dp

@Composable
internal fun PeerAvatar(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    theyFavoritedUs: Boolean = false,
    isVerified: Boolean = false,
    badge: (@Composable () -> Unit)? = null
) {
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier.size(42.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(color.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.trim().firstOrNull()?.uppercase() ?: "#",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = BitchatFontFamily,
                    fontWeight = FontWeight.SemiBold
                ),
                color = color
            )
        }

        if (badge != null) {
            Surface(
                modifier = Modifier
                    .size(PeerAvatarBadgeSize)
                    .align(Alignment.BottomEnd),
                shape = CircleShape,
                color = colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    badge()
                }
            }
        }

        if (isFavorite || theyFavoritedUs) {
            Surface(
                modifier = Modifier
                    .size(PeerAvatarStarSize)
                    .align(Alignment.TopEnd),
                shape = CircleShape,
                color = colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(
                            if (isFavorite) {
                                R.drawable.ic_spec_star_filled
                            } else {
                                R.drawable.ic_spec_star
                            }
                        ),
                        contentDescription = stringResource(
                            if (isFavorite) {
                                R.string.cd_favorite
                            } else {
                                R.string.cd_favorited_you
                            }
                        ),
                        modifier = Modifier.size(10.dp),
                        tint = palette.accentOrange
                    )
                }
            }
        }

        if (isVerified) {
            Surface(
                modifier = Modifier
                    .size(PeerAvatarVerifiedSize)
                    .align(Alignment.TopStart),
                shape = CircleShape,
                color = colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Verified,
                        contentDescription = stringResource(
                            R.string.fingerprint_verified_label
                        ),
                        modifier = Modifier.size(12.dp),
                        tint = colorScheme.primary
                    )
                }
            }
        }
    }
}
