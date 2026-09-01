package com.campusmesh.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.db.MessageEntity
import com.campusmesh.transport.TransportConnectionState
import com.campusmesh.ui.theme.AppTheme
import com.campusmesh.ui.theme.LocalAppTheme
import com.campusmesh.ui.theme.PixelCyan
import com.campusmesh.ui.theme.PixelGreen
import com.campusmesh.ui.theme.PixelMagenta
import com.campusmesh.ui.theme.PixelOrange
import com.campusmesh.ui.theme.PixelYellow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatRoute(
    onBackClick: () -> Unit,
    onNavigateToPeerProfile: (String, String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ChatScreen(
        state = uiState,
        onBackClick = onBackClick,
        onPeerProfileClick = {
            onNavigateToPeerProfile(uiState.peerNodeId, uiState.peerLabel)
        },
        onSendMessage = viewModel::sendMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onBackClick: () -> Unit,
    onPeerProfileClick: () -> Unit,
    onSendMessage: (String) -> Unit,
) {
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val appTheme = LocalAppTheme.current
    val isPixel = (appTheme == AppTheme.PIXEL_8BIT)

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    val avatarBitmap = remember(state.peer?.avatarPath) {
        state.peer?.avatarPath?.let { path ->
            val file = File(path)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
        }
    }

    val connectionColor = when (state.transport.connectionState) {
        TransportConnectionState.Connected -> if (isPixel) PixelGreen else MaterialTheme.colorScheme.secondary
        TransportConnectionState.Connecting -> if (isPixel) PixelOrange else MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val connectionLabel = when (state.transport.connectionState) {
        TransportConnectionState.Connected -> "● Connected"
        TransportConnectionState.Connecting -> "○ Connecting…"
        TransportConnectionState.Failed -> "✕ Failed"
        TransportConnectionState.Disconnected -> "○ Offline — queued"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onPeerProfileClick)
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
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
                                    text = state.peerLabel.take(2).uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                                    color = if (isPixel) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        Column {
                            Text(
                                text = state.peerLabel,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                            )
                            Text(
                                text = connectionLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = connectionColor,
                                fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = {
                            Text(
                                "Type a message…",
                                fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                            )
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp),
                    )
                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                onSendMessage(textInput)
                                textInput = ""
                            }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(if (isPixel) PixelYellow else MaterialTheme.colorScheme.primary),
                        enabled = textInput.isNotBlank(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (isPixel) Color.Black else MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        if (state.messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text(
                        "No messages yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.transport.connectionState == TransportConnectionState.Connected)
                            "Connected! Type a message to start chatting."
                        else
                            "Not connected to this peer. Messages you send will be queued and delivered when in range.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(state.messages, key = { it.messageId }) { msg ->
                    MessageBubble(message = msg, isPixel = isPixel)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageEntity, isPixel: Boolean) {
    val isOutgoing = message.senderId == "local"
    val alignment = if (isOutgoing) Arrangement.End else Arrangement.Start

    val bubbleColor = if (isPixel) {
        if (isOutgoing) PixelYellow else PixelCyan
    } else {
        if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = if (isPixel) {
        Color.Black
    } else {
        if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    }

    val shape = if (isPixel) {
        RoundedCornerShape(6.dp)
    } else if (isOutgoing) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    }

    val timeStr = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }

    val (statusText, statusColor) = when {
        !isOutgoing -> "" to textColor
        message.status == "PENDING" -> " 🕐" to (if (isPixel) Color(0xFF666666) else textColor.copy(alpha = 0.7f))
        message.status == "SENT" -> " ✓" to (if (isPixel) Color(0xFF333333) else textColor.copy(alpha = 0.7f))
        message.status == "DELIVERED" -> " ✓✓" to (if (isPixel) Color(0xFF333333) else textColor.copy(alpha = 0.7f))
        message.status == "SEEN" -> " ✓✓" to (if (isPixel) PixelMagenta else Color(0xFF64B5F6))
        else -> "" to textColor
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = alignment,
    ) {
        Surface(
            color = bubbleColor,
            shape = shape,
            modifier = Modifier
                .widthIn(min = 70.dp, max = 290.dp)
                .then(
                    if (isPixel) Modifier.border(2.dp, if (isOutgoing) PixelMagenta else PixelYellow, shape) else Modifier
                ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message.content,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isPixel) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                )
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPixel) Color.DarkGray else textColor.copy(alpha = 0.7f),
                        fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                    )
                    if (isOutgoing) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                        )
                    }
                }
            }
        }
    }
}
