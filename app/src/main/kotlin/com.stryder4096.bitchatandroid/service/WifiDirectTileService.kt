package com.stryder4096.bitchatandroid 
 
import android.service.quicksettings.Tile 
import android.service.quicksettings.TileService 
 
class WifiDirectTileService : TileService() { 
    private var isActive = false 
 
    override fun onClick() { 
        super.onClick() 
        isActive = isActive 
        qsTile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE 
        qsTile.updateTile() 
        // Toggle Wi-Fi Direct in HybridMeshManager 
        if (isActive) { 
            // Enable via manager 
        } else { 
            // Disable 
        } 
    } 
 
    override fun onStartListening() { 
        super.onStartListening() 
        qsTile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE 
        qsTile.updateTile() 
    } 
} 
