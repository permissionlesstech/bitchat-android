package com.bitchat.android.ui

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.hotspot.HotspotActivity
import com.bitchat.android.share.ShareAppSection
import javax.inject.Inject

/**
 * The root of the APK-sharing rows in About's Settings card: it gets the ViewModel, re-checks
 * the local APK each time the section is shown, and turns the ViewModel's events into
 * activities and toasts. [ShareAppSectionContent] draws the rows.
 */
internal class ShareAppSectionImpl @Inject constructor() : ShareAppSection {

    @Composable
    override fun Content(modifier: Modifier) {
        val context = LocalContext.current
        // Deliberately Activity-scoped, not entry-scoped. Under a
        // route the entry decorator would clear this on pop and kill
        // an in-flight download. Do not "simplify" this back to a
        // bare hiltViewModel().
        val activity = LocalActivity.current as ComponentActivity
        val apkViewModel: ApkDownloadViewModel = hiltViewModel(activity)
        val uiState by apkViewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            apkViewModel.onAction(ApkAction.SectionShown)
        }

        // Handle one-shot events (navigation, toasts, share intents)
        ObserveAsEvents(apkViewModel.events) { event ->
            when (event) {
                is ApkEvent.NavigateToHotspot -> {
                    val intent = Intent(context, HotspotActivity::class.java)
                    intent.putExtra(HotspotActivity.EXTRA_APK_PATH, event.apkPath)
                    context.startActivity(intent)
                }
                is ApkEvent.ShareApk -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_STREAM, event.apkUri)
                        clipData = ClipData.newRawUri("", event.apkUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(intent, event.chooserTitle).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(chooser)
                }
                is ApkEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        ShareAppSectionContent(
            uiState = uiState,
            onAction = apkViewModel::onAction,
            modifier = modifier
        )
    }
}
