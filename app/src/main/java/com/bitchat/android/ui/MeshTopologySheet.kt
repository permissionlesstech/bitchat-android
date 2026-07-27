package com.bitchat.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.ui.debug.MeshTopologySection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshTopologySheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    meshService: MeshService,
) {
    if (!isPresented) return
    BitchatBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("mesh topology", fontFamily = FontFamily.Monospace)
            MeshTopologySection(
                localPeerID = meshService.myPeerID,
                blePeerIDs = meshService.getDirectBlePeerIDs(),
            )
        }
    }
}
