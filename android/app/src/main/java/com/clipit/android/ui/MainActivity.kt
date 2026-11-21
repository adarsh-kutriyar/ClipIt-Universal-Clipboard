package com.clipit.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.clipit.android.ble.BleManager
import com.clipit.android.ble.BleProtocol
import com.clipit.android.data.BleConnectionState
import com.clipit.android.data.ClipboardRepository
import com.clipit.android.service.ClipItAccessibilityService
import com.clipit.android.service.ClipItForegroundService
import com.clipit.android.ui.theme.ClipItTheme
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ClipItMainActivity"
    }

    private val companionDeviceManager by lazy {
        getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
    }

    // Association launcher for CompanionDeviceManager
    private val associationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(
                    CompanionDeviceManager.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
            }

            device?.let { handleDeviceAssociated(it) }
        }
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Bluetooth permissions are required for ClipIt", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()
        handleIncomingShareIntent(intent)

        setContent {
            ClipItTheme {
                val connectionState by ClipboardRepository.connectionState.collectAsState()
                val pairedAddress by ClipboardRepository.pairedMacAddress.collectAsState()
                val isAccessibilityEnabled by ClipboardRepository.isAccessibilityEnabled.collectAsState()

                HomeScreen(
                    pairedMac = pairedAddress,
                    isConnected = connectionState == BleConnectionState.CONNECTED,
                    zeroTouchEnabled = isAccessibilityEnabled,
                    onSendClipboard = { pushCurrentClipboard() },
                    onScanQr = { startQrCodePairing() },
                    onAutoSearch = { startDirectBleDiscovery() },
                    onZeroTouchChange = { enabled ->
                        openAccessibilitySettings()
                    },
                    onOpenSettings = { openAppSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                BleManager.getInstance(this).sendText(sharedText)
                Toast.makeText(this, "Shared text sent to Mac", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // MARK: - QR Code Pairing (Google Code Scanner)
    private fun startQrCodePairing() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                if (!raw.isNullOrBlank()) {
                    handleQrCodeScanned(raw)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "QR Code Scanner failed or cancelled", e)
                Toast.makeText(this, "QR Scan cancelled or unavailable: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun handleQrCodeScanned(raw: String) {
        Log.d(TAG, "Scanned QR code: $raw")
        Toast.makeText(this, "QR Code recognized! Searching for Mac...", Toast.LENGTH_SHORT).show()

        var targetName = "ClipIt-Mac"
        var targetUuid: UUID? = BleProtocol.SERVICE_UUID

        try {
            if (raw.startsWith("clipit://pair")) {
                val uri = Uri.parse(raw)
                targetName = uri.getQueryParameter("name") ?: targetName
                val uuidStr = uri.getQueryParameter("uuid")
                if (!uuidStr.isNullOrBlank()) {
                    targetUuid = UUID.fromString(uuidStr)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse URI query params, falling back to defaults", e)
        }

        startDirectBleDiscovery(targetName, targetUuid)
    }

    // MARK: - Direct In-App BLE Discovery
    private fun startDirectBleDiscovery(
        targetName: String = "ClipIt-Mac",
        targetUuid: UUID? = BleProtocol.SERVICE_UUID
    ) {
        Toast.makeText(this, "Scanning for $targetName over BLE...", Toast.LENGTH_SHORT).show()

        BleManager.getInstance(this).startScanningForMac(
            targetName = targetName,
            targetUuid = targetUuid,
            onFound = { device ->
                runOnUiThread {
                    Toast.makeText(this, "Found ${device.name ?: "Mac"}! Connecting...", Toast.LENGTH_SHORT).show()
                    handleDeviceAssociated(device)
                }
            },
            onFailed = { reason ->
                runOnUiThread {
                    Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // MARK: - Companion Device Manager Flow
    @SuppressLint("MissingPermission")
    private fun startCompanionDeviceAssociation() {
        Log.d(TAG, "Starting Companion Device Association...")

        // Open filter so the Android dialog shows all nearby BLE devices without dropping packets
        val deviceFilter = BluetoothLeDeviceFilter.Builder().build()

        val pairingRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            companionDeviceManager.associate(
                pairingRequest,
                mainExecutor,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) {
                        associationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onDeviceFound(chooserLauncher: IntentSender) {
                        associationLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                    }

                    override fun onFailure(error: CharSequence?) {
                        Log.e(TAG, "Companion Association failed: $error")
                        Toast.makeText(this@MainActivity, "Association failed: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            @Suppress("DEPRECATION")
            companionDeviceManager.associate(
                pairingRequest,
                object : CompanionDeviceManager.Callback() {
                    override fun onDeviceFound(chooserLauncher: IntentSender) {
                        associationLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                    }

                    override fun onFailure(error: CharSequence?) {
                        Log.e(TAG, "Companion Association failed: $error")
                        Toast.makeText(this@MainActivity, "Association failed: $error", Toast.LENGTH_SHORT).show()
                    }
                },
                null
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceAssociated(device: BluetoothDevice) {
        val address = device.address
        Log.d(TAG, "Device associated: ${device.name} ($address)")
        Toast.makeText(this, "Paired with ${device.name ?: "Mac"}! 🟢", Toast.LENGTH_SHORT).show()

        ClipboardRepository.setPairedMacAddress(address, this)

        // Register for device presence observation (wakes app on BLE presence)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                companionDeviceManager.startObservingDevicePresence(address)
                Log.d(TAG, "startObservingDevicePresence registered for $address")
            }
        } catch (e: Exception) {
            Log.e(TAG, "startObservingDevicePresence notice", e)
        }

        // Start Foreground Service & Connect BLE
        val serviceIntent = Intent(this, ClipItForegroundService::class.java).apply {
            putExtra(ClipItForegroundService.EXTRA_DEVICE_ADDRESS, address)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        BleManager.getInstance(this).connectToDevice(address)
    }

    private fun checkAccessibilityStatus() {
        val expectedServiceName = "${packageName}/${ClipItAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val isEnabled = enabledServices.split(':').any {
            it.equals(expectedServiceName, ignoreCase = true) ||
            it.contains(ClipItAccessibilityService::class.java.simpleName)
        }

        ClipboardRepository.setAccessibilityEnabled(isEnabled)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Battery optimization already disabled!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendTestText() {
        val testMessage = "Hello from Android at ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} 📱"
        BleManager.getInstance(this).sendText(testMessage)
        Toast.makeText(this, "Sent test text to Mac!", Toast.LENGTH_SHORT).show()
    }

    private fun pushCurrentClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.isNotBlank()) {
                BleManager.getInstance(this).sendText(text)
                Toast.makeText(this, "Pushed current clipboard to Mac!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
        }
    }
}
