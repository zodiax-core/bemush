package com.campusmesh.call

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.campusmesh.call.audio.AudioStreamEngine
import com.campusmesh.profile.ProfileManager
import com.campusmesh.transport.DirectTransportController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class CallState {
    object Idle : CallState()

    data class Outgoing(
        val peerNodeId: String,
        val peerName: String,
        val avatarPath: String?,
        val callId: String,
        val isRinging: Boolean = false,
    ) : CallState()

    data class Incoming(
        val peerNodeId: String,
        val peerName: String,
        val avatarPath: String?,
        val callId: String,
    ) : CallState()

    data class Connected(
        val peerNodeId: String,
        val peerName: String,
        val avatarPath: String?,
        val callId: String,
        val durationSeconds: Long = 0,
    ) : CallState()

    data class Ended(
        val reason: String,
    ) : CallState()
}

@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transportController: DirectTransportController,
    private val callNotificationManager: CallNotificationManager,
    private val audioStreamEngine: AudioStreamEngine,
    private val profileManager: ProfileManager,
) : DirectTransportController.CallPacketListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private var durationJob: Job? = null
    private var timeoutJob: Job? = null

    private var incomingRingtone: Ringtone? = null

    init {
        transportController.setCallPacketListener(this)

        // Forward local mic captured frames over direct BLE transport to active peer
        audioStreamEngine.setOnAudioFrameCapturedListener { frameData, seq ->
            val current = _callState.value
            if (current is CallState.Connected) {
                transportController.sendVoiceFrameDirect(current.peerNodeId, seq, frameData)
            }
        }
    }

    fun startCall(peerNodeId: String, peerName: String, avatarPath: String?) {
        val current = _callState.value
        if (current !is CallState.Idle && current !is CallState.Ended) {
            Timber.w("Cannot start call: already in call state %s", current)
            return
        }

        val callId = UUID.randomUUID().toString()
        val localProfile = profileManager.localProfile.value

        _isMuted.value = false
        _isSpeakerOn.value = false
        audioStreamEngine.setMuted(false)
        audioStreamEngine.setSpeakerphone(false)

        _callState.value = CallState.Outgoing(
            peerNodeId = peerNodeId,
            peerName = peerName,
            avatarPath = avatarPath,
            callId = callId,
            isRinging = false,
        )

        val offer = CallPacket.Offer(
            callId = callId,
            callerName = localProfile.displayName.ifBlank { "Me" },
            callerAvatarPath = localProfile.avatarPath,
        )
        transportController.sendCallPacket(peerNodeId, offer)
        Timber.i("Sent Call Offer %s to %s", callId, peerNodeId)

        // 35-second unanswered timeout
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(35_000L)
            if (_callState.value is CallState.Outgoing) {
                endCall("No Answer")
            }
        }
    }

    fun acceptCall() {
        val current = _callState.value
        if (current !is CallState.Incoming) return

        stopIncomingAlerts()
        timeoutJob?.cancel()

        _isMuted.value = false
        _isSpeakerOn.value = false
        audioStreamEngine.setMuted(false)
        audioStreamEngine.setSpeakerphone(false)

        _callState.value = CallState.Connected(
            peerNodeId = current.peerNodeId,
            peerName = current.peerName,
            avatarPath = current.avatarPath,
            callId = current.callId,
            durationSeconds = 0,
        )

        val answer = CallPacket.Answer(callId = current.callId)
        transportController.sendCallPacket(current.peerNodeId, answer)
        Timber.i("Accepted call %s from %s", current.callId, current.peerNodeId)

        // Yield slightly so ANSWER packet transmits cleanly without competing with immediate audio
        scope.launch {
            delay(100L)
            if (_callState.value is CallState.Connected) {
                audioStreamEngine.start(scope)
                startDurationTimer()
            }
        }
    }

    fun declineCall() {
        val current = _callState.value
        if (current !is CallState.Incoming) return

        stopIncomingAlerts()
        timeoutJob?.cancel()

        val decline = CallPacket.Decline(callId = current.callId)
        transportController.sendCallPacket(current.peerNodeId, decline)
        Timber.i("Declined call %s from %s", current.callId, current.peerNodeId)

        _callState.value = CallState.Ended("Call Declined")
        scheduleResetToIdle()
    }

    fun endCall(reason: String = "Call Ended") {
        val current = _callState.value
        if (current is CallState.Idle) return

        val peerNodeId = when (current) {
            is CallState.Outgoing -> current.peerNodeId
            is CallState.Incoming -> current.peerNodeId
            is CallState.Connected -> current.peerNodeId
            else -> null
        }
        val callId = when (current) {
            is CallState.Outgoing -> current.callId
            is CallState.Incoming -> current.callId
            is CallState.Connected -> current.callId
            else -> null
        }
        val duration = (current as? CallState.Connected)?.durationSeconds ?: 0

        stopIncomingAlerts()
        timeoutJob?.cancel()
        durationJob?.cancel()
        audioStreamEngine.stop()

        if (peerNodeId != null && callId != null) {
            val hangup = CallPacket.Hangup(callId = callId, durationSeconds = duration)
            transportController.sendCallPacket(peerNodeId, hangup)
            Timber.i("Sent Hangup for call %s to %s", callId, peerNodeId)
            // Redundant transmission after 60ms to guarantee arrival over RF
            scope.launch {
                delay(60L)
                transportController.sendCallPacket(peerNodeId, hangup)
            }
        }

        _callState.value = CallState.Ended(reason)
        scheduleResetToIdle()
    }

    fun toggleMute() {
        val newMute = !_isMuted.value
        _isMuted.value = newMute
        audioStreamEngine.setMuted(newMute)
    }

    fun toggleSpeaker() {
        val newSpeaker = !_isSpeakerOn.value
        _isSpeakerOn.value = newSpeaker
        audioStreamEngine.setSpeakerphone(newSpeaker)
    }

    override fun onCallPacketReceived(senderNodeId: String, packet: CallPacket) {
        scope.launch {
            when (packet) {
                is CallPacket.Offer -> {
                    val current = _callState.value
                    if (current !is CallState.Idle && current !is CallState.Ended) {
                        // Busy — decline automatically
                        val busyPacket = CallPacket.Decline(callId = packet.callId, reason = "busy")
                        transportController.sendCallPacket(senderNodeId, busyPacket)
                        return@launch
                    }

                    _callState.value = CallState.Incoming(
                        peerNodeId = senderNodeId,
                        peerName = packet.callerName,
                        avatarPath = packet.callerAvatarPath,
                        callId = packet.callId,
                    )

                    startIncomingAlerts(packet.callerName, packet.callerAvatarPath)

                    // Acknowledge offer with Ringing status
                    val ringingPacket = CallPacket.Ringing(callId = packet.callId)
                    transportController.sendCallPacket(senderNodeId, ringingPacket)

                    // 40-second timeout if receiver doesn't answer
                    timeoutJob?.cancel()
                    timeoutJob = launch {
                        delay(40_000L)
                        if (_callState.value is CallState.Incoming) {
                            declineCall()
                        }
                    }
                }

                is CallPacket.Ringing -> {
                    val current = _callState.value
                    if (current is CallState.Outgoing && current.callId == packet.callId) {
                        _callState.value = current.copy(isRinging = true)
                    }
                }

                is CallPacket.Answer -> {
                    val current = _callState.value
                    if (current is CallState.Outgoing && (current.callId == packet.callId || current.peerNodeId == senderNodeId)) {
                        timeoutJob?.cancel()
                        _callState.value = CallState.Connected(
                            peerNodeId = current.peerNodeId,
                            peerName = current.peerName,
                            avatarPath = current.avatarPath,
                            callId = packet.callId.ifBlank { current.callId },
                            durationSeconds = 0,
                        )
                        audioStreamEngine.start(scope)
                        startDurationTimer()
                        Timber.i("Call connected with %s (callId=%s)", current.peerNodeId, packet.callId)
                    }
                }

                is CallPacket.Decline -> {
                    val current = _callState.value
                    val currentCallId = when (current) {
                        is CallState.Outgoing -> current.callId
                        is CallState.Incoming -> current.callId
                        is CallState.Connected -> current.callId
                        else -> null
                    }
                    val currentPeer = when (current) {
                        is CallState.Outgoing -> current.peerNodeId
                        is CallState.Incoming -> current.peerNodeId
                        is CallState.Connected -> current.peerNodeId
                        else -> null
                    }
                    if (currentCallId == packet.callId || (currentPeer != null && currentPeer == senderNodeId)) {
                        val reason = if (packet.reason == "busy") "User Busy" else "Call Declined"
                        stopIncomingAlerts()
                        timeoutJob?.cancel()
                        durationJob?.cancel()
                        audioStreamEngine.stop()
                        _callState.value = CallState.Ended(reason)
                        scheduleResetToIdle()
                    }
                }

                is CallPacket.Hangup -> {
                    val current = _callState.value
                    val currentCallId = when (current) {
                        is CallState.Outgoing -> current.callId
                        is CallState.Incoming -> current.callId
                        is CallState.Connected -> current.callId
                        else -> null
                    }
                    val currentPeer = when (current) {
                        is CallState.Outgoing -> current.peerNodeId
                        is CallState.Incoming -> current.peerNodeId
                        is CallState.Connected -> current.peerNodeId
                        else -> null
                    }
                    if (currentCallId == packet.callId || (currentPeer != null && currentPeer == senderNodeId)) {
                        stopIncomingAlerts()
                        timeoutJob?.cancel()
                        durationJob?.cancel()
                        audioStreamEngine.stop()
                        _callState.value = CallState.Ended("Call Ended")
                        scheduleResetToIdle()
                    }
                }

                is CallPacket.AudioFrame -> {
                    val current = _callState.value
                    if (current is CallState.Connected && current.callId == packet.callId) {
                        audioStreamEngine.onIncomingAudioFrame(packet.audioData)
                    }
                }
            }
        }
    }

    override fun onDirectVoiceFrameReceived(senderNodeId: String, seq: Byte, audioData: ByteArray) {
        val current = _callState.value
        if (current is CallState.Connected) {
            audioStreamEngine.onIncomingAudioFrame(audioData)
        }
    }

    override fun onPeerDisconnected(nodeId: String) {
        scope.launch {
            val current = _callState.value
            val isCurrentPeer = when (current) {
                is CallState.Outgoing -> current.peerNodeId == nodeId
                is CallState.Incoming -> current.peerNodeId == nodeId
                is CallState.Connected -> current.peerNodeId == nodeId
                else -> false
            }
            if (isCurrentPeer) {
                Timber.w("Active call peer %s disconnected, entering 4s grace period", nodeId)
                delay(4000L)
                val stillCurrent = _callState.value
                val stillMatching = when (stillCurrent) {
                    is CallState.Outgoing -> stillCurrent.peerNodeId == nodeId
                    is CallState.Incoming -> stillCurrent.peerNodeId == nodeId
                    is CallState.Connected -> stillCurrent.peerNodeId == nodeId
                    else -> false
                }
                if (stillMatching && !transportController.isPeerDirectlyConnected(nodeId)) {
                    Timber.w("Active call peer %s still disconnected, ending call", nodeId)
                    endCall("Connection Lost")
                }
            }
        }
    }

    private fun startDurationTimer() {
        durationJob?.cancel()
        durationJob = scope.launch {
            while (isActive) {
                delay(1000L)
                _callState.update { current ->
                    if (current is CallState.Connected) {
                        current.copy(durationSeconds = current.durationSeconds + 1)
                    } else current
                }
            }
        }
    }

    private fun startIncomingAlerts(callerName: String, avatarPath: String?) {
        callNotificationManager.showIncomingCallNotification(
            callerNodeId = (_callState.value as? CallState.Incoming)?.peerNodeId ?: "",
            callerName = callerName,
            callerAvatarPath = avatarPath,
        )

        try {
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            incomingRingtone = RingtoneManager.getRingtone(context, alertUri)
            incomingRingtone?.play()
        } catch (e: Exception) {
            Timber.w(e, "Could not play incoming ringtone")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0)
                vm?.vibrate(CombinedVibration.createParallel(effect))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(longArrayOf(0, 1000, 1000), 0)
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not start call vibration")
        }
    }

    private fun stopIncomingAlerts() {
        callNotificationManager.cancelIncomingCallNotification()

        try {
            incomingRingtone?.stop()
        } catch (_: Exception) {}
        incomingRingtone = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.cancel()
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.cancel()
            }
        } catch (_: Exception) {}
    }

    private fun scheduleResetToIdle() {
        scope.launch {
            delay(2000L)
            if (_callState.value is CallState.Ended) {
                _callState.value = CallState.Idle
            }
        }
    }
}
