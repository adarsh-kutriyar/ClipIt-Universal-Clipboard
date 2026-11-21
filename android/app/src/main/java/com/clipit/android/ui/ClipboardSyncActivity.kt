package com.clipit.android.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.clipit.android.ble.BleManager
import com.clipit.android.data.ClipboardRepository

/**
 * Invisible, transient activity that gains window focus for ~20ms to read the system
 * clipboard legally under Android 10+ background privacy rules, transmits text to Mac,
 * and immediately closes.
 */
class ClipboardSyncActivity : Activity() {

    companion object {
        private const val TAG = "ClipboardSyncActivity"
    }

    private var hasExecuted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasExecuted) {
            hasExecuted = true
            readAndSyncClipboard()
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun readAndSyncClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val text = item.text?.toString() ?: item.coerceToText(this)?.toString()
                if (!text.isNullOrBlank()) {
                    if (ClipboardRepository.shouldSendToMac(text)) {
                        Log.d(TAG, "Successfully read clipboard via window focus: ${text.length} chars. Pushing to Mac...")
                        BleManager.getInstance(this).sendText(text)
                        android.widget.Toast.makeText(this, "Synced to Mac! 🚀", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        Log.d(TAG, "Clipboard text already in sync or echo, skipping")
                        android.widget.Toast.makeText(this, "Already in sync with Mac", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading clipboard in ClipboardSyncActivity", e)
        }
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
    }
}
