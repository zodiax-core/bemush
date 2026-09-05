package com.campusmesh.ui.call

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmesh.call.CallManager
import com.campusmesh.call.CallState
import com.campusmesh.data.PeerRepository
import com.campusmesh.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    private val callManager: CallManager,
    private val peerRepository: PeerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val callState: StateFlow<CallState> = callManager.callState
    val isMuted: StateFlow<Boolean> = callManager.isMuted
    val isSpeakerOn: StateFlow<Boolean> = callManager.isSpeakerOn

    val peerNodeId: String = savedStateHandle.get<String>("peerNodeId")?.let { Routes.decodeArg(it) } ?: ""
    val peerLabel: String = savedStateHandle.get<String>("peerLabel")?.let { Routes.decodeArg(it) } ?: ""
    val isIncoming: Boolean = savedStateHandle.get<Boolean>("isIncoming") ?: false

    init {
        Timber.i("CallViewModel initialized for peer %s (isIncoming=%s)", peerLabel, isIncoming)

        viewModelScope.launch {
            val peer = peerRepository.getPeer(peerNodeId)
            val avatarPath = peer?.avatarPath

            val currentState = callManager.callState.value
            if (!isIncoming && (currentState is CallState.Idle || currentState is CallState.Ended)) {
                callManager.startCall(peerNodeId, peerLabel, avatarPath)
            }
        }
    }

    fun acceptCall() {
        callManager.acceptCall()
    }

    fun declineCall() {
        callManager.declineCall()
    }

    fun endCall() {
        callManager.endCall()
    }

    fun toggleMute() {
        callManager.toggleMute()
    }

    fun toggleSpeaker() {
        callManager.toggleSpeaker()
    }
}
