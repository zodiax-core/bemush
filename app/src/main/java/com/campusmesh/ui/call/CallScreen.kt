package com.campusmesh.ui.call

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.call.CallState
import java.io.File
import java.util.Locale

@Composable
fun CallRoute(
    onBackClick: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val callState by viewModel.callState.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            timber.log.Timber.w("Microphone permission denied on CallScreen")
        }
    }

    LaunchedEffect(Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    var hasBeenActive by remember { mutableStateOf(false) }
    LaunchedEffect(callState) {
        if (callState !is CallState.Idle) {
            hasBeenActive = true
        }
        if (callState is CallState.Ended) {
            kotlinx.coroutines.delay(1800L)
            onBackClick()
        } else if (callState is CallState.Idle && hasBeenActive) {
            onBackClick()
        }
    }

    CallScreen(
        callState = callState,
        defaultPeerName = viewModel.peerLabel,
        isMuted = isMuted,
        isSpeakerOn = isSpeakerOn,
        onBackClick = onBackClick,
        onAcceptClick = viewModel::acceptCall,
        onDeclineClick = viewModel::declineCall,
        onEndCallClick = viewModel::endCall,
        onToggleMute = viewModel::toggleMute,
        onToggleSpeaker = viewModel::toggleSpeaker,
    )
}

@Composable
fun CallScreen(
    callState: CallState,
    defaultPeerName: String,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    onBackClick: () -> Unit,
    onAcceptClick: () -> Unit,
    onDeclineClick: () -> Unit,
    onEndCallClick: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
) {
    val displayName = when (callState) {
        is CallState.Outgoing -> callState.peerName
        is CallState.Incoming -> callState.peerName
        is CallState.Connected -> callState.peerName
        else -> defaultPeerName
    }.ifBlank { defaultPeerName }

    val avatarPath = when (callState) {
        is CallState.Outgoing -> callState.avatarPath
        is CallState.Incoming -> callState.avatarPath
        is CallState.Connected -> callState.avatarPath
        else -> null
    }

    val avatarBitmap = remember(avatarPath) {
        avatarPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                try { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() } catch (_: Exception) { null }
            } else null
        }
    }

    val statusText = when (callState) {
        is CallState.Outgoing -> if (callState.isRinging) "Ringing…" else "Calling…"
        is CallState.Incoming -> "Incoming voice call…"
        is CallState.Connected -> formatDuration(callState.durationSeconds)
        is CallState.Ended -> callState.reason
        CallState.Idle -> "Connecting…"
    }

    // WhatsApp style color scheme: Black, dark gray, white
    val backgroundColor = Color(0xFF0C0F14)
    val cardGray = Color(0xFF1E232B)
    val subtleGray = Color(0xFF8696A0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        // ── Top Header ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Minimize Call",
                    tint = Color.White,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = subtleGray,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "End-to-end encrypted",
                    color = subtleGray,
                    fontSize = 12.sp,
                )
            }

            // Invisible spacer for symmetric balance
            Spacer(modifier = Modifier.size(48.dp))
        }

        // ── Center Content: Caller Info & Large Avatar ───────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = displayName,
                color = Color.White,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = statusText,
                color = if (callState is CallState.Connected) Color(0xFF25D366) else subtleGray,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Large Circular Avatar
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(cardGray)
                    .border(2.dp, Color(0xFF2B323D), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarBitmap != null) {
                    Image(
                        bitmap = avatarBitmap,
                        contentDescription = "Caller Avatar",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = displayName.take(2).uppercase(),
                        color = Color.White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // ── Bottom Action Controls (WhatsApp structure) ──────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            if (callState is CallState.Incoming) {
                // Incoming Call: Decline and Accept buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Decline
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEA0038))
                                .clickable(onClick = onDeclineClick),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "Decline",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Decline",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    // Accept
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00A884))
                                .clickable(onClick = onAcceptClick),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Accept",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Accept",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            } else {
                // Active / Outgoing / Connected Call: Bottom control bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(32.dp))
                        .background(cardGray)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Speaker Toggle
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(if (isSpeakerOn) Color.White else Color(0xFF2A313C))
                                .clickable(onClick = onToggleSpeaker),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Speaker",
                                tint = if (isSpeakerOn) Color.Black else Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        // Mute Toggle
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(if (isMuted) Color.White else Color(0xFF2A313C))
                                .clickable(onClick = onToggleMute),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Mute",
                                tint = if (isMuted) Color.Black else Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        // End Call (Red Button)
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEA0038))
                                .clickable(onClick = onEndCallClick),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "End Call",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}
