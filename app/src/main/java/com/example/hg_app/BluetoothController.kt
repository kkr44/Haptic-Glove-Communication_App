package com.example.hg_app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

@SuppressLint("MissingPermission") // Permissions are checked in the UI layer before calling
class BluetoothController(private val context: Context) {

    // Universal Serial Port Profile (SPP) UUID required for HC-05 modules
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val bluetoothManager by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    private var activeSocket: BluetoothSocket? = null

    private val _scannedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDeviceDomain>> = _scannedDevices.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDeviceDomain>> = _pairedDevices.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    // BroadcastReceiver to catch nearby Bluetooth devices during discovery
    private val foundDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

                device?.let { btDevice ->
                    val domainDevice = BluetoothDeviceDomain(
                        name = btDevice.name ?: "Unknown Device",
                        address = btDevice.address,
                        isPaired = btDevice.bondState == BluetoothDevice.BOND_BONDED
                    )
                    _scannedDevices.update { currentList ->
                        if (currentList.none { it.address == domainDevice.address }) {
                            currentList + domainDevice
                        } else currentList
                    }
                }
            }
        }
    }

    fun startDiscovery() {
        updatePairedDevices()
        _scannedDevices.value = emptyList()

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        context.registerReceiver(foundDeviceReceiver, filter)

        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        bluetoothAdapter?.startDiscovery()
    }

    fun stopDiscovery() {
        try {
            bluetoothAdapter?.cancelDiscovery()
            context.unregisterReceiver(foundDeviceReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already unregistered
        }
    }

    fun updatePairedDevices() {
        val bonded = bluetoothAdapter?.bondedDevices?.map { device ->
            BluetoothDeviceDomain(
                name = device.name ?: "Unnamed Device",
                address = device.address,
                isPaired = true
            )
        } ?: emptyList()
        _pairedDevices.value = bonded
    }

    /**
     * Connects to the target MAC address and yields incoming strings as a cold Flow.
     * Note: If the HC-05 wasn't paired beforehand, Android will prompt the user for the PIN (1234).
     */
    fun connectAndListen(address: String): Flow<String> = flow {
        stopDiscovery()
        _connectionError.value = null

        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            _connectionError.value = "Device not found."
            return@flow
        }

        try {
            val socket = device.createRfcommSocketToServiceRecord(sppUuid)
            activeSocket = socket
            socket.connect() // Blocks until connected or throws IOException
            _isConnected.value = true

            val inputStream = socket.inputStream
            val buffer = ByteArray(1024)
            val lineAccumulator = StringBuilder()

            while (_isConnected.value) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    val chunk = String(buffer, 0, bytesRead, Charsets.UTF_8)
                    lineAccumulator.append(chunk)

                    // Emit complete lines terminated by '\n' so partial UART reads stay intact
                    var newlineIndex = lineAccumulator.indexOf("\n")
                    while (newlineIndex != -1) {
                        val completeLine = lineAccumulator.substring(0, newlineIndex).trim()
                        if (completeLine.isNotEmpty()) {
                            emit(completeLine)
                        }
                        lineAccumulator.delete(0, newlineIndex + 1)
                        newlineIndex = lineAccumulator.indexOf("\n")
                    }
                }
            }
        } catch (e: IOException) {
            _connectionError.value = "Connection lost or failed: ${e.localizedMessage}"
        } finally {
            disconnect()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun sendMessage(message: String, appendNewline: Boolean = true): Boolean {
        return withContext(Dispatchers.IO) {
            val socket = activeSocket ?: return@withContext false
            if (!socket.isConnected) return@withContext false

            try {
                val payload = if (appendNewline) "$message\n" else message
                socket.outputStream.write(payload.toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                true
            } catch (e: IOException) {
                _connectionError.value = "Failed to send: ${e.localizedMessage}"
                disconnect()
                false
            }
        }
    }

    fun disconnect() {
        _isConnected.value = false
        try {
            activeSocket?.close()
        } catch (_: IOException) {
        } finally {
            activeSocket = null
        }
    }
}