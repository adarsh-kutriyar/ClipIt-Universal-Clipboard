package com.clipit.android.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

object ClipboardRepository {

    private const val PREFS_NAME = "clipit_prefs"
    private const val KEY_PAIRED_MAC = "paired_mac_address"

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _pairedMacAddress = MutableStateFlow<String?>(null)
    val pairedMacAddress: StateFlow<String?> = _pairedMacAddress.asStateFlow()

    private val _lastSyncedText = MutableStateFlow<String?>(null)
    val lastSyncedText: StateFlow<String?> = _lastSyncedText.asStateFlow()

    private val _lastSyncDirection = MutableStateFlow<String?>(null)
    val lastSyncDirection: StateFlow<String?> = _lastSyncDirection.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow<Long>(0L)
    val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    // Echo prevention hashes
    @Volatile
    private var lastReceivedHash: String = ""
    @Volatile
    private var lastSentHash: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_PAIRED_MAC, null)
        _pairedMacAddress.value = saved
    }

    fun updateConnectionState(state: BleConnectionState) {
        _connectionState.value = state
    }

    fun setPairedMacAddress(address: String?, context: Context? = null) {
        _pairedMacAddress.value = address
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_PAIRED_MAC, address).apply()
        }
    }

    fun setAccessibilityEnabled(enabled: Boolean) {
        _isAccessibilityEnabled.value = enabled
    }

    /**
     * Called when text is received from Mac over BLE.
     * Sets Android clipboard without triggering an outgoing echo loop.
     */
    fun setReceivedFromMac(text: String, context: Context) {
        val hash = sha256(text)
        lastReceivedHash = hash

        _lastSyncedText.value = text
        _lastSyncDirection.value = "Received from Mac"
        _lastSyncTimestamp.value = System.currentTimeMillis()

        // ClipboardManager must be touched on Main Thread
        mainHandler.post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ClipIt", text)
                clipboard.setPrimaryClip(clip)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Checks whether newly detected text should be sent to Mac.
     * Returns true only if it is non-empty and not an echo of what was just received or sent.
     */
    fun shouldSendToMac(text: String): Boolean {
        if (text.isBlank()) return false
        val hash = sha256(text)
        if (hash == lastReceivedHash) return false
        if (hash == lastSentHash) return false
        return true
    }

    /**
     * Marks text as sent to prevent re-sending.
     */
    fun markSentToMac(text: String) {
        lastSentHash = sha256(text)
        _lastSyncedText.value = text
        _lastSyncDirection.value = "Sent to Mac"
        _lastSyncTimestamp.value = System.currentTimeMillis()
    }

    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
