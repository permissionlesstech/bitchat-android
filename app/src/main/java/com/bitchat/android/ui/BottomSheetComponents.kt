package com.bitchat.android.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.FrontHand
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R

data class InfoRow(
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val description: Int
)

private val featuresData = listOf(
    InfoRow(Icons.Default.WifiOff, R.string.feature_offline_title, R.string.feature_offline_desc),
    InfoRow(
        Icons.Default.Lock,
        R.string.feature_encryption_title,
        R.string.feature_encryption_desc
    ),
    InfoRow(
        Icons.Default.SettingsInputAntenna,
        R.string.feature_range_title,
        R.string.feature_range_desc
    ),
    InfoRow(Icons.Default.Star, R.string.feature_favorites_title, R.string.feature_favorites_desc),
    InfoRow(
        Icons.Default.Public,
        R.string.feature_mutual_favorites_title,
        R.string.feature_mutual_favorites_desc
    ),
    InfoRow(
        Icons.Default.AlternateEmail,
        R.string.feature_mentions_title,
        R.string.feature_mentions_desc
    )
)

// "PRIVACY" list
private val privacyData = listOf(
    InfoRow(
        Icons.Default.VisibilityOff,
        R.string.privacy_no_tracking_title,
        R.string.privacy_no_tracking_desc
    ),
    InfoRow(
        Icons.Default.Shuffle,
        R.string.privacy_ephemeral_title,
        R.string.privacy_ephemeral_desc
    ),
    InfoRow(Icons.Default.FrontHand, R.string.privacy_panic_title, R.string.privacy_panic_desc)
)

// "HOW TO USE" list
private val howToUseData = listOf(
    R.string.how_to_use_1,
    R.string.how_to_use_2,
    R.string.how_to_use_3,
    R.string.how_to_use_4,
    R.string.how_to_use_5
)

/**
 * BottomSheet components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoBottomSheet(
    show: Boolean,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (show) {
        ModalBottomSheet(
            modifier = Modifier.Companion.statusBarsPadding(),
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            dragHandle = null
        ) {
            AppInfoContent(onDismiss = onDismiss)
        }
    }
}

@Composable
private fun AppInfoContent(onDismiss: () -> Unit) {

    val lazyListState = rememberLazyListState()

    val isScrolled by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0
        }
    }

    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.85f else 0f,
        label = "topBarAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = 80.dp, bottom = 20.dp)
        ) {

            item(key = "header") {
                ScreenHeader(modifier = Modifier.padding(horizontal = 24.dp))
            }

            //  Features
            item(key = "features_header") {
                SectionHeader(titleRes = R.string.features_title)
            }
            items(featuresData, key = { it.title }) { rowData ->
                FeatureRow(
                    data = rowData,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            //  Privacy
            item(key = "privacy_header") {
                SectionHeader(titleRes = R.string.privacy_title)
            }

            items(privacyData, key = { it.title }) { rowData ->
                FeatureRow(
                    data = rowData,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            //  How To Use
            item(key = "how_to_use_header") {
                SectionHeader(titleRes = R.string.how_to_use_title)
            }

            item(key = "how_to_use_list") {
                HowToUseSection(
                    modifier = Modifier.padding(horizontal = 24.dp + 16.dp)
                )
            }

            //  Warning
            item(key = "warning_section") {
                WarningSection(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
                )
            }
        }

        TopBar(
            alpha = topBarAlpha,
            onDismiss = onDismiss,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun ScreenHeader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 38.sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_info_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun SectionHeader(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes).uppercase(),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun FeatureRow(data: InfoRow, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier.padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = data.icon,
            contentDescription = stringResource(data.title),
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = stringResource(data.title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(data.description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun HowToUseSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        howToUseData.forEach { stringRes ->
            Text(
                text = stringResource(stringRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun WarningSection(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                colorScheme.error.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = stringResource(R.string.warning_title).uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.warning_message),
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.error
        )
    }
}

@Composable
private fun TopBar(
    alpha: Float,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme


    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(colorScheme.background.copy(alpha = alpha))
    ) {
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.done).uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = colorScheme.onBackground
            )
        }
    }
}