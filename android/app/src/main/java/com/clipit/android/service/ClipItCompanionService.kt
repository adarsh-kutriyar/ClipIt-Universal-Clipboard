package com.clipit.android.service

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.clipit.android.ble.BleManager
import com.clipit.android.data.ClipboardRepository

@SuppressLint("NewApi")
class ClipItCompanionService : CompanionDeviceService() {

    companion object {
        private const val TAG = "ClipItCompanion"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ClipItCompanionService created.")
    }

    // Android 14+ (API 34+)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val macAddress = associationInfo.deviceMacAddress?.toString() ?: return
        Log.d(TAG, "onDeviceAppeared (API 34+): Device $macAddress is in range!")
        handleDeviceAppeared(macAddress)
    }

    // Android 12-33 (API 31-33)
    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(address: String) {
        @Suppress("DEPRECATION")
        super.onDeviceAppeared(address)
        Log.d(TAG, "onDeviceAppeared (Legacy): Device $address is in range!")
        handleDeviceAppeared(address)
    }

    private fun handleDeviceAppeared(address: String) {
        ClipboardRepository.setPairedMacAddress(address, this)

        // Ensure foreground service is running for reliable BLE persistence
        val intent = Intent(this, ClipItForegroundService::class.java).apply {
            putExtra(ClipItForegroundService.EXTRA_DEVICE_ADDRESS, address)
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service from companion callback", e)
        }

        // Connect BLE GATT to Mac
        BleManager.getInstance(this).connectToDevice(address)
    }

    // Android 14+ (API 34+)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        val macAddress = associationInfo.deviceMacAddress?.toString() ?: return
        Log.d(TAG, "onDeviceDisappeared (API 34+): Device $macAddress went out of range.")
        handleDeviceDisappeared(macAddress)
    }

    // Android 12-33 (API 31-33)
    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(address: String) {
        @Suppress("DEPRECATION")
        super.onDeviceDisappeared(address)
        Log.d(TAG, "onDeviceDisappeared (Legacy): Device $address went out of range.")
        handleDeviceDisappeared(address)
    }

    private fun handleDeviceDisappeared(address: String) {
        Log.d(TAG, "Companion device $address disappeared.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ClipItCompanionService destroyed.")
    }
}
