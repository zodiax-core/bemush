package com.campusmesh.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.ui.theme.AppTheme
import com.campusmesh.ui.theme.LocalAppTheme
import com.campusmesh.ui.theme.PixelCyan
import com.campusmesh.ui.theme.PixelMagenta
import com.campusmesh.ui.theme.PixelTextCyan
import com.campusmesh.ui.theme.PixelYellow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeRoute(
    onNavigateToChat: (String, String) -> Unit,
    onNavigateToPeers: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onNavigateToProfile: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val localProfile by viewModel.localProfile.collectAsStateWithLifecycle()
    HomeScreen(
        conversations = conversations,
        onConversationClick = { conv -> onNavigateToChat(conv.peerId, conv.peerLabel) },
        onNewChatClick = onNavigateToPeers,
        onDebugClick = onNavigateToDebug,
        onProfileClick = onNavigateToProfile,
        localAvatarPath = localProfile.avatarPath,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    conversations: List<ConversationSummary>,
    onConversationClick: (ConversationSummary) -> Unit,
    onNewChatClick: () -> Unit,
    onDebugClick: () -> Unit,
    onProfileClick: () -> Unit,
    localAvatarPath: String? = null,
) {
    val appTheme = LocalAppTheme.current
    val isPixel = (appTheme == AppTheme.PIXEL_8BIT)

    val localAvatarBitmap = remember(localAvatarPath) {
        localAvatarPath?.let { path ->
            try { BitmapFactory.decodeFile(path)?.asImageBitmap() } catch (_: Exception) { null }
        }
    }

    Scaffold(
        containerColor = if (isPixel) Color.White else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isPixel) "🕹️ CampusMesh" else "CampusMesh",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isPixel) Color.Black else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (isPixel) "OFFLINE MESH NETWORK" else "Offline Mesh Network",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPixel) PixelTextCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onProfileClick) {
                        if (localAvatarBitmap != null) {
                            Image(
                                bitmap = localAvatarBitmap,
                                contentDescription = "Profile",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = 2.dp,
                                        color = if (isPixel) Color.Black else MaterialTheme.colorScheme.primary,
                                        shape = CircleShape,
                                    ),
                            )
                        } else {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = "Profile",
                                tint = if (isPixel) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onDebugClick) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = "Debug",
                            tint = if (isPixel) PixelTextCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewChatClick,
                icon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = Color.Black,
                    )
                },
                text = {
                    Text(
                        text = if (isPixel) "FIND PEERS 🕹️" else "Find Peers",
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                    )
                },
                containerColor = if (isPixel) PixelYellow else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.then(
                    if (isPixel) Modifier.border(2.5.dp, Color.Black, RoundedCornerShape(16.dp)) else Modifier
                ),
            )
        },
    ) { innerPadding ->
        if (conversations.isEmpty()) {
            EmptyState(modifier = Modifier.padding(innerPadding), isPixel = isPixel, onFindPeers = onNewChatClick)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(conversations, key = { it.peerId }) { conv ->
                    ConversationRow(
                        conversation = conv,
                        isPixel = isPixel,
                        onClick = { onConversationClick(conv) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 80.dp),
                        color = if (isPixel) Color.Black.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    isPixel: Boolean,
    onClick: () -> Unit,
) {
    val timeFormatted = remember(conversation.lastMessageTimestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(conversation.lastMessageTimestamp))
    }
    val statusPrefix = when {
        conversation.isOutgoing && conversation.lastMessageStatus == "PENDING" -> "🕐 "
        conversation.isOutgoing && conversation.lastMessageStatus == "SENT" -> "✓ "
        conversation.isOutgoing && conversation.lastMessageStatus == "DELIVERED" -> "✓✓ "
        conversation.isOutgoing && conversation.lastMessageStatus == "SEEN" -> "✓✓ "
        conversation.isOutgoing -> "→ "
        else -> ""
    }

    val avatarBitmap = remember(conversation.avatarPath) {
        conversation.avatarPath?.let { path ->
            val file = File(path)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (isPixel) PixelYellow else MaterialTheme.colorScheme.primaryContainer)
                .border(
                    width = if (isPixel) 2.dp else 0.dp,
                    color = if (isPixel) Color.Black else Color.Transparent,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap,
                    contentDescription = "Peer Avatar",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = conversation.peerLabel.take(2).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isPixel) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Text content
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = conversation.peerLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isPixel) Color.Black else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = timeFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conversation.unreadCount > 0) {
                        if (isPixel) PixelMagenta else MaterialTheme.colorScheme.primary
                    } else {
                        if (isPixel) PixelTextCyan else MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$statusPrefix${conversation.lastMessageContent}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isPixel) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isPixel) PixelMagenta else MaterialTheme.colorScheme.primary)
                            .border(if (isPixel) 1.5.dp else 0.dp, Color.Black, RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${conversation.unreadCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    isPixel: Boolean = false,
    onFindPeers: () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isPixel) PixelYellow else MaterialTheme.colorScheme.primaryContainer)
                    .border(
                        width = if (isPixel) 3.dp else 0.dp,
                        color = if (isPixel) Color.Black else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = if (isPixel) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (isPixel) "🕹️ NO MESSAGES YET" else "No Conversations Yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (isPixel) Color.Black else MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Tap 'Find Peers' to scan for nearby phones over Bluetooth LE mesh.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isPixel) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onFindPeers,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPixel) PixelYellow else MaterialTheme.colorScheme.primary,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.then(
                    if (isPixel) Modifier.border(2.5.dp, Color.Black, RoundedCornerShape(12.dp)) else Modifier
                ),
            ) {
                Text(
                    text = if (isPixel) "PRESS TO FIND PEERS 🕹️" else "Find Nearby Peers",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
