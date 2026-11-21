package com.clipit.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.clipit.android.ClipItApplication
import com.clipit.android.R
import com.clipit.android.ble.BleManager
import com.clipit.android.data.BleConnectionState
import com.clipit.android.data.ClipboardRepository
import com.clipit.android.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class ClipItForegroundService : Service() {

    companion object {
        private const val TAG = "ClipItForeground"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val ACTION_MANUAL_SEND_CLIPBOARD = "com.clipit.android.ACTION_MANUAL_SEND_CLIPBOARD"
        const val ACTION_RECONNECT = "com.clipit.android.ACTION_RECONNECT"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                    android.bluetooth.BluetoothAdapter.ERROR
                )
                if (state == android.bluetooth.BluetoothAdapter.STATE_ON) {
                    Log.d(TAG, "Bluetooth turned back ON. Reconnecting to Mac...")
                    BleManager.getInstance(this@ClipItForegroundService).triggerAutoReconnect()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ClipItForegroundService created.")

        startForegroundWithNotification("Initializing...")

        // Register receiver for phone Bluetooth toggle events
        val filter = android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, filter)

        // Observe connection state and update persistent notification
        scope.launch {
            ClipboardRepository.connectionState.collectLatest { state ->
                val statusText = when (state) {
                    BleConnectionState.CONNECTED -> "Connected to Mac "
                    BleConnectionState.CONNECTING -> "Connecting to Mac "
                    BleConnectionState.SCANNING -> "Searching for Mac "
                    BleConnectionState.DISCONNECTED -> "Mac Disconnected (Auto-reconnecting)"
                }
                updateNotification(statusText)
            }
        }

        // Immediately start auto-reconnection if a Mac was previously paired
        ClipboardRepository.pairedMacAddress.value?.let {
            BleManager.getInstance(this).triggerAutoReconnect()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)

        if (address != null) {
            BleManager.getInstance(this).connectToDevice(address)
        }

        when (action) {
            ACTION_MANUAL_SEND_CLIPBOARD -> {
                handleManualSendClipboard()
            }
            ACTION_RECONNECT -> {
                ClipboardRepository.pairedMacAddress.value?.let { addr ->
                    BleManager.getInstance(this).connectToDevice(addr)
                }
            }
        }

        return START_STICKY
    }

    private fun handleManualSendClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                if (text.isNotBlank()) {
                    Log.d(TAG, "Manually sending clipboard to Mac via Notification Action...")
                    BleManager.getInstance(this).sendText(text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read clipboard on manual push", e)
        }
    }

    private fun startForegroundWithNotification(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(statusText: String) {
        val notification = buildNotification(statusText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sendClipIntent = Intent(this, com.clipit.android.ui.ClipboardSyncActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        val sendClipPendingIntent = PendingIntent.getActivity(
            this, 1, sendClipIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, ClipItApplication.CHANNEL_ID_SERVICE)
            .setContentTitle("ClipIt Universal Clipboard")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_clipboard)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_clipboard, "Send Clipboard", sendClipPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "App task removed from Recents. ClipItForegroundService continues running in background.")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            // ignore if not registered
        }
        scope.cancel()
        Log.d(TAG, "ClipItForegroundService destroyed.")
    }
}
