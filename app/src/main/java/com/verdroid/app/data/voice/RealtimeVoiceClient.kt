package com.verdroid.app.data.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

enum class RealtimePhase { IDLE, CONNECTING, LISTENING, RESPONDING, ERROR }
data class RealtimeVoiceState(
    val phase: RealtimePhase = RealtimePhase.IDLE,
    val message: String = "Not connected",
    val transcript: String = "",
)

/**
 * A separate opt-in Realtime voice conversation. It does not execute Verdroid's agent tools.
 * BYOK: the key comes from EncryptedSharedPreferences, is sent only via the TLS WebSocket
 * Authorization header, and must never be put in logs / persisted here.
 *
 * Audio is 24kHz mono PCM16 in both directions. The model runs semantic VAD and can interrupt
 * speech. A hard ten-minute limit guards against abandoned paid sessions.
 */
class RealtimeVoiceClient(
    private val context: Context,
    private val http: OkHttpClient,
    private val keyProvider: () -> String?,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RealtimeVoiceState())
    val state: StateFlow<RealtimeVoiceState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var microphone: AudioRecord? = null
    private var speaker: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var microphoneJob: Job? = null
    private var speakerJob: Job? = null
    private var limitJob: Job? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>(64)
    @Volatile private var responseActive = false
    @Volatile private var generation = 0

    @Synchronized
    fun connect(model: String) {
        disconnect()
        if (model !in RealtimeProtocol.MODELS) {
            _state.value = RealtimeVoiceState(RealtimePhase.ERROR, "Unknown Realtime model")
            return
        }
        val key = keyProvider()?.trim().orEmpty()
        if (key.isBlank()) {
            _state.value = RealtimeVoiceState(RealtimePhase.ERROR, "Add an OpenAI API key in Settings → LLM providers")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = RealtimeVoiceState(RealtimePhase.ERROR, "Microphone permission required")
            return
        }
        val epoch = ++generation
        _state.value = RealtimeVoiceState(RealtimePhase.CONNECTING, "Connecting to OpenAI…")
        val request = Request.Builder()
            .url("https://api.openai.com/v1/realtime?model=$model")
            .header("Authorization", "Bearer $key")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (epoch != generation) return
                when (val event = RealtimeProtocol.parse(text)) {
                    RealtimeEvent.SessionCreated -> webSocket.send(RealtimeProtocol.sessionUpdate(model))
                    RealtimeEvent.SessionReady -> {
                        _state.value = RealtimeVoiceState(RealtimePhase.LISTENING, "Listening…")
                        scope.launch {
                            if (epoch == generation) runCatching { startAudio(webSocket, epoch) }
                                .onFailure { if (epoch == generation) fail("Audio unavailable: ${it.message ?: "device error"}") }
                        }
                    }
                    RealtimeEvent.SpeechStarted -> {
                        audioQueue.clear()
                        synchronized(this@RealtimeVoiceClient) {
                            runCatching { speaker?.pause(); speaker?.flush(); speaker?.play() }
                        }
                        if (responseActive) webSocket.send(RealtimeProtocol.cancelResponse())
                        responseActive = false
                        _state.value = _state.value.copy(phase = RealtimePhase.LISTENING, message = "Listening…", transcript = "")
                    }
                    RealtimeEvent.ResponseStarted -> {
                        responseActive = true
                        _state.value = _state.value.copy(phase = RealtimePhase.RESPONDING, message = "NOX speaking…")
                    }
                    RealtimeEvent.ResponseDone -> {
                        responseActive = false
                        _state.value = _state.value.copy(phase = RealtimePhase.LISTENING, message = "Listening…")
                    }
                    is RealtimeEvent.Audio -> {
                        if (!audioQueue.offer(event.pcm16)) {
                            audioQueue.poll()
                            audioQueue.offer(event.pcm16)
                        }
                    }
                    is RealtimeEvent.Transcript -> {
                        _state.value = _state.value.copy(transcript = (_state.value.transcript + event.delta).takeLast(1200))
                    }
                    is RealtimeEvent.Error -> fail("OpenAI: ${event.message}")
                    RealtimeEvent.Other -> Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (epoch == generation) fail("Connection failed${response?.let { " (HTTP ${it.code})" } ?: ""}: ${t.message ?: "network error"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (epoch == generation) disconnect()
            }
        })
        limitJob = scope.launch {
            delay(10 * 60 * 1000L)
            if (epoch == generation) fail("Session ended after 10 minutes to limit API costs")
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startAudio(ws: WebSocket, epoch: Int) {
        if (epoch != generation || microphone != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Microphone permission revoked")
        val sampleRate = RealtimeProtocol.SAMPLE_RATE
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minMic = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val minSpk = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minMic > 0 && minSpk > 0) { "24 kHz audio not supported by the device" }
        val mic = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format).setBufferSizeInBytes(maxOf(minMic, 9_600)).build()
        check(mic.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
        microphone = mic
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(mic.audioSessionId)?.also { it.enabled = true }
        }
        val outFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val out = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(outFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minSpk, 19_200)).build()
        check(out.state == AudioTrack.STATE_INITIALIZED) { "Speaker initialization failed" }
        speaker = out
        out.play()
        mic.startRecording()
        speakerJob = scope.launch {
            while (isActive && epoch == generation) {
                val pcm = audioQueue.poll(150, TimeUnit.MILLISECONDS) ?: continue
                runCatching { out.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) }
                    .onFailure { break }
            }
        }
        microphoneJob = scope.launch {
            val buffer = ByteArray(4_800) // 100 ms, 24 kHz, mono PCM16
            while (isActive && epoch == generation) {
                val count = try { mic.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) }
                    catch (_: Exception) { break }
                if (count > 0) {
                    val payload = if (count == buffer.size) buffer else buffer.copyOf(count)
                    if (!ws.send(RealtimeProtocol.appendAudio(payload))) break
                } else if (count < 0) break
            }
        }
    }

    private fun fail(message: String) {
        disconnect()
        _state.value = RealtimeVoiceState(RealtimePhase.ERROR, message.take(240))
    }

    @Synchronized
    fun disconnect() {
        generation++
        responseActive = false
        limitJob?.cancel(); limitJob = null
        microphoneJob?.cancel(); microphoneJob = null
        speakerJob?.cancel(); speakerJob = null
        audioQueue.clear()
        val mic = microphone; microphone = null
        runCatching { mic?.stop() }
        runCatching { mic?.release() }
        echoCanceler?.release(); echoCanceler = null
        val out = speaker; speaker = null
        runCatching { out?.pause(); out?.flush(); out?.release() }
        val ws = socket; socket = null
        runCatching { ws?.close(1000, "User disconnected") }
        _state.value = RealtimeVoiceState()
    }

    override fun close() {
        disconnect()
        scope.cancel()
    }
}
