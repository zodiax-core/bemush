package com.campusmesh.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmesh.data.MessageRepository
import com.campusmesh.data.PeerRepository
import com.campusmesh.db.MessageEntity
import com.campusmesh.db.PeerEntity
import com.campusmesh.transport.DirectTransportController
import com.campusmesh.transport.DirectTransportSnapshot
import com.campusmesh.transport.TransportConnectionState
import com.campusmesh.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val directTransportController: DirectTransportController,
) : ViewModel() {

    val peerNodeId: String = Routes.decodeArg(savedStateHandle["peerNodeId"] ?: "")
    val initialPeerLabel: String = Routes.decodeArg(savedStateHandle["peerLabel"] ?: "Peer")

    val uiState: StateFlow<ChatUiState> = combine(
        messageRepository.getMessagesForConversation(peerNodeId),
        peerRepository.allPeers,
        directTransportController.snapshot,
    ) { messages, peers, transport ->
        val peer = peers.find { it.nodeId == peerNodeId }
        val effectiveLabel = peer?.customName?.ifBlank { null }
            ?: peer?.displayName?.ifBlank { null }
            ?: initialPeerLabel.takeIf { it.isNotBlank() }
            ?: peerNodeId.take(8).uppercase()

        ChatUiState(
            peerNodeId = peerNodeId,
            peerLabel = effectiveLabel,
            messages = messages,
            peer = peer,
            transport = transport,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState(
            peerNodeId = peerNodeId,
            peerLabel = initialPeerLabel,
            messages = emptyList(),
            transport = directTransportController.snapshot.value,
        ),
    )

    init {
        directTransportController.activeChatPeerId = peerNodeId
        directTransportController.markConversationAsRead(peerNodeId)

        // Only initiate connection if not already directly connected in mesh
        viewModelScope.launch {
            val liveAddress = directTransportController.resolveConnectableAddress(peerNodeId)
            val peer = peerRepository.getPeer(peerNodeId)
            val effectiveAddress = liveAddress ?: peer?.deviceAddress ?: ""

            val isAlreadyDirect = directTransportController.isPeerDirectlyConnected(peerNodeId) ||
                    (effectiveAddress.isNotBlank() && directTransportController.isAddressDirectlyConnected(effectiveAddress))

            if (!isAlreadyDirect && effectiveAddress.isNotBlank()) {
                val label = peer?.customName?.ifBlank { null }
                    ?: peer?.displayName?.ifBlank { null }
                    ?: initialPeerLabel.takeIf { it.isNotBlank() }
                    ?: peerNodeId.take(8).uppercase()
                directTransportController.connectToPeer(
                    deviceAddress = effectiveAddress,
                    peerNodeId = peerNodeId,
                    peerLabel = label
                )
            }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        directTransportController.sendMessage(content, targetPeerNodeId = peerNodeId)
    }

    /** Manually reconnects to this peer. Bound to the reconnect button in ChatScreen. */
    fun reconnect() {
        viewModelScope.launch {
            val liveAddress = directTransportController.resolveConnectableAddress(peerNodeId)
            val peer = peerRepository.getPeer(peerNodeId)
            val effectiveAddress = liveAddress ?: peer?.deviceAddress ?: ""
            if (effectiveAddress.isNotBlank()) {
                val label = peer?.customName?.ifBlank { null }
                    ?: peer?.displayName?.ifBlank { null }
                    ?: initialPeerLabel.takeIf { it.isNotBlank() }
                    ?: peerNodeId.take(8).uppercase()
                directTransportController.connectToPeer(
                    deviceAddress = effectiveAddress,
                    peerNodeId = peerNodeId,
                    peerLabel = label
                )
            } else {
                directTransportController.reconnectToPeer()
            }
        }
    }

    /** Updates the custom name / alias for this peer in Room SQLite. */
    fun updatePeerCustomName(newName: String) {
        viewModelScope.launch {
            peerRepository.setCustomName(peerNodeId, newName)
        }
    }

    override fun onCleared() {
        if (directTransportController.activeChatPeerId == peerNodeId) {
            directTransportController.activeChatPeerId = null
        }
        super.onCleared()
    }
}

data class ChatUiState(
    val peerNodeId: String,
    val peerLabel: String,
    val messages: List<MessageEntity>,
    val peer: PeerEntity? = null,
    val transport: DirectTransportSnapshot,
)
