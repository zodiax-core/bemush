package com.campusmesh.call.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioStreamEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val isRunning = AtomicBoolean(false)
    val isMuted = AtomicBoolean(false)
    val isSpeakerOn = AtomicBoolean(false)

    private var recordJob: Job? = null
    private var playbackJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()

    private var onAudioFrameCapturedListener: ((ByteArray, Byte) -> Unit)? = null
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerState = false

    fun setOnAudioFrameCapturedListener(listener: ((ByteArray, Byte) -> Unit)?) {
        onAudioFrameCapturedListener = listener
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
        try {
            audioManager.isMicrophoneMute = muted
        } catch (e: Exception) {
            Timber.w(e, "Failed to set microphone mute")
        }
    }

    fun setSpeakerphone(speakerOn: Boolean) {
        isSpeakerOn.set(speakerOn)
        try {
            audioManager.isSpeakerphoneOn = speakerOn
        } catch (e: Exception) {
            Timber.w(e, "Failed to set speakerphone")
        }
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (isRunning.getAndSet(true)) return

        try {
            previousAudioMode = audioManager.mode
            previousSpeakerState = audioManager.isSpeakerphoneOn

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = isSpeakerOn.get()
        } catch (e: Exception) {
            Timber.w(e, "Error configuring AudioManager for voice call")
        }

        playbackQueue.clear()

        startAudioTrack(scope)
        startAudioRecord(scope)
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecord(scope: CoroutineScope) {
        val sampleRate = 8000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Timber.e("RECORD_AUDIO permission not granted, cannot start AudioRecord")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, ImaAdpcmCodec.SAMPLES_PER_FRAME * 4)

        try {
            var record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize,
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Timber.w("AudioRecord with VOICE_COMMUNICATION failed to initialize, falling back to MIC")
                record.release()
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize,
                )
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Timber.e("AudioRecord failed to initialize with both sources")
                record.release()
                audioRecord = null
                return
            }

            audioRecord = record
            record.startRecording()
            Timber.i("AudioRecord started recording (audioSource=%d)", record.audioSource)

            recordJob = scope.launch(Dispatchers.IO) {
                val pcmBuffer = ShortArray(ImaAdpcmCodec.SAMPLES_PER_FRAME)
                val encodedOut = ByteArray(ImaAdpcmCodec.ENCODED_FRAME_SIZE)
                val codecState = ImaAdpcmCodec.State()
                var seq: Byte = 0

                while (isActive && isRunning.get()) {
                    val currentRecord = audioRecord ?: break
                    var readTotal = 0
                    while (readTotal < ImaAdpcmCodec.SAMPLES_PER_FRAME && isActive && isRunning.get()) {
                        val read = currentRecord.read(
                            pcmBuffer,
                            readTotal,
                            ImaAdpcmCodec.SAMPLES_PER_FRAME - readTotal,
                        )
                        if (read > 0) {
                            readTotal += read
                        } else {
                            kotlinx.coroutines.delay(10)
                            break
                        }
                    }

                    if (readTotal == ImaAdpcmCodec.SAMPLES_PER_FRAME) {
                        if (isMuted.get()) {
                            pcmBuffer.fill(0)
                        }

                        val encodedBytes = ImaAdpcmCodec.encode(
                            pcmSamples = pcmBuffer,
                            offset = 0,
                            length = ImaAdpcmCodec.SAMPLES_PER_FRAME,
                            state = codecState,
                            sequenceNumber = seq,
                            outBuffer = encodedOut,
                        )

                        if (encodedBytes > 0) {
                            onAudioFrameCapturedListener?.invoke(encodedOut.clone(), seq)
                        }
                        seq = (seq + 1).toByte()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception initializing AudioRecord")
        }
    }

    private fun startAudioTrack(scope: CoroutineScope) {
        val sampleRate = 8000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, ImaAdpcmCodec.SAMPLES_PER_FRAME * 4)

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioFormat)
                .build()

            var track = AudioTrack(
                audioAttributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                audioManager.generateAudioSessionId(),
            )

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Timber.w("AudioTrack with USAGE_VOICE_COMMUNICATION failed, falling back to USAGE_MEDIA")
                track.release()
                val fallbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                track = AudioTrack(
                    fallbackAttributes,
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    audioManager.generateAudioSessionId(),
                )
            }

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Timber.e("AudioTrack failed to initialize with all configurations")
                track.release()
                audioTrack = null
                return
            }

            audioTrack = track
            track.play()
            Timber.i("AudioTrack started playing")

            playbackJob = scope.launch(Dispatchers.IO) {
                val decodedPcm = ShortArray(ImaAdpcmCodec.SAMPLES_PER_FRAME)

                while (isActive && isRunning.get()) {
                    val frame = playbackQueue.poll()
                    if (frame != null) {
                        val samples = ImaAdpcmCodec.decode(
                            encodedFrame = frame,
                            frameOffset = 0,
                            frameLength = frame.size,
                            outSamples = decodedPcm,
                            outOffset = 0,
                        )
                        if (samples > 0) {
                            audioTrack?.write(decodedPcm, 0, samples)
                        }
                    } else {
                        // Small non-blocking delay to avoid busy looping when queue is empty
                        kotlinx.coroutines.delay(5)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception initializing AudioTrack")
        }
    }

    /**
     * Enqueues an incoming encoded audio frame for playback.
     */
    fun onIncomingAudioFrame(encodedFrame: ByteArray) {
        if (!isRunning.get()) return
        // Drop oldest if queue is backing up (jitter buffer limit = 10 frames / ~400ms)
        while (playbackQueue.size > 10) {
            playbackQueue.poll()
        }
        playbackQueue.offer(encodedFrame)
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        recordJob?.cancel()
        recordJob = null

        playbackJob?.cancel()
        playbackJob = null

        playbackQueue.clear()

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            audioTrack?.stop()
        } catch (_: Exception) {}
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        try {
            audioManager.mode = previousAudioMode
            audioManager.isSpeakerphoneOn = previousSpeakerState
            audioManager.isMicrophoneMute = false
        } catch (_: Exception) {}

        Timber.i("AudioStreamEngine stopped")
    }
}
