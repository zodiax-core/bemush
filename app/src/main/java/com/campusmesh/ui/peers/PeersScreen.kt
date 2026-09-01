package com.campusmesh.ui.peers

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.ble.NearbyPeer
import com.campusmesh.db.PeerEntity
import com.campusmesh.ui.theme.AppTheme
import com.campusmesh.ui.theme.LocalAppTheme
import com.campusmesh.ui.theme.PixelCyan
import com.campusmesh.ui.theme.PixelMagenta
import com.campusmesh.ui.theme.PixelSurfaceVariant
import com.campusmesh.ui.theme.PixelYellow
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
    val appTheme = LocalAppTheme.current
    val isPixel = (appTheme == AppTheme.PIXEL_8BIT)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isPixel) "🕹️ FIND PEERS" else "Find Peers",
                        fontWeight = FontWeight.Bold,
                        fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                        color = if (isPixel) PixelYellow else MaterialTheme.colorScheme.onSurface,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isDiscoveryActive) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = if (isPixel) PixelYellow else MaterialTheme.colorScheme.primary,
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
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isPixel) PixelYellow else MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = if (isPixel) Color.Black else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    },
                    headlineContent = {
                        Text(
                            "You (This Node)",
                            fontWeight = FontWeight.Bold,
                            fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                            color = if (isPixel) PixelYellow else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    supportingContent = {
                        Text(
                            "ID: ${state.localNodeId.take(8).uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (isPixel) Color(0xFF161626) else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
                HorizontalDivider(color = if (isPixel) PixelMagenta else MaterialTheme.colorScheme.outlineVariant)
            }

            // ── Live nearby ──────────────────────────────────────────
            item {
                SectionHeader(
                    text = if (isPixel) "LIVE NEARBY (${state.nearbyPeers.size})" else "Live Nearby (${state.nearbyPeers.size})",
                    subtitle = "CampusMesh devices in Bluetooth range right now",
                    isPixel = isPixel,
                )
            }

            if (state.nearbyPeers.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Searching for nearby BLE devices… keep Bluetooth turned on.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isPixel) PixelCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp),
                            fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
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
                    isPixel = isPixel,
                    onChat = { onChatWithNearby(peer) },
                    onSync = { onSyncWithNearby(peer) },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = if (isPixel) PixelMagenta.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
                )
            }

            // ── Previously seen ───────────────────────────────────────
            if (state.knownPeers.isNotEmpty()) {
                item {
                    SectionHeader(
                        text = if (isPixel) "PREVIOUSLY SEEN (${state.knownPeers.size})" else "Previously Seen (${state.knownPeers.size})",
                        subtitle = "Out of range — messages queued for next encounter",
                        isPixel = isPixel,
                    )
                }
                items(state.knownPeers, key = { "known_${it.nodeId}" }) { peer ->
                    val label = peer.displayName ?: peer.nodeId.take(8).uppercase()
                    KnownPeerRow(
                        label = label,
                        nodeUid = peer.nodeId.take(8).uppercase(),
                        avatarPath = peer.avatarPath,
                        subtitle = "ID: ${peer.nodeId.take(8).uppercase()}",
                        isPixel = isPixel,
                        onChat = { onChatWithKnown(peer) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = if (isPixel) PixelMagenta.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String, subtitle: String, isPixel: Boolean = false) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isPixel) PixelYellow else MaterialTheme.colorScheme.primary,
            fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = if (isPixel) PixelCyan else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun LivePeerRow(
    label: String,
    nodeUid: String,
    avatarPath: String?,
    subtitle: String,
    isPixel: Boolean,
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
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isPixel) PixelYellow else MaterialTheme.colorScheme.primaryContainer)
                    .border(
                        width = if (isPixel) 2.dp else 0.dp,
                        color = if (isPixel) PixelCyan else Color.Transparent,
                        shape = CircleShape,
                    ),
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
                    Text(
                        text = label.take(2).uppercase(),
                        fontWeight = FontWeight.Bold,
                        fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                        color = if (isPixel) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        headlineContent = {
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = "ID: $nodeUid",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPixel) PixelCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                )
            }
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onSync) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Sync",
                        tint = if (isPixel) PixelYellow else MaterialTheme.colorScheme.primary,
                    )
                }
                Button(
                    onClick = onChat,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPixel) PixelYellow else MaterialTheme.colorScheme.primary,
                        contentColor = if (isPixel) Color.Black else MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        "Chat",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                    )
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
    isPixel: Boolean,
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
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isPixel) PixelSurfaceVariant else MaterialTheme.colorScheme.secondaryContainer),
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
                    Text(
                        text = label.take(2).uppercase(),
                        fontWeight = FontWeight.Bold,
                        fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                        color = if (isPixel) PixelCyan else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        },
        headlineContent = {
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
            )
        },
        trailingContent = {
            OutlinedButton(
                onClick = onChat,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    "Chat",
                    fontSize = 12.sp,
                    fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                )
            }
        },
    )
}
