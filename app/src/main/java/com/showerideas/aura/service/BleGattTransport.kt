package com.showerideas.aura.service

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Task 7 — BLE GATT direct transport implementing [NearbyTransport].
 *
 * ## Architecture
 * - AURA GATT service UUID: [SERVICE_UUID]
 * - TX characteristic ([TX_CHAR_UUID]): server notifies → client reads
 * - RX characteristic ([RX_CHAR_UUID]): client writes → server receives
 * - On connect: client calls [requestMtu(512)], waits for [onMtuChanged], then discovers services
 * - Data framing: [4-byte header: 2-byte seqNo + 2-byte totalChunks] + payload chunk
 *
 * ## Usage
 * ```kotlin
 * val transport = BleGattTransport(context)
 * transport.onEndpointFound = { addr, name -> transport.requestConnection("", addr) }
 * transport.onConnected     = { addr, name, _ -> /* start handshake */ }
 * transport.onPayloadReceived = { addr, bytes -> /* handle message */ }
 * transport.startAdvertising("my-device", "aura")
 * transport.startDiscovery("aura")
 * // ...
 * transport.stopAllEndpoints()
 * ```
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
        clientConnections.forEach { (_, gatt) -> gatt.disconnect(); gatt.close() }
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
                    Timber.i("BLE: connected to $address — requesting MTU 512")
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.i("BLE: disconnected from $address")
                    clientConnections.remove(address)?.close()
                    mtuMap.remove(address)
                    onDisconnected?.invoke(address)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtuMap[gatt.device.address] = mtu
            Timber.i("BLE: MTU negotiated to $mtu for ${gatt.device.address}")
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
                    Timber.i("BLE server: client connected ${device.address}")
                    onConnectionInitiated?.invoke(device.address, name)
                    onConnected?.invoke(device.address, name, true)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.i("BLE server: client disconnected ${device.address}")
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
