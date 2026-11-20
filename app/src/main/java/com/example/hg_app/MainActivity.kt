package com.example.hg_app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class TerminalMessage(
    val text: String,
    val isSentByPhone: Boolean
)

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothController: BluetoothController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bluetoothController = BluetoothController(applicationContext)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HapticBluetoothApp(bluetoothController)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothController.stopDiscovery()
        bluetoothController.disconnect()
    }
}

@Composable
fun HapticBluetoothApp(controller: BluetoothController) {
    var permissionsGranted by remember { mutableStateOf(false) }

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            controller.updatePairedDevices()
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(requiredPermissions)
    }

    if (!permissionsGranted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Bluetooth permissions are required to scan and connect to your HC-05 module.")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                Text("Grant Permissions")
            }
        }
        return
    }

    val isConnected by controller.isConnected.collectAsState()
    val pairedDevices by controller.pairedDevices.collectAsState()
    val scannedDevices by controller.scannedDevices.collectAsState()
    val connectionError by controller.connectionError.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    var connectionJob by remember { mutableStateOf<Job?>(null) }
    var connectedDeviceName by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<TerminalMessage>() }

    if (isConnected) {
        TerminalScreen(
            deviceName = connectedDeviceName,
            messages = messages,
            onSendMessage = { text ->
                coroutineScope.launch {
                    val success = controller.sendMessage(text, appendNewline = true)
                    if (success) {
                        messages.add(TerminalMessage(text = text, isSentByPhone = true))
                    }
                }
            },
            onDisconnect = {
                connectionJob?.cancel()
                controller.disconnect()
                messages.clear()
            }
        )
    } else {
        DeviceScannerScreen(
            pairedDevices = pairedDevices,
            scannedDevices = scannedDevices,
            errorMessage = connectionError,
            onStartScan = { controller.startDiscovery() },
            onStopScan = { controller.stopDiscovery() },
            onDeviceClick = { device ->
                connectedDeviceName = device.name
                messages.clear()
                connectionJob?.cancel()
                connectionJob = coroutineScope.launch {
                    controller.connectAndListen(device.address).collect { incomingText ->
                        messages.add(TerminalMessage(text = incomingText, isSentByPhone = false))
                    }
                }
            }
        )
    }
}

@Composable
fun DeviceScannerScreen(
    pairedDevices: List<BluetoothDeviceDomain>,
    scannedDevices: List<BluetoothDeviceDomain>,
    errorMessage: String?,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceClick: (BluetoothDeviceDomain) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Bluetooth Devices",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStartScan) {
                Text("Scan Nearby")
            }
            OutlinedButton(onClick = onStopScan) {
                Text("Stop Scan")
            }
        }

        errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = error, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = "PAIRED DEVICES",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            items(pairedDevices) { device ->
                DeviceRow(device = device, onClick = { onDeviceClick(device) })
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "DISCOVERED DEVICES",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            items(scannedDevices) { device ->
                DeviceRow(device = device, onClick = { onDeviceClick(device) })
            }
        }
    }
}

@Composable
fun DeviceRow(device: BluetoothDeviceDomain, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = device.name, fontWeight = FontWeight.SemiBold)
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun TerminalScreen(
    deviceName: String,
    messages: List<TerminalMessage>,
    onSendMessage: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to newest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding() // Adjusts layout cleanly when the phone keyboard opens
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "Connected", style = MaterialTheme.typography.labelMedium, color = Color(0xFF2E7D32))
                Text(text = deviceName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Disconnect")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Message History Log
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                val alignment = if (msg.isSentByPhone) Alignment.End else Alignment.Start
                val prefix = if (msg.isSentByPhone) "TX → " else "RX ← "
                val containerColor = if (msg.isSentByPhone) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }

                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
                    Surface(
                        color = containerColor,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            text = prefix + msg.text,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Keyboard Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Enter command...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputText.isNotBlank()) {
                            onSendMessage(inputText)
                            inputText = ""
                        }
                    }
                )
            )
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSendMessage(inputText)
                        inputText = ""
                    }
                }
            ) {
                Text("Send")
            }
        }
    }
}