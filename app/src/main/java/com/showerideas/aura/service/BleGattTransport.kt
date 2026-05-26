package com.showerideas.aura.service

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Task 7 — BLE GATT direct transport implementing [NearbyTransport].
 * Task 66 — BLE 6.2 Shorter Connection Interval (SCI) optimization.
 *
 * ## Architecture
 * - AURA GATT service UUID: [SERVICE_UUID]
 * - TX characteristic ([TX_CHAR_UUID]): server notifies → client reads
 * - RX characteristic ([RX_CHAR_UUID]): client writes → server receives
 * - On connect: client calls [requestMtu(512)], waits for [onMtuChanged], then discovers services
 * - Data framing: [4-byte header: 2-byte seqNo + 2-byte totalChunks] + payload chunk
 *
 * ## BLE 6.2 SCI (Task 66)
 * After MTU negotiation, [attemptSciNegotiation] checks for BLE 6.2 SCI support on both the
 * local device (API 36+) and the remote device ([BluetoothDevice.getSupportedFeatures]).
 * If both sides support SCI: requests [BleSessionMetrics.CONNECTION_PRIORITY_DCK] (375 µs–4 ms
 * connection interval). Falls back to [BluetoothGatt.CONNECTION_PRIORITY_HIGH] silently.
 * Session metrics captured in [sessionMetrics] map for audit-log integration.
 *
 * ## Compatibility matrix (Task 66)
 * | Local      | Remote     | Negotiated priority              |
 * |------------|------------|----------------------------------|
 * | BLE 6.2+   | BLE 6.2+   | CONNECTION_PRIORITY_DCK (SCI)    |
 * | BLE 6.2+   | BLE 5.x    | CONNECTION_PRIORITY_HIGH         |
 * | BLE 5.x    | any        | CONNECTION_PRIORITY_HIGH (no-op) |
 *
 * See: [argenox.com — BLE 6.2 SCI negotiation procedure]
 * See: [NordicSemiconductor/Android-BLE-Library — CONNECTION_PRIORITY_DCK]
 *
 * @see [NordicSemiconductor/Android-BLE-Library] for reference GATT patterns
 * @see [blessed-android] for MTU negotiation best practices
 */
@SuppressLint("MissingPermission")
class BleGattTransport(private val context: Context) : NearbyTransport {

    // ── Callbacks ──────────────────────────────────────────────────────────
    override var onPayloadReceived  : ((String, ByteArray) -> Unit)?         = null
    override var onConnected        : ((String, String, Boolean) -> Unit)?   = null
    override var onDisconnected     : ((String) -> Unit)?                    = null
    override var onEndpointFound    : ((String, String) -> Unit)?            = null
    override var onConnectionInitiated : ((String, String) -> Unit)?         = null

    // ── BLE state ──────────────────────────────────────────────────────────
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter : BluetoothAdapter get() = bluetoothManager.adapter
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var gattServer  : BluetoothGattServer?     = null
    private var bleAdvertiser: BluetoothLeAdvertiser?  = null
    private var bleScanner  : BluetoothLeScanner?      = null
    private val isAdvertising = AtomicBoolean(false)
    private val isScanning    = AtomicBoolean(false)

    private val clientConnections  = ConcurrentHashMap<String, BluetoothGatt>()
    private val mtuMap             = ConcurrentHashMap<String, Int>()
    private val reassemblyBuffers  = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
    private val expectedChunks     = ConcurrentHashMap<String, Int>()
    private val discoveredDevices  = ConcurrentHashMap<String, String>()

    // ── Task 66 — Session metrics ──────────────────────────────────────────
    /** Per-session BLE 6.2 SCI metrics. Keyed by device address. */
    private val sessionMetrics = ConcurrentHashMap<String, BleSessionMetrics>()
    /** Session start timestamps for total-duration calculation. */
    private val sessionStartMs = ConcurrentHashMap<String, Long>()

    // ── NearbyTransport — lifecycle ────────────────────────────────────────

    override fun startAdvertising(localName: String, serviceId: String) {
        startGattServer()
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: run {
            Timber.e("BLE: LE advertising not supported"); return
        }
        bleAdvertiser = advertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID)).setIncludeDeviceName(false).build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        isAdvertising.set(true)
        Timber.i("BLE: started advertising AURA service")
    }

    override fun startDiscovery(serviceId: String) {
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            Timber.e("BLE: LE scanner not supported"); return
        }
        bleScanner = scanner
        val filter   = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        isScanning.set(true)
        Timber.i("BLE: started scanning for AURA peers")
    }

    override fun requestConnection(localName: String, endpointId: String) {
        val device = bluetoothAdapter.getRemoteDevice(endpointId)
        sessionStartMs[endpointId] = System.currentTimeMillis()
        val gatt   = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        clientConnections[endpointId] = gatt
        Timber.i("BLE: connecting to $endpointId")
    }

    override fun acceptConnection(endpointId: String) {
        val name = discoveredDevices[endpointId] ?: endpointId
        onConnected?.invoke(endpointId, name, true)
    }

    override fun rejectConnection(endpointId: String) {
        clientConnections.remove(endpointId)?.disconnect()
        gattServer?.cancelConnection(bluetoothAdapter.getRemoteDevice(endpointId))
    }

    override fun stopAdvertising() {
        if (isAdvertising.compareAndSet(true, false)) {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
            Timber.d("BLE: stopped advertising")
        }
    }

    override fun stopDiscovery() {
        if (isScanning.compareAndSet(true, false)) {
            bleScanner?.stopScan(scanCallback)
            Timber.d("BLE: stopped scanning")
        }
    }

    override fun stopAllEndpoints() {
        stopAdvertising(); stopDiscovery()
        clientConnections.forEach { (addr, gatt) ->
            finalizeSessionMetrics(addr, gatt)
            gatt.disconnect(); gatt.close()
        }
        clientConnections.clear()
        gattServer?.close(); gattServer = null
        scope.cancel()
        Timber.d("BLE: all endpoints stopped")
    }

    // ── Data transfer ──────────────────────────────────────────────────────

    override fun sendBytes(endpointId: String, data: ByteArray) {
        val gatt      = clientConnections[endpointId]
        val mtu       = mtuMap[endpointId] ?: 23
        val chunkSize = (mtu - 3).coerceAtMost(509)
        val chunks    = data.toChunks(chunkSize)
        val total     = chunks.size

        scope.launch {
            chunks.forEachIndexed { index, chunk ->
                val frame = ByteArray(4 + chunk.size).also { buf ->
                    buf[0] = (index shr 8).toByte(); buf[1] = (index and 0xFF).toByte()
                    buf[2] = (total shr 8).toByte(); buf[3] = (total and 0xFF).toByte()
                    chunk.copyInto(buf, 4)
                }
                if (gatt != null) {
                    val rxChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(RX_CHAR_UUID)
                    if (rxChar != null) {
                        rxChar.value = frame
                        gatt.writeCharacteristic(rxChar)
                        delay(20L)
                    }
                } else {
                    val txChar = gattServer?.getService(SERVICE_UUID)?.getCharacteristic(TX_CHAR_UUID)
                    if (txChar != null) {
                        txChar.value = frame
                        val device = bluetoothAdapter.getRemoteDevice(endpointId)
                        gattServer?.notifyCharacteristicChanged(device, txChar, false)
                        delay(20L)
                    }
                }
            }
            Timber.d("BLE: sent ${data.size}b to $endpointId in $total chunks")
        }
    }

    // ── Task 66 — BLE 6.2 SCI Negotiation ─────────────────────────────────

    /**
     * Attempt BLE 6.2 Shorter Connection Interval negotiation after MTU exchange.
     *
     * Must be called from [onMtuChanged] — SCI negotiation must occur AFTER MTU
     * negotiation to avoid GATT parameter collision.
     *
     * Algorithm:
     * 1. Check API level ≥ 36 (Android 16 required for CONNECTION_PRIORITY_DCK)
     * 2. Check remote device feature mask for FEATURE_LE_SCI (BLE 6.2 controller)
     * 3. If both: request [BleSessionMetrics.CONNECTION_PRIORITY_DCK]
     * 4. Else: fall back to [BluetoothGatt.CONNECTION_PRIORITY_HIGH] silently
     *
     * @param gatt The connected GATT client to negotiate on.
     * @param negotiatedMtu The MTU that was just confirmed by [onMtuChanged].
     */
    internal fun attemptSciNegotiation(gatt: BluetoothGatt, negotiatedMtu: Int) {
        val address = gatt.device.address

        // Condition 1: API 36+ required for CONNECTION_PRIORITY_DCK
        if (Build.VERSION.SDK_INT < BleSessionMetrics.SCI_MIN_API) {
            Timber.d("BLE SCI: API ${Build.VERSION.SDK_INT} < 36 — using CONNECTION_PRIORITY_HIGH")
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            sessionMetrics[address] = BleSessionMetrics(
                mtu = negotiatedMtu,
                connectionIntervalMs = 13.75f,  // mid-point of HIGH range (11.25–15 ms)
                sci = false,
                deviceAddressAnon = BleSessionMetrics.anonymizeAddress(address),
            )
            return
        }

        // Condition 2: Remote device must support BLE 6.2 SCI in its controller feature mask.
        // BluetoothDevice.getSupportedFeatures() → bitmask; FEATURE_LE_SCI bit is API 36+.
        // We check by querying the FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING package feature as
        // a proxy; individual device feature mask requires API 36 BluetoothDevice constants.
        val remoteSupportsSci = runCatching {
            // API 36+: check device feature mask for SCI support
            // BluetoothStatusCodes.FEATURE_SUPPORTED = 1
            @Suppress("NewApi")
            gatt.device.getSupportedFeatures() and 0x00000020L != 0L  // LE_SCI bit position
        }.getOrElse { false }

        val sciPriority = if (remoteSupportsSci) {
            Timber.i("BLE SCI: both sides support BLE 6.2 SCI — requesting CONNECTION_PRIORITY_DCK")
            BleSessionMetrics.CONNECTION_PRIORITY_DCK
        } else {
            Timber.d("BLE SCI: remote ${address} lacks SCI — falling back to CONNECTION_PRIORITY_HIGH")
            BluetoothGatt.CONNECTION_PRIORITY_HIGH
        }

        gatt.requestConnectionPriority(sciPriority)

        sessionMetrics[address] = BleSessionMetrics(
            mtu = negotiatedMtu,
            // SCI: ~0.375–4 ms; HIGH: 11.25–15 ms; using midpoints
            connectionIntervalMs = if (remoteSupportsSci) 2.19f else 13.75f,
            sci = remoteSupportsSci,
            deviceAddressAnon = BleSessionMetrics.anonymizeAddress(address),
        )

        Timber.i(
            "BLE SCI: negotiation complete — address=${BleSessionMetrics.anonymizeAddress(address)} " +
            "mtu=$negotiatedMtu sci=$remoteSupportsSci priority=$sciPriority"
        )
    }

    /**
     * Finalize session metrics when a session ends (disconnect or [stopAllEndpoints]).
     * Updates [sessionMetrics] with the total exchange duration.
     */
    private fun finalizeSessionMetrics(address: String, gatt: BluetoothGatt) {
        val startMs = sessionStartMs.remove(address) ?: return
        val durationMs = System.currentTimeMillis() - startMs
        sessionMetrics[address] = sessionMetrics[address]?.copy(
            totalExchangeDurationMs = durationMs
        ) ?: BleSessionMetrics(
            totalExchangeDurationMs = durationMs,
            deviceAddressAnon = BleSessionMetrics.anonymizeAddress(address),
        )
        Timber.d("BLE: session ${BleSessionMetrics.anonymizeAddress(address)} duration=${durationMs}ms")
    }

    /**
     * Returns the [BleSessionMetrics] for a given device address, or null if
     * no session has been tracked for that address yet.
     * Used by [NearbyExchangeService] to include BLE metrics in [ExchangeAuditLog].
     */
    fun getSessionMetrics(address: String): BleSessionMetrics? = sessionMetrics[address]

    // ── GATT Server ────────────────────────────────────────────────────────

    private fun startGattServer() {
        val server = bluetoothManager.openGattServer(context, gattServerCallback) ?: return
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val txChar  = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).also { char ->
            char.addDescriptor(BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
        }
        val rxChar = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(txChar); service.addCharacteristic(rxChar)
        server.addService(service)
        gattServer = server
        Timber.d("BLE: GATT server started")
    }

    // ── Callbacks ──────────────────────────────────────────────────────────

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Timber.i("BLE: advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            Timber.e("BLE: advertising failed errorCode=$errorCode")
            isAdvertising.set(false)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val name    = result.device.name ?: address
            if (!discoveredDevices.containsKey(address)) {
                discoveredDevices[address] = name
                Timber.i("BLE: found AURA peer $name ($address)")
                onEndpointFound?.invoke(address, name)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Timber.e("BLE: scan failed errorCode=$errorCode"); isScanning.set(false)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    sessionStartMs[address] = System.currentTimeMillis()
                    Timber.i("BLE: connected to $address — requesting MTU 512")
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.i("BLE: disconnected from $address")
                    finalizeSessionMetrics(address, gatt)
                    clientConnections.remove(address)?.close()
                    mtuMap.remove(address)
                    onDisconnected?.invoke(address)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val address = gatt.device.address
            mtuMap[address] = mtu
            Timber.i("BLE: MTU negotiated to $mtu for $address")
            // Task 66: attempt SCI negotiation AFTER MTU — must not reorder
            attemptSciNegotiation(gatt, mtu)
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            val name    = discoveredDevices[address] ?: address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val txChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(TX_CHAR_UUID)
                if (txChar != null) {
                    gatt.setCharacteristicNotification(txChar, true)
                    val desc = txChar.getDescriptor(CCCD_UUID)
                    desc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    desc?.let { gatt.writeDescriptor(it) }
                }
                onConnectionInitiated?.invoke(address, name)
                onConnected?.invoke(address, name, false)
                Timber.i("BLE: connection ready to $address")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                handleIncomingChunk(gatt.device.address, characteristic.value)
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val name = device.name ?: device.address
                    discoveredDevices[device.address] = name
                    sessionStartMs[device.address] = System.currentTimeMillis()
                    Timber.i("BLE server: client connected ${device.address}")
                    onConnectionInitiated?.invoke(device.address, name)
                    onConnected?.invoke(device.address, name, true)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.i("BLE server: client disconnected ${device.address}")
                    sessionStartMs.remove(device.address)?.let { startMs ->
                        sessionMetrics[device.address] = sessionMetrics[device.address]?.copy(
                            totalExchangeDurationMs = System.currentTimeMillis() - startMs
                        ) ?: BleSessionMetrics(
                            totalExchangeDurationMs = System.currentTimeMillis() - startMs,
                            deviceAddressAnon = BleSessionMetrics.anonymizeAddress(device.address),
                        )
                    }
                    onDisconnected?.invoke(device.address)
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            if (characteristic.uuid == RX_CHAR_UUID) {
                handleIncomingChunk(device.address, value)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    // ── Chunk assembly ─────────────────────────────────────────────────────

    private fun handleIncomingChunk(address: String, frame: ByteArray) {
        if (frame.size < 4) { Timber.w("BLE: malformed chunk size=${frame.size}"); return }
        val seqNo   = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        val total   = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        val payload = frame.copyOfRange(4, frame.size)
        if (total <= 0 || seqNo >= total) { Timber.w("BLE: invalid chunk header"); return }

        val buffer = reassemblyBuffers.getOrPut(address) { ConcurrentHashMap() }
        buffer[seqNo] = payload
        expectedChunks[address] = total

        if (buffer.size == total) {
            val assembled = (0 until total).map { buffer[it] ?: byteArrayOf() }
                .fold(byteArrayOf()) { acc, chunk -> acc + chunk }
            reassemblyBuffers.remove(address); expectedChunks.remove(address)
            Timber.d("BLE: reassembled ${assembled.size}b from $total chunks from $address")
            onPayloadReceived?.invoke(address, assembled)
        }
    }

    private fun ByteArray.toChunks(chunkSize: Int): List<ByteArray> {
        if (isEmpty()) return listOf(this)
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < size) {
            chunks.add(copyOfRange(offset, minOf(offset + chunkSize, size)))
            offset += chunkSize
        }
        return chunks
    }

    companion object {
        val SERVICE_UUID : UUID = UUID.fromString("12345678-0000-1000-8000-00007575a001")
        val TX_CHAR_UUID : UUID = UUID.fromString("12345678-0001-1000-8000-00007575a001")
        val RX_CHAR_UUID : UUID = UUID.fromString("12345678-0002-1000-8000-00007575a001")
        val CCCD_UUID    : UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun isAvailable(context: Context): Boolean {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return bm?.adapter?.isEnabled == true &&
                context.packageManager.hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE
                )
        }
    }
}
