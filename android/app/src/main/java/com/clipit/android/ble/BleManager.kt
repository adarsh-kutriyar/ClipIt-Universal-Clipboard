package com.clipit.android.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.clipit.android.data.BleConnectionState
import com.clipit.android.data.ClipboardRepository
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "ClipItBleManager"

        @Volatile
        private var instance: BleManager? = null

        fun getInstance(context: Context): BleManager {
            return instance ?: synchronized(this) {
                instance ?: BleManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var clipboardCharacteristic: BluetoothGattCharacteristic? = null

    private val reassembler = BleProtocol.Reassembler()
    private var currentMessageId: Byte = 0

    private val outgoingQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isWriting = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var targetDeviceAddress: String? = null

    private var isScanning = false
    private var scanCallback: ScanCallback? = null

    private var autoReconnectJob: Job? = null
    private var isAutoReconnectActive = false

    init {
        // Restore paired Mac address from persistent repository
        targetDeviceAddress = ClipboardRepository.pairedMacAddress.value
    }

    fun triggerAutoReconnect() {
        val addr = targetDeviceAddress ?: ClipboardRepository.pairedMacAddress.value
        if (addr != null && ClipboardRepository.connectionState.value == BleConnectionState.DISCONNECTED) {
            Log.d(TAG, "Triggering immediate auto-reconnect to $addr...")
            startAutoReconnectLoop()
        }
    }

    fun startAutoReconnectLoop() {
        if (isAutoReconnectActive) return
        val addr = targetDeviceAddress ?: ClipboardRepository.pairedMacAddress.value ?: return

        isAutoReconnectActive = true
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            Log.d(TAG, "Auto-reconnect engine started for $addr...")
            var attempt = 0

            while (isActive && isAutoReconnectActive) {
                if (ClipboardRepository.connectionState.value == BleConnectionState.CONNECTED) {
                    Log.d(TAG, "Connected! Pausing auto-reconnect engine.")
                    isAutoReconnectActive = false
                    break
                }

                val adapter = bluetoothAdapter
                if (adapter == null || !adapter.isEnabled) {
                    Log.d(TAG, "Bluetooth is disabled on phone, waiting for BT to be turned on...")
                    delay(3000)
                    continue
                }

                attempt++
                Log.d(TAG, "Auto-reconnect cycle #$attempt searching for Mac...")

                var foundMac = false
                startScanningForMac(
                    targetName = "ClipIt-Mac",
                    targetUuid = BleProtocol.SERVICE_UUID,
                    onFound = { device ->
                        foundMac = true
                        Log.d(TAG, "Mac detected in range! Connecting immediately...")
                        connectToDevice(device.address)
                    },
                    onFailed = {
                        // Scan finished or timeout
                    }
                )

                // Give scan 4 seconds to detect Mac advertising packet
                delay(4000)
                stopScanning()

                if (ClipboardRepository.connectionState.value == BleConnectionState.CONNECTED) {
                    isAutoReconnectActive = false
                    break
                }

                // If scan didn't trigger, also attempt direct connectGatt
                if (!foundMac && ClipboardRepository.connectionState.value == BleConnectionState.DISCONNECTED) {
                    try {
                        Log.d(TAG, "Attempting direct GATT probe to $addr...")
                        connectToDevice(addr)
                    } catch (e: Exception) {
                        Log.e(TAG, "Direct connect probe error", e)
                    }
                }

                // Wait 5 seconds before next detection cycle (battery friendly)
                delay(5000)
            }
        }
    }

    fun stopAutoReconnectLoop() {
        isAutoReconnectActive = false
        autoReconnectJob?.cancel()
        autoReconnectJob = null
    }

    fun startScanningForMac(
        targetName: String? = null,
        targetUuid: UUID? = null,
        onFound: (BluetoothDevice) -> Unit,
        onFailed: (String) -> Unit
    ) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            onFailed("Bluetooth is turned off or BLE is unavailable")
            return
        }

        stopScanning()
        isScanning = true
        ClipboardRepository.updateConnectionState(BleConnectionState.SCANNING)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val devName = device.name ?: result.scanRecord?.deviceName ?: ""
                val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()

                val matchesName = devName.contains("ClipIt", ignoreCase = true) ||
                        (!targetName.isNullOrBlank() && devName.contains(targetName, ignoreCase = true))
                val matchesUuid = serviceUuids.contains(BleProtocol.SERVICE_UUID) ||
                        (targetUuid != null && serviceUuids.contains(targetUuid))

                if (matchesName || matchesUuid) {
                    Log.d(TAG, "Discovered ClipIt Mac: $devName (${device.address})")
                    stopScanning()
                    onFound(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE Scan failed with code: $errorCode")
                stopScanning()
                onFailed("Scan error ($errorCode)")
            }
        }

        try {
            scanner.startScan(emptyList(), settings, scanCallback)
            Log.d(TAG, "Started BLE scan for Mac...")

            scope.launch {
                delay(12000)
                if (isScanning) {
                    stopScanning()
                    onFailed("Scan timed out. Make sure Mac menu bar app is running.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            stopScanning()
            onFailed(e.localizedMessage ?: "Scan exception")
        }
    }

    fun stopScanning() {
        if (!isScanning) return
        isScanning = false
        try {
            scanCallback?.let { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scan", e)
        }
        scanCallback = null
        if (ClipboardRepository.connectionState.value == BleConnectionState.SCANNING) {
            ClipboardRepository.updateConnectionState(BleConnectionState.DISCONNECTED)
        }
    }

    fun connectToDevice(deviceAddress: String) {
        targetDeviceAddress = deviceAddress
        ClipboardRepository.setPairedMacAddress(deviceAddress, context)

        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "BluetoothAdapter is null")
            return
        }

        try {
            val device = adapter.getRemoteDevice(deviceAddress)
            disconnect()

            ClipboardRepository.updateConnectionState(BleConnectionState.CONNECTING)
            Log.d(TAG, "Initiating GATT connection to ${device.name} ($deviceAddress)...")

            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to device $deviceAddress", e)
            ClipboardRepository.updateConnectionState(BleConnectionState.DISCONNECTED)
        }
    }

    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            clipboardCharacteristic = null
            outgoingQueue.clear()
            isWriting = false
            ClipboardRepository.updateConnectionState(BleConnectionState.DISCONNECTED)
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    fun sendText(text: String) {
        if (!ClipboardRepository.shouldSendToMac(text)) {
            Log.d(TAG, "Skipping send: Text is empty or already in sync")
            return
        }

        val gatt = bluetoothGatt
        val char = clipboardCharacteristic
        if (gatt == null || char == null) {
            Log.w(TAG, "Cannot send text: GATT or characteristic not connected")
            return
        }

        currentMessageId = (currentMessageId + 1).toByte()
        val packets = BleProtocol.chunk(text, currentMessageId, maxPacketSize = 180)

        Log.d(TAG, "Queueing ${packets.size} packets for text (${text.length} chars) to Mac...")
        outgoingQueue.addAll(packets)
        ClipboardRepository.markSentToMac(text)

        processNextWrite()
    }

    @Synchronized
    private fun processNextWrite() {
        if (isWriting) return
        val nextPacket = outgoingQueue.poll() ?: return

        val gatt = bluetoothGatt ?: return
        val char = clipboardCharacteristic ?: return

        isWriting = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                char,
                nextPacket,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            char.value = nextPacket
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(char)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                ClipboardRepository.updateConnectionState(BleConnectionState.CONNECTING)
                // Request maximum MTU for fast transfer
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ClipboardRepository.updateConnectionState(BleConnectionState.DISCONNECTED)
                clipboardCharacteristic = null
                isWriting = false
                outgoingQueue.clear()

                Log.d(TAG, "GATT disconnected. Triggering continuous auto-reconnection...")
                startAutoReconnectLoop()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU negotiated: $mtu (status=$status). Discovering services...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Services discovery failed with status $status")
                return
            }

            val service = gatt.getService(BleProtocol.SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "ClipIt Service not found on Mac GATT server!")
                return
            }

            val characteristic = service.getCharacteristic(BleProtocol.CLIPBOARD_CHAR_UUID)
            if (characteristic == null) {
                Log.e(TAG, "ClipIt Characteristic not found!")
                return
            }

            clipboardCharacteristic = characteristic

            // Enable local notifications
            gatt.setCharacteristicNotification(characteristic, true)

            // Write CCCD descriptor to subscribe to notifications on Mac
            val cccd = characteristic.getDescriptor(BleProtocol.CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }

            stopAutoReconnectLoop()
            ClipboardRepository.updateConnectionState(BleConnectionState.CONNECTED)
            Log.d(TAG, "Successfully connected and subscribed to ClipIt Mac!")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            isWriting = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Characteristic write failed: status=$status")
            }
            processNextWrite()
        }

        // Android 13+ (API 33+) callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingChunk(value)
        }

        // Legacy callback for Android < 33
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleIncomingChunk(characteristic.value)
        }

        private fun handleIncomingChunk(bytes: ByteArray?) {
            if (bytes == null || bytes.isEmpty()) return
            val completedText = reassembler.feed(bytes)
            if (completedText != null) {
                Log.d(TAG, "Successfully received and assembled text from Mac (${completedText.length} chars)!")
                ClipboardRepository.setReceivedFromMac(completedText, context)
            }
        }
    }
}
