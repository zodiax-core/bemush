package com.campusmesh.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmesh.data.MessageRepository
import com.campusmesh.data.PeerRepository
import com.campusmesh.identity.LocalNodeIdStore
import com.campusmesh.profile.LocalProfileState
import com.campusmesh.profile.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ConversationSummary(
    val peerId: String,
    val peerLabel: String,
    val avatarPath: String?,
    val lastMessageContent: String,
    val lastMessageTimestamp: Long,
    val lastMessageStatus: String,
    val isOutgoing: Boolean,
    val unreadCount: Int,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    messageRepository: MessageRepository,
    peerRepository: PeerRepository,
    localNodeIdStore: LocalNodeIdStore,
    profileManager: ProfileManager,
) : ViewModel() {

    private val localNodeId = localNodeIdStore.nodeId.toString()

    /** Local user profile — used to display own avatar in HomeScreen top bar. */
    val localProfile: StateFlow<LocalProfileState> = profileManager.localProfile
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = profileManager.localProfile.value,
        )

    val conversations: StateFlow<List<ConversationSummary>> =
        combine(
            messageRepository.latestMessagePerConversation,
            peerRepository.allPeers,
            messageRepository.unreadCountsPerConversation,
        ) { messages, peers, unreadCounts ->
            val unreadMap = unreadCounts.associate { it.peerId to it.unreadCount }

            messages.mapNotNull { msg ->
                val peerId = if (msg.senderId == "local") msg.recipientId else msg.senderId
                if (peerId == localNodeId || peerId == "local") return@mapNotNull null
                val peer = peers.find { it.nodeId == peerId }
                val label = peer?.customName?.ifBlank { null }
                    ?: peer?.displayName?.ifBlank { null }
                    ?: peerId.take(8).uppercase()
                val count = unreadMap[peerId] ?: 0

                ConversationSummary(
                    peerId = peerId,
                    peerLabel = label,
                    avatarPath = peer?.avatarPath,
                    lastMessageContent = msg.content,
                    lastMessageTimestamp = msg.timestamp,
                    lastMessageStatus = msg.status,
                    isOutgoing = msg.senderId == "local",
                    unreadCount = count,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
}
