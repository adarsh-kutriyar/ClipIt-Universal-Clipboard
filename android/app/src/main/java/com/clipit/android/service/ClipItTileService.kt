package com.clipit.android.service

import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.clipit.android.ble.BleManager
import com.clipit.android.data.BleConnectionState
import com.clipit.android.data.ClipboardRepository

@RequiresApi(Build.VERSION_CODES.N)
class ClipItTileService : TileService() {

    companion object {
        private const val TAG = "ClipItTileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isConnected = ClipboardRepository.connectionState.value == BleConnectionState.CONNECTED
        if (!isConnected) {
            Toast.makeText(this, "ClipIt: Mac is not connected", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val syncIntent = Intent(this, com.clipit.android.ui.ClipboardSyncActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this, 0, syncIntent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(syncIntent)
            }

            // Momentarily highlight tile
            qsTile?.state = Tile.STATE_ACTIVE
            qsTile?.updateTile()
            Handler(Looper.getMainLooper()).postDelayed({
                updateTileState()
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Tile onClick clipboard error", e)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isConnected = ClipboardRepository.connectionState.value == BleConnectionState.CONNECTED
        tile.state = if (isConnected) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.label = if (isConnected) "Push Clipboard" else "ClipIt (Offline)"
        tile.updateTile()
    }
}
