package com.campusmesh.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmesh.data.PeerRepository
import com.campusmesh.db.PeerEntity
import com.campusmesh.transport.DirectTransportController
import com.campusmesh.transport.DirectTransportSnapshot
import com.campusmesh.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PeerProfileUiState(
    val peerNodeId: String = "",
    val peerLabel: String = "",
    val peer: PeerEntity? = null,
    val transport: DirectTransportSnapshot = DirectTransportSnapshot(),
)

@HiltViewModel
class PeerProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    peerRepository: PeerRepository,
    directTransportController: DirectTransportController,
) : ViewModel() {

    val peerNodeId: String = Routes.decodeArg(savedStateHandle["peerNodeId"] ?: "")
    val initialLabel: String = Routes.decodeArg(savedStateHandle["peerLabel"] ?: "Peer")

    val uiState: StateFlow<PeerProfileUiState> = combine(
        peerRepository.allPeers,
        directTransportController.snapshot,
    ) { peers, transport ->
        val peer = peers.find { it.nodeId == peerNodeId }
        val effectiveLabel = peer?.displayName ?: initialLabel
        PeerProfileUiState(
            peerNodeId = peerNodeId,
            peerLabel = effectiveLabel,
            peer = peer,
            transport = transport,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PeerProfileUiState(
            peerNodeId = peerNodeId,
            peerLabel = initialLabel,
            transport = directTransportController.snapshot.value,
        ),
    )
}
