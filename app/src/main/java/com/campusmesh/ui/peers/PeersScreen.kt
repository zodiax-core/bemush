package com.campusmesh.ui.peers

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.ble.NearbyPeer
import com.campusmesh.db.PeerEntity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersRoute(
    onNavigateToChat: (String, String) -> Unit,
    onBackClick: () -> Unit,
    viewModel: PeersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PeersScreen(
        state = state,
        onBackClick = onBackClick,
        onChatWithNearby = { peer ->
            val label = state.resolveLabel(peer.nodeId.toString(), peer.shortLabel)
            viewModel.connectAndSync(peer.deviceAddress, peer.nodeId.toString(), label)
            onNavigateToChat(peer.nodeId.toString(), label)
        },
        onChatWithKnown = { peer ->
            val label = peer.displayName ?: peer.nodeId.take(8).uppercase()
            viewModel.connectAndSync(peer.deviceAddress, peer.nodeId, label)
            onNavigateToChat(peer.nodeId, label)
        },
        onSyncWithNearby = { peer ->
            val label = state.resolveLabel(peer.nodeId.toString(), peer.shortLabel)
            viewModel.connectAndSync(peer.deviceAddress, peer.nodeId.toString(), label)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(
    state: PeersUiState,
    onBackClick: () -> Unit,
    onChatWithNearby: (NearbyPeer) -> Unit,
    onChatWithKnown: (PeerEntity) -> Unit,
    onSyncWithNearby: (NearbyPeer) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find Peers", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isDiscoveryActive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Your node ID ──────────────────────────────────────────
            item {
                ListItem(
                    leadingContent = {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    },
                    headlineContent = { Text("You", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("ID: ${state.localNodeId.take(8).uppercase()}", style = MaterialTheme.typography.labelSmall) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                )
                HorizontalDivider()
            }

            // ── Live nearby ──────────────────────────────────────────
            item {
                SectionHeader(
                    text = "Live Nearby (${state.nearbyPeers.size})",
                    subtitle = "CampusMesh devices in Bluetooth range right now",
                )
            }

            if (state.nearbyPeers.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Searching… keep Bluetooth turned on to connect automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            items(state.nearbyPeers, key = { it.nodeId }) { peer ->
                val label = state.resolveLabel(peer.nodeId.toString(), peer.shortLabel)
                val peerEntity = state.allPeers.find { it.nodeId == peer.nodeId.toString() }
                val ageMs = state.nowEpochMs - peer.lastSeenEpochMs
                val ageLabel = when {
                    ageMs < 2_000 -> "just now"
                    ageMs < 60_000 -> "${ageMs / 1_000}s ago"
                    else -> "${ageMs / 60_000}m ago"
                }
                val rssiQuality = when {
                    peer.rssiDbm >= -60 -> "Strong"
                    peer.rssiDbm >= -80 -> "Moderate"
                    else -> "Weak"
                }
                LivePeerRow(
                    label = label,
                    nodeUid = peer.nodeId.toString().take(8).uppercase(),
                    avatarPath = peerEntity?.avatarPath,
                    subtitle = "RSSI ${peer.rssiDbm} dBm ($rssiQuality) · $ageLabel",
                    onChat = { onChatWithNearby(peer) },
                    onSync = { onSyncWithNearby(peer) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }

            // ── Previously seen ───────────────────────────────────────
            if (state.knownPeers.isNotEmpty()) {
                item {
                    SectionHeader(
                        text = "Previously Seen (${state.knownPeers.size})",
                        subtitle = "Out of range — messages queued for next encounter",
                    )
                }
                items(state.knownPeers, key = { "known_${it.nodeId}" }) { peer ->
                    val label = peer.displayName ?: peer.nodeId.take(8).uppercase()
                    KnownPeerRow(
                        label = label,
                        nodeUid = peer.nodeId.take(8).uppercase(),
                        avatarPath = peer.avatarPath,
                        subtitle = "ID: ${peer.nodeId.take(8).uppercase()}",
                        onChat = { onChatWithKnown(peer) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String, subtitle: String) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LivePeerRow(
    label: String,
    nodeUid: String,
    avatarPath: String?,
    subtitle: String,
    onChat: () -> Unit,
    onSync: () -> Unit,
) {
    val avatarBitmap = remember(avatarPath) {
        avatarPath?.let { path ->
            val file = File(path)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
        }
    }

    ListItem(
        leadingContent = {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarBitmap != null) {
                    Image(
                        bitmap = avatarBitmap,
                        contentDescription = "Avatar",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(label.take(2).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
        headlineContent = {
            Column {
                Text(label, fontWeight = FontWeight.Bold)
                Text("UID: $nodeUid", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.labelSmall) },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSync,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sync", style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = onChat,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("Chat", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
    )
}

@Composable
private fun KnownPeerRow(
    label: String,
    nodeUid: String,
    avatarPath: String?,
    subtitle: String,
    onChat: () -> Unit,
) {
    val avatarBitmap = remember(avatarPath) {
        avatarPath?.let { path ->
            val file = File(path)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
        }
    }

    ListItem(
        leadingContent = {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarBitmap != null) {
                    Image(
                        bitmap = avatarBitmap,
                        contentDescription = "Avatar",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(label.take(2).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        headlineContent = {
            Column {
                Text(label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("UID: $nodeUid", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.labelSmall) },
        trailingContent = {
            TextButton(onClick = onChat) { Text("Chat") }
        },
    )
}
