package com.campusmesh.ui.debug

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.campusmesh.db.PeerEntity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.ble.DiscoveryBlock
import com.campusmesh.ble.NearbyPeer
import com.campusmesh.ble.RadioOpState
import com.campusmesh.mesh.MeshState
import com.campusmesh.permissions.PermissionGrantState
import com.campusmesh.permissions.PermissionStatus
import com.campusmesh.permissions.RequiredPermissions
import com.campusmesh.platform.DeviceStatus
import com.campusmesh.transport.DirectTransportSnapshot
import com.campusmesh.transport.TransportConnectionState
import com.campusmesh.ui.theme.CampusDanger
import com.campusmesh.ui.theme.CampusOk
import com.campusmesh.ui.theme.CampusWarning

@Composable
fun DebugStatusRoute(
    onNavigateToChat: (String, String) -> Unit,
    viewModel: DebugStatusViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refresh()
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refresh()
    }
    val locationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refresh()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DebugStatusScreen(
        state = uiState,
        onRefresh = viewModel::refresh,
        onRequestPermissions = {
            permissionLauncher.launch(RequiredPermissions.runtimeRequestNames(Build.VERSION.SDK_INT))
        },
        onEnableBluetooth = {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        },
        onOpenLocationSettings = {
            locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        },
        onStartDiscovery = viewModel::startDiscovery,
        onStopDiscovery = viewModel::stopDiscovery,
        onConnectPeer = { address, nodeId, label -> viewModel.connectToPeer(address, nodeId, label) },
        onSendMessage = viewModel::sendMessage,
        onDisconnectTransport = viewModel::disconnectTransport,
        onNavigateToChat = onNavigateToChat,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugStatusScreen(
    state: DebugStatusUiState,
    onRefresh: () -> Unit,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onConnectPeer: (String, String, String) -> Unit,
    onSendMessage: (String) -> Unit,
    onDisconnectTransport: () -> Unit,
    onNavigateToChat: (String, String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CampusMesh debug") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BannerCard(
                title = state.phaseLabel,
                body = "Full store-and-forward mesh diagnostic metrics tracking live peer connections and packets.",
            )

            DiagnosticMetricsCard(state = state)

            NearbyPeersCard(
                peers = state.discovery.peers,
                nowEpochMs = state.discovery.nowEpochMs,
                onConnectPeer = onConnectPeer,
                onNavigateToChat = onNavigateToChat,
            )

            DirectTransportCard(
                transport = state.directTransport,
                onSendMessage = onSendMessage,
                onDisconnect = onDisconnectTransport,
            )

            KnownPeersCard(
                peers = state.knownPeers,
                onNavigateToChat = onNavigateToChat
            )

            StartChatByNodeIdCard(
                onNavigateToChat = onNavigateToChat
            )

            DiscoveryCard(state = state)

            StatusCard(title = "This phone") {
                StatusRow("Node ID", state.discovery.localNodeLabel)
                StatusRow("Full ID", state.discovery.localNodeId.toString())
                StatusRow("Version", state.device.applicationVersionName)
                StatusRow("Manufacturer", state.device.manufacturer)
                StatusRow("Model", state.device.model)
                StatusRow("Android", " (API )")
            }

            BluetoothCard(device = state.device)

            PermissionsCard(permissions = state.permissions)

            MeshCard(meshState = state.meshState)

            ActionButtons(
                state = state,
                onRefresh = onRefresh,
                onRequestPermissions = onRequestPermissions,
                onEnableBluetooth = onEnableBluetooth,
                onOpenLocationSettings = onOpenLocationSettings,
                onStartDiscovery = onStartDiscovery,
                onStopDiscovery = onStopDiscovery,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NearbyPeersCard(
    peers: List<NearbyPeer>,
    nowEpochMs: Long,
    onConnectPeer: (String, String, String) -> Unit,
    onNavigateToChat: (String, String) -> Unit,
) {
    StatusCard(title = "Nearby CampusMesh devices") {
        if (peers.isEmpty()) {
            Text(
                text = "None seen yet. Keep this screen open on both phones with Bluetooth on and permissions granted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "No nearby CampusMesh devices" },
            )
        } else {
            peers.forEach { peer ->
                PeerRow(
                    peer = peer,
                    nowEpochMs = nowEpochMs,
                    onConnect = { onConnectPeer(peer.deviceAddress, peer.nodeId.toString(), peer.shortLabel) },
                    onChat = { onNavigateToChat(peer.nodeId.toString(), peer.shortLabel) },
                )
            }
        }
    }
}

@Composable
private fun PeerRow(
    peer: NearbyPeer,
    nowEpochMs: Long,
    onConnect: () -> Unit,
    onChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = "Nearby peer " },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(peer.shortLabel, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "RSSI  dBm ()",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Last seen ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onConnect,
                modifier = Modifier.heightIn(min = 36.dp),
            ) {
                Text("Connect")
            }
            Button(
                onClick = onChat,
                modifier = Modifier.heightIn(min = 36.dp),
            ) {
                Text("Chat")
            }
        }
    }
}

@Composable
private fun DirectTransportCard(
    transport: DirectTransportSnapshot,
    onSendMessage: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var textInput by remember { mutableStateOf("") }

    StatusCard(title = "Direct Transport & Chat") {
        StatusRow("Connection", connectionStateLabel(transport.connectionState))
        StatusRow("Relay Packets Queue", "${transport.relayPackets.size} packets")
        transport.peerLabel?.let { label ->
            StatusRow("Connected Peer", "$label (${transport.peerAddress})")
        }
        StatusRow("Bytes Sent", "${transport.bytesSent} bytes")
        StatusRow("Bytes Received", "${transport.bytesReceived} bytes")
        StatusRow("Last Received Message", transport.lastReceivedMessage ?: "(none)")

        transport.lastError?.let { error ->
            Text(error, style = MaterialTheme.typography.bodyMedium, color = CampusDanger)
        }

        if (transport.connectionState == TransportConnectionState.Connected) {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                label = { Text("Message (e.g. hello)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            onSendMessage(textInput)
                            textInput = ""
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Send message")
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Disconnect")
                }
            }
        }

        if (transport.persistedMessages.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Persisted Chat History (Room):", style = MaterialTheme.typography.labelLarge)
            transport.persistedMessages.take(10).forEach { msg ->
                Text("[] ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DiscoveryCard(state: DebugStatusUiState) {
    StatusCard(title = "Discovery") {
        StatusRow("Scan", radioLabel(state.discovery.scan))
        Text(state.discovery.scanDetail, style = MaterialTheme.typography.bodyMedium, color = detailColor(state.discovery.scan))
        StatusRow("Advertise", radioLabel(state.discovery.advertise))
        Text(state.discovery.advertiseDetail, style = MaterialTheme.typography.bodyMedium, color = detailColor(state.discovery.advertise))
        StatusRow("Wanted", if (state.discovery.wantedRunning) "Running while this screen is visible" else "Stopped")
        if (state.discovery.blocks.isNotEmpty()) {
            Text(
                text = state.discovery.blocks.joinToString { blockLabel(it) },
                style = MaterialTheme.typography.bodyMedium,
                color = CampusWarning,
            )
        }
        state.discovery.lastError?.let { error ->
            Text(error, style = MaterialTheme.typography.bodyMedium, color = CampusDanger)
        }
    }
}

@Composable
private fun ActionButtons(
    state: DebugStatusUiState,
    onRefresh: () -> Unit,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
) {
    val needsBluetooth = state.device.bluetoothEnabled == false ||
        DiscoveryBlock.BluetoothOff in state.discovery.blocks
    val needsLocation = Build.VERSION.SDK_INT <= 30 &&
        (!state.locationEnabled || DiscoveryBlock.LocationOff in state.discovery.blocks)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Refresh status" },
            ) {
                Text("Refresh")
            }
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Request runtime permissions" },
            ) {
                Text("Request permissions")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.discovery.wantedRunning) {
                OutlinedButton(
                    onClick = onStopDiscovery,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Stop discovery" },
                ) {
                    Text("Stop discovery")
                }
            } else {
                Button(
                    onClick = onStartDiscovery,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Start discovery" },
                ) {
                    Text("Start discovery")
                }
            }
            if (needsBluetooth) {
                Button(
                    onClick = onEnableBluetooth,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Enable Bluetooth" },
                ) {
                    Text("Enable Bluetooth")
                }
            }
        }
        if (needsLocation) {
            OutlinedButton(
                onClick = onOpenLocationSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Open location settings" },
            ) {
                Text("Turn on location (required to scan on Android 8–11)")
            }
        }
    }
}

@Composable
private fun BannerCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DiagnosticMetricsCard(state: DebugStatusUiState) {
    StatusCard(title = "Diagnostic Metrics") {
        StatusRow("Device ID", if (state.deviceId.isNotBlank()) state.deviceId else state.discovery.localNodeLabel)
        StatusRow("Nearby Nodes", state.nearbyNodes.toString())
        StatusRow("Active Connections", state.activeConnections.toString())
        StatusRow("Packets Stored", state.packetsStored.toString())
        StatusRow("Packets Forwarded", state.packetsForwarded.toString())
        StatusRow("Duplicates Blocked", state.duplicatesBlocked.toString())
        StatusRow("Messages Delivered", state.messagesDelivered.toString())
        StatusRow("BLE Status", state.bleStatus)
        StatusRow("Scan Status", state.scanStatus)
        StatusRow("Advertise Status", state.advertiseStatus)
        StatusRow("Routing Activity", state.routingActivity)
    }
}


@Composable
private fun StatusCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BluetoothCard(device: DeviceStatus) {
    StatusCard(title = "Bluetooth adapter") {
        StatusRow(
            label = "Adapter present",
            value = if (device.bluetoothAdapterPresent) "Yes" else "No",
        )
        StatusRow(
            label = "Bluetooth enabled",
            value = when {
                !device.bluetoothAdapterPresent -> "Unknown (no adapter)"
                device.bluetoothEnabled == null -> "Unknown (permission required)"
                device.bluetoothEnabled -> "Enabled"
                else -> "Disabled"
            },
        )
        device.bluetoothUnsupportedReason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = CampusWarning,
            )
        }
    }
}

@Composable
private fun PermissionsCard(permissions: List<PermissionStatus>) {
    StatusCard(title = "Permissions") {
        permissions.forEach { status ->
            PermissionRow(status)
        }
    }
}

@Composable
private fun PermissionRow(status: PermissionStatus) {
    val (label, color) = when (status.state) {
        PermissionGrantState.Granted -> "Granted" to CampusOk
        PermissionGrantState.Denied -> "Denied" to CampusDanger
        PermissionGrantState.NotRequiredOnThisApi -> "Not required on this Android version" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(status.permission.title, style = MaterialTheme.typography.bodyLarge)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
        if (status.state != PermissionGrantState.NotRequiredOnThisApi) {
            Text(
                status.permission.rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MeshCard(meshState: MeshState) {
    StatusCard(title = "Mesh") {
        Text(
            text = when (meshState) {
                MeshState.NotStarted -> "Not Started"
                MeshState.Active -> "Active (Store & Forward)"
            },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { contentDescription = "Mesh state ${meshState.name}" },
        )
        Text(
            text = when (meshState) {
                MeshState.NotStarted -> "Basic chat experience active. Routing and mesh forwarding are still not started."
                MeshState.Active -> "Store-and-forward relaying is active. Packets will be carried and forwarded to peers."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun detailColor(state: RadioOpState) = when (state) {
    RadioOpState.Running -> CampusOk
    RadioOpState.Failed, RadioOpState.Blocked -> CampusWarning
    RadioOpState.Idle, RadioOpState.Starting -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun radioLabel(state: RadioOpState): String = when (state) {
    RadioOpState.Idle -> "Idle"
    RadioOpState.Starting -> "Starting"
    RadioOpState.Running -> "Running"
    RadioOpState.Blocked -> "Blocked"
    RadioOpState.Failed -> "Failed"
}

private fun connectionStateLabel(state: TransportConnectionState): String = when (state) {
    TransportConnectionState.Disconnected -> "Disconnected"
    TransportConnectionState.Connecting -> "Connecting..."
    TransportConnectionState.Connected -> "Connected"
    TransportConnectionState.Failed -> "Failed"
}

private fun blockLabel(block: DiscoveryBlock): String = when (block) {
    DiscoveryBlock.BluetoothHardwareMissing -> "No Bluetooth hardware"
    DiscoveryBlock.BleHardwareMissing -> "No BLE hardware"
    DiscoveryBlock.AdapterMissing -> "No Bluetooth adapter"
    DiscoveryBlock.AdvertiserMissing -> "No BLE advertiser"
    DiscoveryBlock.BluetoothOff -> "Bluetooth off"
    DiscoveryBlock.BluetoothStateUnknown -> "Bluetooth state unknown"
    DiscoveryBlock.MissingScanPermission -> "Scan permission missing"
    DiscoveryBlock.MissingAdvertisePermission -> "Advertise permission missing"
    DiscoveryBlock.LocationOff -> "Location off"
}

private fun rssiQuality(rssi: Int): String = when {
    rssi >= -60 -> "strong"
    rssi >= -80 -> "moderate"
    else -> "weak"
}

private fun formatAge(ageMs: Long): String {
    val seconds = (ageMs / 1000).coerceAtLeast(0)
    return when {
        seconds < 2 -> "just now"
        seconds < 60 -> "s ago"
        else -> "m ago"
    }
}

@Composable
private fun KnownPeersCard(
    peers: List<PeerEntity>,
    onNavigateToChat: (String, String) -> Unit,
) {
    StatusCard(title = "Known Mesh Peers (History)") {
        if (peers.isEmpty()) {
            Text(
                text = "No historical peers saved in database yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            peers.forEach { peer ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        val shortLabel = peer.nodeId.take(8).uppercase()
                        Text(shortLabel, style = MaterialTheme.typography.titleMedium)
                        Text("Address: ${peer.deviceAddress}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = { onNavigateToChat(peer.nodeId, peer.nodeId.take(8).uppercase()) },
                        modifier = Modifier.heightIn(min = 36.dp),
                    ) {
                        Text("Chat")
                    }
                }
            }
        }
    }
}

@Composable
private fun StartChatByNodeIdCard(
    onNavigateToChat: (String, String) -> Unit,
) {
    var nodeIdInput by remember { mutableStateOf("") }

    StatusCard(title = "Start Chat by Node ID") {
        OutlinedTextField(
            value = nodeIdInput,
            onValueChange = { nodeIdInput = it },
            label = { Text("Node ID (UUID or 8-char short ID)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = {
                if (nodeIdInput.isNotBlank()) {
                    val label = if (nodeIdInput.length >= 8) nodeIdInput.take(8).uppercase() else nodeIdInput
                    onNavigateToChat(nodeIdInput, label)
                    nodeIdInput = ""
                }
            },
            enabled = nodeIdInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Chat")
        }
    }
}
