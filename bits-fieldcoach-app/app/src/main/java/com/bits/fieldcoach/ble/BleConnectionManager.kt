package com.bits.fieldcoach.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.bits.fieldcoach.audio.Lc3Decoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * BLE Connection Manager for Mentra Live glasses.
 * Ported from MentraOS MentraLive.java (MIT licensed).
 *
 * Supports dual-mode BLE connection:
 *   Mode 1 (CENTRAL): Phone scans for glasses as BLE peripheral
 *   Mode 2 (PERIPHERAL): Phone advertises, glasses connect as GATT client
 *
 * Implements K900 binary protocol, CTKD bonding, LC3 audio,
 * message chunking, file transfer, and readiness handshake.
 */
class BleConnectionManager(private val context: Context) {
    companion object {
        private const val TAG = "BleConnectionManager"
    }

    // -----------------------------------------------------------------------
    // Observable state
    // -----------------------------------------------------------------------
    private val _connectionState = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionState: StateFlow<ConnectionStatus> = _connectionState.asStateFlow()

    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    // -----------------------------------------------------------------------
    // Bluetooth components
    // -----------------------------------------------------------------------
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var isAdvertising = false

    // GATT client mode (phone as central)
    private var clientGatt: BluetoothGatt? = null
    private var clientTxChar: BluetoothGattCharacteristic? = null
    private var clientRxChar: BluetoothGattCharacteristic? = null
    private var clientFileReadChar: BluetoothGattCharacteristic? = null
    private var clientFileWriteChar: BluetoothGattCharacteristic? = null
    private var clientLc3ReadChar: BluetoothGattCharacteristic? = null
    private var clientLc3WriteChar: BluetoothGattCharacteristic? = null

    // BLE scanner
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false

    // -----------------------------------------------------------------------
    // Protocol state
    // -----------------------------------------------------------------------
    private var currentMtu = BleConstants.DEFAULT_MTU
    private var effectiveMtu = BleConstants.DEFAULT_MTU
    private var glassesReady = false
    private var audioConnected = false
    private var fullyBooted = false
    private var killed = false

    // Send queue with rate limiting
    private val sendQueue = ConcurrentLinkedQueue<ByteArray>()
    private val isSending = AtomicBoolean(false)
    private var lastSendTime = 0L
    
    // Gate: block sends until all CCCD descriptor writes complete
    private var pendingDescriptorWrites = 0
    private var notificationsReady = false

    // Message ID for ACK tracking
    private val messageIdCounter = AtomicLong(System.currentTimeMillis())

    // Chunk assembler for incoming chunked messages
    private val chunkAssembler = MessageChunker.ChunkAssembler()

    // File packet reassembly buffer
    private var filePacketBuffer = ByteArrayOutputStream()

    // File transfer session
    private var currentFileTransfer: FileTransferSession? = null

    // LC3 decoder
    private var lc3Decoder: Lc3Decoder? = null

    // Listeners
    private val eventListeners = mutableListOf<(GlassesEvent) -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    // Bonding
    private var bondingReceiver: BroadcastReceiver? = null
    private var bondingRetries = 0

    // -----------------------------------------------------------------------
    // Initialization
    // -----------------------------------------------------------------------

    fun initialize(): Boolean {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not available on this device")
            return false
        }

        if (!bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }

        // Initialize LC3 decoder
        lc3Decoder = Lc3Decoder().apply { initialize() }

        Log.i(TAG, "Bluetooth initialized successfully")
        return true
    }

    // -----------------------------------------------------------------------
    // Connection — dual mode (scan + advertise)
    // -----------------------------------------------------------------------

    // Saved device address for direct reconnection
    private val PREFS_NAME = "bits_fieldcoach"
    private val PREF_DEVICE_ADDRESS = "last_glasses_address"

    fun startAdvertisingAndListen() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Cannot start — Bluetooth not initialized")
            return
        }

        killed = false
        glassesReady = false
        fullyBooted = false
        seenAddresses.clear()
        _discoveredDevices.value = emptyList()
        _connectionState.value = ConnectionStatus.ADVERTISING

        // Try direct connect to saved bonded device first
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedAddress = prefs.getString(PREF_DEVICE_ADDRESS, null)

        if (savedAddress != null) {
            debugLog("Saved: $savedAddress")
            try {
                val device = bluetoothAdapter!!.getRemoteDevice(savedAddress)
                val bondState = device.bondState
                debugLog("Bond: $bondState (need ${BluetoothDevice.BOND_BONDED})")
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    debugLog("Direct GATT connect...")
                    connectToDevice(device)
                    return
                }
            } catch (e: Exception) {
                debugLog("Saved device err: ${e.message}")
            }
        }

        // Also check all bonded devices for Mentra glasses
        try {
            val bondedDevices = bluetoothAdapter!!.bondedDevices
            debugLog("Bonded devices: ${bondedDevices.size}")
            for (device in bondedDevices) {
                val name = device.name ?: continue
                debugLog("Bonded: $name (${device.address})")
                val nameLower = name.lowercase()
                if (nameLower.contains("mentra") || nameLower.contains("live") || 
                    nameLower.contains("xysmart") || nameLower.contains("e4f4")) {
                    debugLog("Found bonded glasses! Direct GATT...")
                    // Save for future
                    prefs.edit().putString(PREF_DEVICE_ADDRESS, device.address).apply()
                    connectToDevice(device)
                    return
                }
            }
        } catch (e: SecurityException) {
            debugLog("Bond list err: ${e.message}")
        }

        debugLog("No bonded glasses. Scanning...")
        // Mode 1: Scan for glasses as peripheral
        startScanning()

        // Mode 2: Advertise so glasses can find us
        setupGattServer()
        startAdvertising()
    }

    // -----------------------------------------------------------------------
    // Mode 1: BLE Scanner (phone=central, glasses=peripheral)
    // -----------------------------------------------------------------------

    private fun startScanning() {
        try {
            bleScanner = bluetoothAdapter?.bluetoothLeScanner
            if (bleScanner == null) {
                Log.e(TAG, "BLE scanner not available")
                return
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            // Wide scan — match by name in callback
            bleScanner?.startScan(null, settings, scanCallback)
            isScanning = true
            Log.i(TAG, "Started BLE scanning for Mentra Live glasses")

            // Timeout
            handler.postDelayed({ stopScanning() }, BleConstants.SCAN_TIMEOUT_MS)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permissions for BLE scan", e)
        }
    }

    private fun stopScanning() {
        try {
            if (isScanning) {
                bleScanner?.stopScan(scanCallback)
                isScanning = false
                Log.d(TAG, "Stopped BLE scanning")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error stopping scan", e)
        }
    }

    // Debug: track all discovered BLE devices AND status messages for display
    private val _discoveredDevices = MutableStateFlow<List<String>>(emptyList())
    val discoveredDevices: StateFlow<List<String>> = _discoveredDevices.asStateFlow()
    private val seenAddresses = mutableSetOf<String>()

    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        _discoveredDevices.value = _discoveredDevices.value + msg
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device ?: return
            val address = device.address ?: return
            val name = try { device.name } catch (e: SecurityException) { null }

            // Log ALL devices with names for debugging
            if (name != null && !seenAddresses.contains(address)) {
                seenAddresses.add(address)
                val entry = "$name ($address)"
                _discoveredDevices.value = _discoveredDevices.value + entry
                Log.d(TAG, "BLE device found: $entry")
            }

            if (name == null) return

            val nameLower = name.lowercase()
            val isGlasses = BleConstants.SCAN_DEVICE_NAMES.any { nameLower.contains(it.lowercase()) } ||
                    nameLower.contains("mentra") ||
                    nameLower.contains("live") ||
                    nameLower.contains("xysmart") ||
                    nameLower.contains("e4f4")

            if (isGlasses) {
                debugLog("Found glasses: $name")
                // Save address for future direct connect
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREF_DEVICE_ADDRESS, device.address).apply()
                debugLog("Saved address: ${device.address}")
                stopScanning()
                stopAdvertising()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            _discoveredDevices.value = _discoveredDevices.value + "SCAN FAILED: error $errorCode"
        }
    }

    // -----------------------------------------------------------------------
    // GATT Client connection (phone connects to glasses)
    // -----------------------------------------------------------------------

    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionStatus.CONNECTING
        Log.i(TAG, "Connecting to glasses as GATT client...")

        try {
            // autoConnect=true for bonded devices — maintains stable connection
            val isBonded = device.bondState == BluetoothDevice.BOND_BONDED
            debugLog("connectGatt bonded=$isBonded")
            device.connectGatt(context, isBonded, gattClientCallback, BluetoothDevice.TRANSPORT_LE)

            // Connection timeout
            handler.postDelayed({
                if (_connectionState.value == ConnectionStatus.CONNECTING) {
                    Log.e(TAG, "Connection timeout — closing GATT")
                    clientGatt?.close()
                    clientGatt = null
                    _connectionState.value = ConnectionStatus.DISCONNECTED
                    scheduleReconnect()
                }
            }, BleConstants.CONNECTION_TIMEOUT_MS)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error connecting to glasses", e)
            _connectionState.value = ConnectionStatus.ERROR
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when {
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                    debugLog("GATT connected!")
                    clientGatt = gatt
                    connectedDevice = gatt?.device
                    _connectionState.value = ConnectionStatus.CONNECTING

                    // Reset state for new connection
                    glassesReady = false
                    fullyBooted = false

                    try {
                        // Request high priority first
                        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        debugLog("High priority set")
                        
                        // Request MTU — onMtuChanged will trigger service discovery
                        debugLog("Requesting MTU...")
                        gatt?.requestMtu(BleConstants.PREFERRED_MTU)
                    } catch (e: SecurityException) {
                        debugLog("ERROR: setup denied")
                        // Try service discovery directly
                        try { gatt?.discoverServices() } catch (e2: Exception) {}
                    }

                    // Initiate CTKD bonding
                    gatt?.device?.let {
                        debugLog("Checking bond...")
                        initiateBonding(it)
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from glasses GATT (status=$status)")
                    handleDisconnect()
                }

                else -> {
                    Log.e(TAG, "GATT connection error: status=$status, state=$newState")
                    handleDisconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            debugLog("Services discovered!")

            // Log ALL services and characteristics for debugging
            gatt?.services?.forEach { svc ->
                debugLog("SVC: ${svc.uuid}")
                svc.characteristics.forEach { char ->
                    debugLog("  CHAR: ${char.uuid} props=${char.properties}")
                }
            }

            val service = gatt?.getService(BleConstants.SERVICE_UUID)
            if (service != null) {
                // Core TX/RX
                clientTxChar = service.getCharacteristic(BleConstants.TX_CHAR_UUID)
                clientRxChar = service.getCharacteristic(BleConstants.RX_CHAR_UUID)

                // File transfer — try primary UUIDs first
                clientFileReadChar = service.getCharacteristic(BleConstants.FILE_READ_UUID)
                clientFileWriteChar = service.getCharacteristic(BleConstants.FILE_WRITE_UUID)

                debugLog("Primary service: TX=${clientTxChar != null} RX=${clientRxChar != null} FileRead=${clientFileReadChar != null} FileWrite=${clientFileWriteChar != null}")
            } else {
                debugLog("Primary service NOT found — scanning all services")
            }

            // Scan ALL services for any missing characteristics
            gatt?.services?.forEach { svc ->
                if (clientFileReadChar == null) {
                    svc.getCharacteristic(BleConstants.FILE_READ_UUID)?.let {
                        clientFileReadChar = it
                        debugLog("FileRead found on svc ${svc.uuid}")
                    }
                }
                if (clientFileWriteChar == null) {
                    svc.getCharacteristic(BleConstants.FILE_WRITE_UUID)?.let {
                        clientFileWriteChar = it
                        debugLog("FileWrite found on svc ${svc.uuid}")
                    }
                }
                if (clientTxChar == null) {
                    svc.getCharacteristic(BleConstants.TX_CHAR_UUID)?.let {
                        clientTxChar = it
                        debugLog("TX found on svc ${svc.uuid}")
                    }
                }
                if (clientRxChar == null) {
                    svc.getCharacteristic(BleConstants.RX_CHAR_UUID)?.let {
                        clientRxChar = it
                        debugLog("RX found on svc ${svc.uuid}")
                    }
                }
                svc.getCharacteristic(BleConstants.LC3_READ_UUID)?.let {
                    clientLc3ReadChar = it
                    debugLog("LC3 read on svc ${svc.uuid}")
                }
                svc.getCharacteristic(BleConstants.LC3_WRITE_UUID)?.let {
                    clientLc3WriteChar = it
                    debugLog("LC3 write on svc ${svc.uuid}")
                }
            }

            // If FileRead still null — use RX as photo receive fallback
            if (clientFileReadChar == null) {
                debugLog("⚠️ FILE_READ_UUID not found — photo data will arrive on RX char (fallback mode)")
            }

            // Enable notifications on all receive characteristics
            debugLog("Enabling notifications...")
            enableNotifications(gatt)

            // Delay before starting heartbeat — let ALL BLE operations complete
            debugLog("Stabilizing (3s)...")
            handler.postDelayed({
                debugLog("Starting heartbeat loop...")
                startReadinessCheckLoop()
                // Also kick the send queue in case it was blocked
                isSending.set(false)
                processSendQueue()
            }, 3000)

            // FORCE READY TIMER — if glasses haven't responded after 12 seconds,
            // force the connection to CONNECTED anyway. The glasses may not send
            // heartbeat responses without MentraOS cloud, but BLE is working.
            handler.postDelayed({
                if (!glassesReady && !killed) {
                    debugLog("⚡ FORCE READY (12s timer) — no HB response but GATT is live")
                    handleGlassesReady()
                }
            }, 12000)

            // Start send queue processor
            processSendQueue()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            val data = characteristic?.value ?: return
            val uuid = characteristic.uuid
            debugLog("RX on ${uuid.toString().take(8)}: ${data.size}b")

            when (uuid) {
                BleConstants.FILE_READ_UUID -> processFilePacketData(data)
                BleConstants.LC3_READ_UUID -> processLc3AudioPacket(data)
                BleConstants.RX_CHAR_UUID, BleConstants.TX_CHAR_UUID -> processReceivedData(data)
                else -> {
                    // Try to detect LC3 by content
                    if (K900Protocol.isLc3AudioPacket(data)) {
                        processLc3AudioPacket(data)
                    } else {
                        processReceivedData(data)
                    }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            isSending.set(false)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                debugLog("TX FAILED: status=$status")
            } else {
                debugLog("TX OK: ${characteristic?.value?.size ?: 0}b")
            }
            // Schedule next send with rate limiting
            handler.postDelayed({ processSendQueue() }, BleConstants.MIN_SEND_DELAY_MS)
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
                effectiveMtu = minOf(mtu, BleConstants.BES_MAX_MTU) - 3
                debugLog("MTU: $mtu (eff: $effectiveMtu)")
            } else {
                debugLog("MTU failed: $status")
            }
            // Chain: MTU done → discover services
            try {
                debugLog("Discovering services...")
                gatt?.discoverServices()
            } catch (e: SecurityException) {
                debugLog("Service discovery denied")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                debugLog("Notify enable FAILED: $status")
            } else {
                debugLog("Notify enabled OK")
            }
            pendingDescriptorWrites--
            if (pendingDescriptorWrites <= 0) {
                pendingDescriptorWrites = 0
                notificationsReady = true
                debugLog("✅ All notifications enabled — sends UNLOCKED")
                processSendQueue()
            }
        }
    }

    /**
     * Enable notifications on all receive characteristics.
     */
    private fun enableNotifications(gatt: BluetoothGatt?) {
        val chars = listOfNotNull(clientRxChar, clientTxChar, clientFileReadChar, clientLc3ReadChar)

        notificationsReady = false
        pendingDescriptorWrites = 0

        // Count how many descriptor writes we'll do
        for (char in chars) {
            val descriptor = char.getDescriptor(BleConstants.CCC_UUID)
            if (descriptor != null) pendingDescriptorWrites++
        }
        debugLog("Enabling $pendingDescriptorWrites notifications (sends LOCKED)...")

        if (pendingDescriptorWrites == 0) {
            notificationsReady = true
            debugLog("No descriptors to write — sends UNLOCKED")
            return
        }

        // Serialize CCCD writes — BLE only allows one descriptor write at a time
        // onDescriptorWrite callback will fire for each, decrementing pendingDescriptorWrites
        for ((index, char) in chars.withIndex()) {
            handler.postDelayed({
                try {
                    gatt?.setCharacteristicNotification(char, true)

                    val descriptor = char.getDescriptor(BleConstants.CCC_UUID)
                    if (descriptor != null) {
                        val hasIndicate = (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                        descriptor.value = if (hasIndicate)
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        else
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt?.writeDescriptor(descriptor)
                        debugLog("Notify ON: ${char.uuid.toString().take(8)} (${index + 1}/${chars.size})")
                    } else {
                        debugLog("No CCCD on ${char.uuid.toString().take(8)} — setNotif only")
                    }
                } catch (e: SecurityException) {
                    debugLog("Notify DENIED: ${char.uuid.toString().take(8)}")
                    pendingDescriptorWrites--
                    if (pendingDescriptorWrites <= 0) {
                        notificationsReady = true
                        debugLog("✅ Notifications done (with errors) — sends UNLOCKED")
                        processSendQueue()
                    }
                }
            }, index * 500L)
        }

        // Safety: unlock sends after 5 seconds no matter what
        handler.postDelayed({
            if (!notificationsReady) {
                debugLog("⚠️ Notification setup timeout — FORCE UNLOCK sends")
                notificationsReady = true
                pendingDescriptorWrites = 0
                processSendQueue()
            }
        }, 5000)
    }

    // -----------------------------------------------------------------------
    // Mode 2: BLE Peripheral (phone advertises, glasses connect)
    // -----------------------------------------------------------------------

    private fun setupGattServer() {
        try {
            gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
            if (gattServer == null) {
                Log.e(TAG, "Failed to open GATT server")
                return
            }

            val service = BluetoothGattService(
                BleConstants.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            txCharacteristic = BluetoothGattCharacteristic(
                BleConstants.TX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or
                        BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val rxCharacteristic = BluetoothGattCharacteristic(
                BleConstants.RX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            service.addCharacteristic(txCharacteristic!!)
            service.addCharacteristic(rxCharacteristic)
            gattServer?.addService(service)
            Log.i(TAG, "GATT server configured")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permissions for GATT server", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up GATT server", e)
        }
    }

    private fun startAdvertising() {
        try {
            bluetoothAdapter?.name = BleConstants.DEVICE_NAME
            advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.e(TAG, "BLE advertising not supported")
                return
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build()

            advertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.i(TAG, "Started BLE advertising as '${BleConstants.DEVICE_NAME}'")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permissions for advertising", e)
        }
    }

    private fun stopAdvertising() {
        try {
            if (isAdvertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error stopping advertising", e)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "BLE advertising started — waiting for glasses")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
            isAdvertising = false
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Glasses connected via GATT server: ${device?.address}")
                    connectedDevice = device
                    stopAdvertising()
                    stopScanning()

                    // Initiate CTKD bonding
                    device?.let { initiateBonding(it) }

                    // Start readiness check
                    handler.postDelayed({
                        startReadinessCheckLoop()
                    }, 500)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Glasses disconnected from GATT server")
                    handleDisconnect()
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission error sending GATT response", e)
                }
            }

            if (value != null) {
                when (characteristic?.uuid) {
                    BleConstants.RX_CHAR_UUID -> processReceivedData(value)
                    BleConstants.FILE_READ_UUID -> processFilePacketData(value)
                    else -> {
                        if (K900Protocol.isLc3AudioPacket(value)) {
                            processLc3AudioPacket(value)
                        } else {
                            processReceivedData(value)
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            currentMtu = mtu
            effectiveMtu = minOf(mtu, BleConstants.BES_MAX_MTU) - 3
            Log.i(TAG, "Server MTU changed to $mtu (effective: $effectiveMtu)")
        }
    }

    // -----------------------------------------------------------------------
    // CTKD Bonding (BT Classic pairing via BLE)
    // -----------------------------------------------------------------------

    private fun initiateBonding(device: BluetoothDevice) {
        bondingRetries = 0
        registerBondingReceiver()
        
        try {
            val bondState = device.bondState
            when (bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    debugLog("Already bonded — skipping CTKD")
                    audioConnected = true
                    checkFullyBooted()
                }
                BluetoothDevice.BOND_BONDING -> {
                    debugLog("Bonding in progress...")
                }
                else -> {
                    debugLog("Not bonded — creating bond...")
                    createBond(device)
                }
            }
        } catch (e: SecurityException) {
            debugLog("Bond check failed — continuing")
            audioConnected = true
            checkFullyBooted()
        }
    }

    private fun registerBondingReceiver() {
        if (bondingReceiver != null) return

        bondingReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)

                when (state) {
                    BluetoothDevice.BOND_BONDED -> {
                        Log.i(TAG, "CTKD bonding succeeded: ${device.address}")
                        audioConnected = true
                        checkFullyBooted()
                    }

                    BluetoothDevice.BOND_BONDING -> {
                        Log.d(TAG, "Bonding in progress...")
                    }

                    BluetoothDevice.BOND_NONE -> {
                        Log.w(TAG, "Bonding failed")
                        bondingRetries++
                        if (bondingRetries < BleConstants.MAX_PAIRING_RETRIES) {
                            handler.postDelayed({
                                connectedDevice?.let { createBond(it) }
                            }, BleConstants.PAIRING_RETRY_DELAY_MS)
                        } else {
                            Log.e(TAG, "Max bonding retries exceeded — continuing without BT Classic")
                            audioConnected = true  // Mark as connected anyway for BLE-only mode
                            checkFullyBooted()
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bondingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bondingReceiver, filter)
        }
    }

    private fun createBond(device: BluetoothDevice) {
        try {
            val method = device.javaClass.getMethod("createBond")
            method.invoke(device)
            Log.d(TAG, "createBond() invoked via reflection")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invoke createBond()", e)
            // Continue anyway — bonding may not be required for all firmware versions
            audioConnected = true
            checkFullyBooted()
        }
    }

    private fun unregisterBondingReceiver() {
        bondingReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // Already unregistered
            }
            bondingReceiver = null
        }
    }

    // -----------------------------------------------------------------------
    // Readiness Handshake
    // -----------------------------------------------------------------------

    private var readinessCheckRunnable: Runnable? = null

    private var readinessCheckCount = 0

    private fun startReadinessCheckLoop() {
        stopReadinessCheckLoop()
        readinessCheckCount = 0

        readinessCheckRunnable = object : Runnable {
            override fun run() {
                if (glassesReady || killed) return
                readinessCheckCount++

                debugLog("Heartbeat #$readinessCheckCount → glasses")
                queueData(GlassesProtocol.createHeartbeatRequest())

                handler.postDelayed(this, BleConstants.READINESS_CHECK_INTERVAL_MS)
            }
        }
        handler.post(readinessCheckRunnable!!)
    }

    private fun stopReadinessCheckLoop() {
        readinessCheckRunnable?.let { handler.removeCallbacks(it) }
        readinessCheckRunnable = null
    }

    /**
     * Handle glasses heartbeat response (sr_hrt with ready flag).
     */
    private var heartbeatResponseCount = 0

    private fun handleHeartbeatResponse(ready: Boolean, battery: Int, charging: Boolean) {
        heartbeatResponseCount++
        debugLog("HB response #$heartbeatResponseCount ready=$ready bat=$battery")

        if (battery >= 0) {
            _batteryLevel.value = battery
        }

        if (ready && !glassesReady) {
            debugLog("Glasses READY! Sending phone_ready...")
            queueData(GlassesProtocol.createPhoneReady())
        } else if (!ready && !glassesReady) {
            debugLog("Glasses SOC not ready yet (attempt $heartbeatResponseCount)...")
            
            // After 3 heartbeat responses with battery data, force ready
            // The glasses ARE communicating — the ready flag may never flip
            // on standalone BLE without MentraOS cloud handshake
            if (heartbeatResponseCount >= 3 && battery >= 0) {
                debugLog("FORCE READY — got $heartbeatResponseCount HB responses with battery=$battery")
                handleGlassesReady()
            }
        }
    }

    /**
     * Handle glasses_ready message — fully connected.
     */
    private fun handleGlassesReady() {
        if (glassesReady) return

        glassesReady = true
        stopReadinessCheckLoop()
        debugLog("GLASSES READY! Setting up mic+cam...")

        // Send MTU config
        queueData(GlassesProtocol.createMtuConfig(effectiveMtu))

        // Request battery and firmware
        handler.postDelayed({
            queueData(GlassesProtocol.createBatteryRequest())
            queueData(GlassesProtocol.createFirmwareRequest())
        }, 200)

        // Enable mic
        handler.postDelayed({
            queueData(GlassesProtocol.createMicCommand(true))
        }, 500)

        // Start heartbeat
        startHeartbeat()

        checkFullyBooted()
    }

    private fun checkFullyBooted() {
        if (glassesReady && audioConnected && !fullyBooted) {
            fullyBooted = true
            _connectionState.value = ConnectionStatus.CONNECTED
            debugLog("FULLY CONNECTED! Mic+Cam ready")

            // Start LC3 audio playback
            lc3Decoder?.startPlayback()

            notifyEvent(GlassesEvent.ConnectionState(connected = true))
        }
    }

    // -----------------------------------------------------------------------
    // Heartbeat
    // -----------------------------------------------------------------------

    private var heartbeatRunnable: Runnable? = null
    private var heartbeatCount = 0

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatCount = 0

        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (killed) return
                queueData(GlassesProtocol.createPing())
                heartbeatCount++

                // Request battery every 10 heartbeats (~5 minutes)
                if (heartbeatCount % 10 == 0) {
                    queueData(GlassesProtocol.createBatteryRequest())
                }

                handler.postDelayed(this, BleConstants.HEARTBEAT_INTERVAL_MS)
            }
        }
        handler.postDelayed(heartbeatRunnable!!, BleConstants.HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    // -----------------------------------------------------------------------
    // Send queue with rate limiting
    // -----------------------------------------------------------------------

    /**
     * Queue data for sending via BLE. Applies K900 framing if needed.
     */
    fun queueData(data: ByteArray) {
        sendQueue.offer(data)
        processSendQueue()
    }

    /**
     * Send JSON string to glasses with optional chunking.
     */
    fun sendJson(json: JSONObject, wakeup: Boolean = false) {
        val jsonStr = json.toString()

        if (MessageChunker.needsChunking(jsonStr)) {
            try {
                val msgId = messageIdCounter.incrementAndGet()
                val chunks = MessageChunker.createChunks(jsonStr, msgId)
                for ((index, chunk) in chunks.withIndex()) {
                    val packed = K900Protocol.packJsonToK900(chunk.toString(), wakeup && index == 0)
                    if (packed != null) {
                        // Delay between chunks
                        handler.postDelayed({
                            queueData(packed)
                        }, index * BleConstants.CHUNK_SEND_DELAY_MS)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error chunking message", e)
            }
        } else {
            val packed = K900Protocol.packJsonToK900(jsonStr, wakeup)
            if (packed != null) {
                queueData(packed)
            }
        }
    }

    private fun processSendQueue() {
        if (sendQueue.isEmpty()) return

        // Don't send while descriptor writes are pending
        if (!notificationsReady) {
            debugLog("Send blocked — notifications not ready yet")
            return
        }

        // Safety: if isSending has been true for >2 seconds, reset it
        if (isSending.get()) {
            val elapsed = System.currentTimeMillis() - lastSendTime
            if (elapsed > 2000) {
                debugLog("SEND STUCK — resetting")
                isSending.set(false)
            } else {
                return
            }
        }

        val now = System.currentTimeMillis()
        val elapsed = now - lastSendTime
        if (elapsed < BleConstants.MIN_SEND_DELAY_MS) {
            handler.postDelayed({ processSendQueue() }, BleConstants.MIN_SEND_DELAY_MS - elapsed)
            return
        }

        val data = sendQueue.poll() ?: return
        isSending.set(true)
        lastSendTime = now

        sendDataInternal(data)
    }

    private fun sendDataInternal(data: ByteArray) {
        try {
            // Try client mode first
            if (clientGatt != null) {
                // Try TX characteristic first, then RX if TX fails
                var written = false
                
                clientTxChar?.let { tx ->
                    val props = tx.properties
                    tx.writeType = if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) 
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE 
                    else 
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    tx.value = data
                    written = clientGatt!!.writeCharacteristic(tx)
                    debugLog("TX: ${data.size}b r=$written p=$props")
                }
                
                // If TX failed, try RX (some glasses swap the direction)
                if (!written && clientRxChar != null) {
                    val rx = clientRxChar!!
                    val props = rx.properties
                    rx.writeType = if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) 
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE 
                    else 
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    rx.value = data
                    written = clientGatt!!.writeCharacteristic(rx)
                    debugLog("RX-W: ${data.size}b r=$written p=$props")
                }
                // Reset isSending immediately since we won't get a callback with NO_RESPONSE
                isSending.set(false)
                // Schedule next send
                handler.postDelayed({ processSendQueue() }, BleConstants.MIN_SEND_DELAY_MS)
                return
            }

            // Fall back to server mode
            val char = txCharacteristic
            if (gattServer != null && char != null && connectedDevice != null) {
                char.value = data
                gattServer?.notifyCharacteristicChanged(connectedDevice, char, false)
                debugLog("WRITE(srv): ${data.size}b")
                isSending.set(false)
                handler.postDelayed({ processSendQueue() }, BleConstants.MIN_SEND_DELAY_MS)
                return
            }

            debugLog("WRITE FAIL: no connection")
            isSending.set(false)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error sending data", e)
            isSending.set(false)
        }
    }

    // -----------------------------------------------------------------------
    // Public send methods
    // -----------------------------------------------------------------------

    fun sendCommand(data: ByteArray): Boolean {
        if (connectedDevice == null) {
            Log.w(TAG, "Cannot send — not connected")
            return false
        }
        queueData(data)
        return true
    }

    fun requestPhoto(requestId: String): Boolean {
        photoInProgress = true
        debugLog("Photo requested: $requestId")
        // Auto-clear after 20 seconds
        handler.postDelayed({ photoInProgress = false }, 20000)
        return sendCommand(GlassesProtocol.createPhotoCommand(requestId))
    }

    fun setMicrophoneEnabled(enabled: Boolean): Boolean {
        return sendCommand(GlassesProtocol.createMicCommand(enabled))
    }

    fun enableAudioTx(enabled: Boolean): Boolean {
        return sendCommand(GlassesProtocol.createEnableAudioTx(enabled))
    }

    fun startRtmpStream(rtmpUrl: String): Boolean {
        val json = JSONObject().apply {
            put("type", "start_rtmp_stream")
            put("url", rtmpUrl)
        }
        debugLog("Starting RTMP stream to: $rtmpUrl")
        return sendCommand(K900Protocol.packJsonToK900(json.toString(), true) ?: return false)
    }

    fun stopRtmpStream(): Boolean {
        val json = JSONObject().apply {
            put("type", "stop_rtmp_stream")
        }
        debugLog("Stopping RTMP stream")
        return sendCommand(K900Protocol.packJsonToK900(json.toString(), true) ?: return false)
    }

    fun startVideoStream(): Boolean {
        val json = JSONObject().apply {
            put("type", "start_video_stream")
        }
        debugLog("Starting video stream")
        return sendCommand(K900Protocol.packJsonToK900(json.toString(), true) ?: return false)
    }

    fun stopVideoStream(): Boolean {
        val json = JSONObject().apply {
            put("type", "stop_video_stream")
        }
        debugLog("Stopping video stream")
        return sendCommand(K900Protocol.packJsonToK900(json.toString(), true) ?: return false)
    }

    fun sendWifiCredentials(ssid: String, password: String): Boolean {
        val json = JSONObject().apply {
            put("type", "set_wifi_credentials")
            put("ssid", ssid)
            put("password", password)
        }
        debugLog("Sending WiFi credentials: $ssid")
        return sendCommand(K900Protocol.packJsonToK900(json.toString(), true) ?: return false)
    }

    // -----------------------------------------------------------------------
    // Incoming data processing
    // -----------------------------------------------------------------------

    private fun processReceivedData(data: ByteArray) {
        debugLog("RX: ${data.size} bytes")

        // Try K900 protocol first
        if (K900Protocol.isK900Format(data)) {
            // Check if this is a PHOTO file packet (CMD_TYPE_PHOTO = 0x31)
            if (data.size >= 3 && data[2] == K900Protocol.CMD_TYPE_PHOTO) {
                debugLog("RX K900 PHOTO packet on RX char — routing to file handler")
                processFilePacketData(data)
                return
            }
            val json = K900Protocol.processReceivedBytesToJson(data)
            if (json != null) {
                debugLog("RX K900 JSON: ${json.toString().take(60)}")
                handleReceivedJson(json)
                return
            }
            debugLog("RX K900 binary: ${data.size}b type=${String.format("%02X", data[2])}")
            // Could still be a file packet — try it
            if (data.size > 30) {
                debugLog("Large binary — attempting file packet parse")
                processFilePacketData(data)
            }
            return
        }

        // Try raw JSON
        try {
            val text = String(data, Charsets.UTF_8).trim()
            if (text.startsWith("{")) {
                debugLog("RX JSON: ${text.take(60)}")
                val json = JSONObject(text)
                handleReceivedJson(json)
            } else {
                debugLog("RX text: ${text.take(40)}")
            }
        } catch (e: Exception) {
            debugLog("RX unknown: ${data.size}b")
        }
    }

    private fun handleReceivedJson(json: JSONObject) {
        val event = GlassesProtocol.parseJsonEvent(json)

        when (event) {
            is GlassesEvent.HeartbeatResponse -> {
                handleHeartbeatResponse(event.ready, event.battery, event.charging)
            }

            is GlassesEvent.GlassesReady -> {
                handleGlassesReady()
            }

            is GlassesEvent.BatteryUpdate -> {
                _batteryLevel.value = event.level
                notifyEvent(event)
            }

            is GlassesEvent.ChunkedMessage -> {
                handleChunkedMessage(event.json)
            }

            is GlassesEvent.MessageAck -> {
                Log.d(TAG, "ACK received for message ${event.messageId}")
            }

            is GlassesEvent.KeepAliveAck -> {
                Log.v(TAG, "Keep-alive ACK received")
            }

            is GlassesEvent.BlePhotoReady -> {
                val bleImgId = event.bleImgId
                debugLog("📸 ble_photo_ready received: bleImgId=$bleImgId")
                photoInProgress = true
                notifyEvent(event)
            }

            is GlassesEvent.RtmpStreamStatus -> {
                if (event.errorDetails != null) {
                    debugLog("📡 RTMP stream status: ${event.status} error=${event.errorDetails}")
                } else {
                    debugLog("📡 RTMP stream status: ${event.status}")
                }
                notifyEvent(event)
            }

            else -> {
                if (event != null) notifyEvent(event)
            }
        }
    }

    private fun handleChunkedMessage(json: JSONObject) {
        try {
            val chunkInfo = MessageChunker.getChunkInfo(json) ?: return
            val completeMessage = chunkAssembler.addChunk(chunkInfo)

            if (completeMessage != null) {
                Log.d(TAG, "Chunk reassembly complete: ${completeMessage.length} bytes")
                try {
                    val reassembledJson = JSONObject(completeMessage)
                    handleReceivedJson(reassembledJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing reassembled message", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing chunked message", e)
        }
    }

    // -----------------------------------------------------------------------
    // File packet processing (camera photos)
    // -----------------------------------------------------------------------

    private fun processFilePacketData(data: ByteArray) {
        // Append to reassembly buffer
        filePacketBuffer.write(data)
        val bufferData = filePacketBuffer.toByteArray()

        // Try to extract complete file packets
        val (packets, remaining) = K900Protocol.extractCompleteFilePackets(bufferData)

        // Update buffer with remaining data
        filePacketBuffer.reset()
        if (remaining.isNotEmpty()) {
            filePacketBuffer.write(remaining)
        }

        for (packetData in packets) {
            val packetInfo = K900Protocol.extractFilePacket(packetData)
            if (packetInfo != null && packetInfo.isValid) {
                handleFilePacket(packetInfo)
            } else {
                Log.w(TAG, "Invalid file packet received")
            }
        }
    }

    private fun handleFilePacket(packet: K900Protocol.FilePacketInfo) {
        // Initialize or continue file transfer session
        val session = currentFileTransfer ?: FileTransferSession(
            fileName = packet.fileName,
            fileSize = packet.fileSize,
            fileType = packet.fileType
        ).also { currentFileTransfer = it }

        session.addPacket(packet.packIndex, packet.data)
        Log.d(TAG, "File packet ${packet.packIndex}: ${packet.packSize} bytes (${session.receivedPackets}/${session.totalPackets} for ${packet.fileName})")

        if (session.isComplete()) {
            val fileData = session.assembleFile()
            Log.i(TAG, "File transfer complete: ${packet.fileName} (${fileData.size} bytes)")
            currentFileTransfer = null
            filePacketBuffer.reset()

            // Emit photo event
            if (packet.fileType == K900Protocol.CMD_TYPE_PHOTO) {
                notifyEvent(
                    GlassesEvent.PhotoResponse(
                        requestId = packet.fileName,
                        success = true,
                        photoData = fileData,
                        error = null
                    )
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // LC3 audio processing
    // -----------------------------------------------------------------------

    private fun processLc3AudioPacket(data: ByteArray) {
        if (!K900Protocol.isLc3AudioPacket(data)) return

        // Decode and play
        val pcmData = lc3Decoder?.processLc3Packet(data)

        // Forward audio data event to listeners
        if (pcmData != null) {
            notifyEvent(GlassesEvent.AudioData(data = pcmData))
        }
    }

    // -----------------------------------------------------------------------
    // Event listeners
    // -----------------------------------------------------------------------

    fun addEventListener(listener: (GlassesEvent) -> Unit) {
        eventListeners.add(listener)
    }

    private fun notifyEvent(event: GlassesEvent) {
        for (listener in eventListeners) {
            try {
                listener(event)
            } catch (e: Exception) {
                Log.e(TAG, "Error in event listener", e)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Disconnect and reconnect
    // -----------------------------------------------------------------------

    private var photoInProgress = false

    private fun handleDisconnect() {
        debugLog("DISCONNECT detected")
        
        // If photo is in progress, try to reconnect without full cleanup
        if (photoInProgress) {
            debugLog("Photo in progress — quick reconnect")
            handler.postDelayed({
                if (!killed) startAdvertisingAndListen()
            }, 1000)
            return
        }

        val wasConnected = fullyBooted

        clientGatt?.let {
            try { it.close() } catch (e: Exception) { }
        }
        clientGatt = null
        clientTxChar = null
        clientRxChar = null
        clientFileReadChar = null
        clientFileWriteChar = null
        clientLc3ReadChar = null
        clientLc3WriteChar = null
        connectedDevice = null
        glassesReady = false
        fullyBooted = false
        audioConnected = false

        stopReadinessCheckLoop()
        stopHeartbeat()
        sendQueue.clear()
        isSending.set(false)
        chunkAssembler.clear()
        filePacketBuffer.reset()
        currentFileTransfer = null
        lc3Decoder?.stopPlayback()

        _connectionState.value = ConnectionStatus.DISCONNECTED

        if (wasConnected) {
            notifyEvent(GlassesEvent.ConnectionState(connected = false))
        }

        if (!killed) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        handler.postDelayed({
            if (_connectionState.value == ConnectionStatus.DISCONNECTED && !killed) {
                Log.i(TAG, "Auto-reconnecting...")
                startAdvertisingAndListen()
            }
        }, BleConstants.AUTO_RECONNECT_DELAY_MS)
    }

    fun disconnect() {
        killed = true
        stopScanning()
        stopAdvertising()
        stopReadinessCheckLoop()
        stopHeartbeat()
        unregisterBondingReceiver()

        try {
            clientGatt?.let {
                it.close()
                clientGatt = null
            }
            connectedDevice?.let { device ->
                gattServer?.cancelConnection(device)
            }
            gattServer?.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error during disconnect", e)
        }

        connectedDevice = null
        glassesReady = false
        fullyBooted = false
        audioConnected = false
        sendQueue.clear()
        lc3Decoder?.release()
        _connectionState.value = ConnectionStatus.DISCONNECTED
        Log.i(TAG, "Disconnected from glasses")
    }

    fun isConnected(): Boolean = connectedDevice != null
    fun isFullyBooted(): Boolean = fullyBooted

    // -----------------------------------------------------------------------
    // File transfer session
    // -----------------------------------------------------------------------

    private class FileTransferSession(
        val fileName: String,
        val fileSize: Int,
        val fileType: Byte
    ) {
        private val packets = mutableMapOf<Int, ByteArray>()
        var receivedPackets = 0
            private set

        // BES hardcodes FILE_PACK_SIZE = 400 for calculating total packets
        val totalPackets: Int = if (fileSize > 0)
            Math.ceil(fileSize.toDouble() / K900Protocol.FILE_PACK_SIZE).toInt()
        else 1

        fun addPacket(index: Int, data: ByteArray) {
            if (!packets.containsKey(index)) {
                packets[index] = data
                receivedPackets = packets.size
            }
        }

        fun isComplete(): Boolean = receivedPackets >= totalPackets

        fun assembleFile(): ByteArray {
            val output = ByteArrayOutputStream()
            for (i in 0 until totalPackets) {
                packets[i]?.let { output.write(it) }
            }
            return output.toByteArray()
        }
    }
}

enum class ConnectionStatus {
    DISCONNECTED,
    ADVERTISING,
    CONNECTING,
    CONNECTED,
    ERROR
}
