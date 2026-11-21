package com.clipit.android.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.clipit.android.ble.BleManager
import com.clipit.android.data.ClipboardRepository
import com.clipit.android.ui.ClipboardSyncActivity

/**
 * Accessibility Service that catches copy events across the entire Android OS.
 *
 * Under Android 10+ background privacy rules, background services are blocked from calling
 * `clipboardManager.getPrimaryClip()`.
 *
 * How ClipIt solves this when the app is NOT in recent apps / foreground:
 * 1. The AccessibilityService detects when the user taps "Copy", "Cut", or contextual menu items.
 * 2. It immediately launches `ClipboardSyncActivity` (a 0-millisecond invisible, transparent floating window).
 * 3. As soon as `ClipboardSyncActivity` gains window focus for ~20ms, Android grants full permission
 *    to read `primaryClip`.
 * 4. It transmits the copied text to your Mac over BLE and instantly finishes without the user noticing.
 */
class ClipItAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipItAccessibility"
    }

    private var clipboardManager: ClipboardManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastSelectedText: String? = null
    private var lastCaptureTime: Long = 0

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        Log.d(TAG, "OnPrimaryClipChanged triggered in AccessibilityService")
        scheduleClipboardCapture(source = "OnPrimaryClipChanged", delayMs = 50)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "ClipItAccessibilityService connected.")
        ClipboardRepository.setAccessibilityEnabled(true)

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Track selected text in case copy is performed
                val text = event.text.joinToString("")
                if (text.isNotBlank()) {
                    lastSelectedText = text
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> {
                if (isCopyOrCutInteraction(event)) {
                    Log.d(TAG, "Detected user tapped Copy/Cut interaction!")
                    scheduleClipboardCapture(source = "CopyClick", delayMs = 120)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // If a popup toolbar closed or clipboard toast appeared, check clipboard
                val className = event.className?.toString() ?: ""
                if (className.contains("FloatingToolbar", ignoreCase = true) ||
                    className.contains("Clipboard", ignoreCase = true)
                ) {
                    scheduleClipboardCapture(source = "ToolbarDismiss", delayMs = 100)
                }
            }
        }
    }

    private fun isCopyOrCutInteraction(event: AccessibilityEvent): Boolean {
        val eventText = event.text.joinToString(" ")
        val desc = event.contentDescription?.toString() ?: ""
        val viewId = event.source?.viewIdResourceName ?: ""

        val keywords = listOf("copy", "cut", "copiar", "kopieren", "copie")

        if (keywords.any { eventText.contains(it, ignoreCase = true) }) return true
        if (keywords.any { desc.contains(it, ignoreCase = true) }) return true
        if (keywords.any { viewId.contains(it, ignoreCase = true) }) return true

        // Check node text in case event was on a child
        val node = event.source
        if (node != null) {
            val nodeText = node.text?.toString() ?: ""
            val nodeDesc = node.contentDescription?.toString() ?: ""
            if (keywords.any { nodeText.contains(it, ignoreCase = true) }) return true
            if (keywords.any { nodeDesc.contains(it, ignoreCase = true) }) return true
        }

        return false
    }

    private fun scheduleClipboardCapture(source: String, delayMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < 400) {
            // Debounce rapid repeated triggers
            return
        }
        lastCaptureTime = now

        handler.postDelayed({
            launchInvisibleClipboardActivity(source)
        }, delayMs)
    }

    private fun launchInvisibleClipboardActivity(source: String) {
        Log.d(TAG, "Triggering invisible ClipboardSyncActivity from $source...")
        val intent = Intent(this, ClipboardSyncActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting ClipboardSyncActivity", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ClipItAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ClipItAccessibilityService destroyed.")
        ClipboardRepository.setAccessibilityEnabled(false)
        clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
    }
}
